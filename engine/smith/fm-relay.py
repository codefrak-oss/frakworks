#!/usr/bin/env python3
"""fm-relay: the session container's entry point in a first-mate session pod.

engine/README.md, "First-mate sessions", "The pod relay protocol". The engine never attaches
to the pod: this relay runs `claude` as its child and connects out to the engine's `/first-mate/pod/{id}/` endpoints,
so any replica serves the session.

    fm-relay -- claude -p --input-format stream-json ...

- Inbound: it asks the engine for `delivered_seq` (an ack of 0, which never lowers it), long-polls `inbox?after=`,
  writes each message to claude's stdin as one stream-json user line (a plain text, or image blocks then the text
  block for a row with `images`), then acks its seq. An ack that fails is retried
  before the next poll, so a message is written once and never skipped.
- Outbound: each stdout line is numbered `out_seq` from 1 and appended to the spool (`$FM_SPOOL`, the pod's emptyDir)
  before it is posted in batches (50 lines or 200 ms) to `outbox` until the engine answers `{accepted}`; the spool
  drops what is accepted. While the engine is unreachable lines stay on disk; a relay restart in the same pod continues
  `out_seq` from the spool.
- Freeze: an inbox answer `{"freeze": true}` closes claude's stdin; once claude exits and the spool is flushed the relay
  posts `status exited` and stays up, so the engine can exec the snapshot before it deletes the pod.
- Resume (`FM_RESUME` set): before claude starts, the relay GETs `snapshot`, writes the Claude session file under
  `$HOME/.claude/projects/` and unpacks the folder into its cwd, and adds `--resume <claude id>` to claude's argv.

Environment: FM_ENGINE_URL, FM_SESSION_ID, FM_TOKEN (the session's first-mate-pod token), FM_SPOOL, FM_RESUME,
FM_POLL_WAIT (seconds, default 25). Python standard library only, as meter-proxy.py.
"""

import base64
import io
import json
import os
import re
import subprocess
import sys
import tarfile
import threading
import time
import urllib.error
import urllib.request

BATCH_LINES = 50
BATCH_SECONDS = 0.2


def log(msg):
    print(f"fm-relay: {msg}", file=sys.stderr, flush=True)


def user_content(body):
    """The user line's `content` for an inbox row's body: the plain text when it has no images (FirstMatePods'
    userLine), else one image block per image (`{media_type, data}`, the desktop's first-mate-images.js shape) and
    then the text block, left out when the text is empty."""
    images = body.get("images")
    text = body.get("text")
    if not isinstance(images, list) or not images:
        return text
    blocks = [{"type": "image", "source": {"type": "base64", "media_type": im["media_type"], "data": im["data"]}} for im in images]
    if text:
        blocks.append({"type": "text", "text": text})
    return blocks


def user_line(content):
    """The stream-json user line `claude -p --input-format stream-json` reads for content: a text, or its blocks."""
    return json.dumps({"type": "user", "message": {"role": "user", "content": content}}, separators=(",", ":")) + "\n"


def claude_session_path(home, cwd, claude_id):
    """Where Claude Code keeps conversation claude_id run in cwd (FirstMatePodSpec's claudeSessionPath)."""
    return os.path.join(home, ".claude", "projects", re.sub(r"[^A-Za-z0-9]", "-", cwd), claude_id + ".jsonl")


class Engine:
    """The engine's pod endpoints for one session, each call retried with backoff until it answers."""

    def __init__(self, url, session_id, token):
        self.base = f"{url.rstrip('/')}/first-mate/pod/{session_id}"
        self.token = token

    def call(self, method, path, body=None, timeout=35, retry=True):
        delay = 0.2
        while True:
            req = urllib.request.Request(self.base + path, method=method,
                                         data=None if body is None else json.dumps(body).encode(),
                                         headers={"Authorization": f"Bearer {self.token}", "Content-Type": "application/json"})
            try:
                with urllib.request.urlopen(req, timeout=timeout) as r:
                    return r.status, json.loads(r.read() or b"null")
            except urllib.error.HTTPError as e:
                if e.code < 500:
                    return e.code, None
                err = f"HTTP {e.code}"
            except (OSError, ValueError) as e:
                err = str(e)
            if not retry:
                return None, None
            log(f"{method} {path}: {err}; retrying in {delay:.1f}s")
            time.sleep(delay)
            delay = min(delay * 2, 2.0)


class Spool:
    """claude's stdout lines not yet accepted by the engine, on disk: `outbox.jsonl` ({o, l} per line) and `accepted`."""

    def __init__(self, folder):
        os.makedirs(folder, exist_ok=True)
        self.path = os.path.join(folder, "outbox.jsonl")
        self.accepted_path = os.path.join(folder, "accepted")
        self.cond = threading.Condition()
        self.accepted = int(open(self.accepted_path).read().strip() or 0) if os.path.exists(self.accepted_path) else 0
        self.pending = []
        if os.path.exists(self.path):
            for raw in open(self.path, encoding="utf-8"):
                try:
                    e = json.loads(raw)
                except ValueError:
                    continue  # a line cut by a crash mid-write
                if e["o"] > self.accepted:
                    self.pending.append((e["o"], e["l"]))
        self.next = max([self.accepted] + [o for o, _ in self.pending]) + 1

    def add(self, line):
        with self.cond:
            seq = self.next
            self.next += 1
            with open(self.path, "a", encoding="utf-8") as f:
                f.write(json.dumps({"o": seq, "l": line}) + "\n")
                f.flush()
                os.fsync(f.fileno())
            self.pending.append((seq, line))
            self.cond.notify_all()

    def batch(self, timeout):
        """Up to BATCH_LINES pending lines, waiting at most timeout for the first and BATCH_SECONDS for more."""
        with self.cond:
            if not self.pending:
                self.cond.wait(timeout)
            if self.pending and len(self.pending) < BATCH_LINES:
                self.cond.wait(BATCH_SECONDS)
            return list(self.pending[:BATCH_LINES])

    def accept(self, upto):
        with self.cond:
            if upto <= self.accepted:
                return
            self.accepted = upto
            self.pending = [(o, l) for o, l in self.pending if o > upto]
            tmp = self.accepted_path + ".tmp"
            with open(tmp, "w") as f:
                f.write(str(upto))
            os.replace(tmp, self.accepted_path)
            tmp = self.path + ".tmp"
            with open(tmp, "w", encoding="utf-8") as f:
                for o, l in self.pending:
                    f.write(json.dumps({"o": o, "l": l}) + "\n")
            os.replace(tmp, self.path)
            self.cond.notify_all()

    def empty(self):
        with self.cond:
            return not self.pending


def restore(engine, argv, home):
    """Fetches the session's snapshot and restores it; argv with `--resume <claude id>` when there is a session file."""
    status, snap = engine.call("GET", "/snapshot")
    if status != 200 or not isinstance(snap, dict):
        log(f"no snapshot to restore ({status})")
        return argv
    cwd = snap.get("cwd") or os.getcwd()
    claude_id = snap.get("claudeSessionId")
    if snap.get("workdirTar"):
        os.makedirs(cwd, exist_ok=True)
        with tarfile.open(fileobj=io.BytesIO(base64.b64decode(snap["workdirTar"])), mode="r:gz") as t:
            if hasattr(tarfile, "data_filter"):
                t.extractall(cwd, filter="data")
            else:
                t.extractall(cwd)
    if claude_id and snap.get("sessionFile"):
        path = claude_session_path(home, cwd, claude_id)
        os.makedirs(os.path.dirname(path), exist_ok=True)
        with open(path, "wb") as f:
            f.write(base64.b64decode(snap["sessionFile"]))
        if "--resume" not in argv:
            argv = argv + ["--resume", claude_id]
    return argv


class Relay:
    def __init__(self, engine, spool, argv, poll_wait):
        self.engine = engine
        self.spool = spool
        self.argv = argv
        self.poll_wait = poll_wait
        self.freezing = threading.Event()
        self.child = None
        self.stdout_done = threading.Event()

    def inbox(self):
        _, ack = self.engine.call("POST", "/inbox/ack", {"seq": 0})
        delivered = int((ack or {}).get("deliveredSeq", 0))
        while self.child.poll() is None and not self.freezing.is_set():
            status, answer = self.engine.call("GET", f"/inbox?after={delivered}&wait={self.poll_wait}", timeout=self.poll_wait + 10)
            if isinstance(answer, dict) and answer.get("freeze"):
                self.freezing.set()
                log("freezing: closing claude's stdin")
                try:
                    self.child.stdin.close()
                except OSError:
                    pass
                return
            if status != 200 or not isinstance(answer, list):
                time.sleep(1)
                continue
            for m in answer:
                seq = int(m["seq"])
                if seq <= delivered:
                    continue
                body = m.get("body")
                content = user_content(body) if isinstance(body, dict) and (body.get("text") is not None or body.get("images")) else None
                if content is not None:
                    try:
                        self.child.stdin.write(user_line(content).encode())
                        self.child.stdin.flush()
                    except (OSError, ValueError):
                        return  # claude is gone
                self.engine.call("POST", "/inbox/ack", {"seq": seq})
                delivered = seq

    def stdout(self):
        for raw in self.child.stdout:
            line = raw.decode("utf-8", "replace").rstrip("\r\n")
            if line.strip():
                self.spool.add(line)
        self.stdout_done.set()

    def sender(self):
        while True:
            batch = self.spool.batch(0.5)
            if not batch:
                if self.stdout_done.is_set() and self.spool.empty():
                    return
                continue
            status, answer = self.engine.call("POST", "/outbox", {"from": batch[0][0], "lines": [l for _, l in batch]})
            if status == 200 and isinstance(answer, dict):
                self.spool.accept(int(answer.get("accepted", 0)))
            else:
                log(f"outbox refused ({status}); retrying")
                time.sleep(1)

    def run(self):
        self.child = subprocess.Popen(self.argv, stdin=subprocess.PIPE, stdout=subprocess.PIPE)
        self.engine.call("POST", "/status", {"state": "running"})
        threads = [threading.Thread(target=f, daemon=True) for f in (self.stdout, self.sender, self.inbox)]
        for t in threads:
            t.start()
        code = self.child.wait()
        threads[0].join()
        threads[1].join()
        self.engine.call("POST", "/status", {"state": "exited", "code": code})
        log(f"claude exited {code}")
        return code


def main(args):
    if args and args[0] == "--":
        args = args[1:]
    if not args:
        log("usage: fm-relay -- <claude argv>")
        return 2
    env = os.environ
    engine = Engine(env["FM_ENGINE_URL"], env["FM_SESSION_ID"], env["FM_TOKEN"])
    spool = Spool(env.get("FM_SPOOL", "/var/spool/fm-relay"))
    if env.get("FM_RESUME"):
        args = restore(engine, args, env.get("HOME", os.path.expanduser("~")))
    relay = Relay(engine, spool, args, int(env.get("FM_POLL_WAIT", "25")))
    code = relay.run()
    if relay.freezing.is_set():
        # The engine execs the snapshot into this container, then deletes the pod: stay up until then.
        while True:
            time.sleep(3600)
    return code


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
