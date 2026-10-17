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
- Clone (`fm-relay --clone-sidecar <dir>`, the pod's native sidecar `clone`, the only container given `FM_GIT_TOKEN`):
  clones `FM_REPO`'s default branch from GitHub into `<dir>/repo` in the background, as a Smith's clone (`--depth 50`),
  the token answered by git-askpass, so it is in no URL, no `.git/config` and so no snapshot; then writes `<dir>/.done`
  or `<dir>/.failed` (the error) and watches for `<dir>/.request` (written by `checkout_repo` with retry) to clone again.
  `fm-relay --clone <dir>` clones once and exits 0.
- Checkout (`FM_REPO` set): claude never waits for the clone. A cwd with a `.git` (a resumed checkout) is kept. Else,
  when the clone is already done it is copied in before claude starts; while it is pending claude starts with a note
  in its appended prompt, and a watcher copies the clone in once `.done` appears and writes First mate a system note
  (ready, or failed with the error).
- `fm-relay --mcp-checkout`: a stdio MCP server, the `checkout` server of the pod's `--mcp-config`, with one tool,
  `checkout_repo` (status pending/ready/failed, `wait` seconds, `retry`).

Environment: FM_ENGINE_URL, FM_SESSION_ID, FM_TOKEN (the session's first-mate-pod token), FM_SPOOL, FM_RESUME,
FM_REPO, FM_CLONE, FM_GIT_TOKEN (the clone sidecar only), FM_POLL_WAIT (seconds, default 25). Python standard library only, as meter-proxy.py.
"""

import base64
import io
import json
import os
import re
import shutil
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


GIT_URL = "https://github.com"
ASKPASS = "/usr/local/bin/git-askpass"
CODE_UNAVAILABLE = ("System note: the code of {repo} could not be cloned into {cwd} ({error}), so it is unavailable "
                    "for now; say so if asked about the code rather than guessing. The checkout_repo tool with retry "
                    "true clones it again.")
CODE_PENDING = ("System note: the code of {repo} is being cloned into {cwd} in the background. Until a system note "
                "says it is ready, call the checkout_repo tool (wait up to 120 seconds) before reading the code.")
CODE_READY = "System note: the code of {repo} is now checked out in {cwd}. No reply is needed."
DONE, FAILED, REQUEST = ".done", ".failed", ".request"


def git_env(token):
    """git's env for a clone: the token for git-askpass alone (GH_TOKEN), never in a URL; no prompt."""
    env = {k: v for k, v in os.environ.items() if k not in ("FM_GIT_TOKEN", "FM_TOKEN")}
    env.update({"GIT_TERMINAL_PROMPT": "0", "GIT_ASKPASS": os.environ.get("FM_GIT_ASKPASS", ASKPASS)})
    if token:
        env["GH_TOKEN"] = token
    return env


def write_marker(path, text):
    tmp = path + ".tmp"
    with open(tmp, "w") as f:
        f.write(text)
    os.replace(tmp, path)


def clone(repo, base, token, git_url=GIT_URL):
    """Clones repo's default branch into `<base>/repo` (afresh), then writes `<base>/.done` or `<base>/.failed` with the
    error; whether it did. Only the clone sidecar holds the token, so claude (in the main container) can never read it."""
    url = f"{git_url.rstrip('/')}/{repo}"
    dest = os.path.join(base, "repo")
    for m in (DONE, FAILED):
        try:
            os.remove(os.path.join(base, m))
        except OSError:
            pass
    try:
        os.makedirs(base, exist_ok=True)
        shutil.rmtree(dest, ignore_errors=True)
        r = subprocess.run(["git", "clone", "--quiet", "--depth", "50", url, dest], env=git_env(token),
                           stdin=subprocess.DEVNULL, stdout=subprocess.DEVNULL, stderr=subprocess.PIPE, timeout=600)
        if r.returncode != 0:
            error = f"git clone failed ({r.returncode}): {r.stderr.decode('utf-8', 'replace').strip()[-500:]}"
        else:
            log(f"cloned {repo} into {dest}")
            write_marker(os.path.join(base, DONE), "")
            return True
    except (OSError, subprocess.SubprocessError) as e:
        error = f"git clone failed: {e}"
    log(f"{repo}: {error}")
    try:
        write_marker(os.path.join(base, FAILED), error)
    except OSError:
        pass
    return False


def clone_sidecar(repo, base, token, git_url=GIT_URL, tick=1.0, once=False):
    """`fm-relay --clone-sidecar <base>`: clones at once, then clones again each time `<base>/.request` appears."""
    clone(repo, base, token, git_url)
    while not once:
        req = os.path.join(base, REQUEST)
        if os.path.exists(req):
            try:
                os.remove(req)
            except OSError:
                pass
            clone(repo, base, token, git_url)
        time.sleep(tick)


def clone_state(base):
    """("ready", None), ("failed", error) or ("pending", None) for the clone in base."""
    if base and os.path.exists(os.path.join(base, DONE)):
        return "ready", None
    if base and os.path.exists(os.path.join(base, FAILED)):
        try:
            with open(os.path.join(base, FAILED)) as f:
                return "failed", f.read().strip() or "unknown error"
        except OSError:
            return "failed", "unknown error"
    return "pending", None


def checkout(cwd, base):
    """Gives cwd the sidecar's clone in `<base>/repo` unless it already has a `.git`; whether cwd holds a checkout. Into
    a folder that already has files (a snapshot without a checkout, or files claude wrote) only the clone's `.git` is
    copied in, then the tracked files not already there are written."""
    if os.path.isdir(os.path.join(cwd, ".git")):
        return True
    src = os.path.join(base, "repo") if base else None
    if not src or clone_state(base)[0] != "ready" or not os.path.isdir(os.path.join(src, ".git")):
        return False
    try:
        os.makedirs(cwd, exist_ok=True)
        if not os.listdir(cwd):
            shutil.copytree(src, cwd, symlinks=True, dirs_exist_ok=True)
        else:
            shutil.copytree(os.path.join(src, ".git"), os.path.join(cwd, ".git"), symlinks=True)
            subprocess.run(["git", "-C", cwd, "checkout-index", "--all", "--quiet"], stdin=subprocess.DEVNULL,
                           stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        return True
    except OSError as e:
        log(f"copying the checkout into {cwd} failed: {e}")
        return False


def append_note(argv, note):
    """argv with note at the end of its --append-system-prompt (added when there is none)."""
    argv = list(argv)
    if "--append-system-prompt" in argv:
        i = argv.index("--append-system-prompt") + 1
        argv[i] = f"{argv[i]}\n\n{note}" if argv[i] else note
    else:
        argv += ["--append-system-prompt", note]
    return argv


def checkout_tool(cwd, base, wait=0, retry=False, tick=0.5):
    """The checkout_repo tool's answer: the clone's state after an optional retry and a wait of up to wait seconds."""
    if retry and not os.path.isdir(os.path.join(cwd, ".git")) and clone_state(base)[0] != "pending":
        write_marker(os.path.join(base, REQUEST), "")
        for m in (DONE, FAILED):  # the sidecar removes them too; drop them now so the answer is pending at once
            try:
                os.remove(os.path.join(base, m))
            except OSError:
                pass
    deadline = time.time() + max(0, min(float(wait or 0), 300))
    while True:
        if os.path.isdir(os.path.join(cwd, ".git")):
            return f"ready: the code is checked out in {cwd}."
        state, error = clone_state(base)
        if state == "failed":
            return f"failed: {error}. Call checkout_repo with retry true to clone again."
        if state == "ready" and os.path.isdir(os.path.join(cwd, ".git")):
            return f"ready: the code is checked out in {cwd}."
        if time.time() >= deadline:
            return ("ready: the clone is done and is being copied into " + cwd + "; call again in a moment.") if state == "ready" \
                else "pending: the clone is still running; call again with wait to wait for it."
        time.sleep(tick)


CHECKOUT_TOOL = {
    "name": "checkout_repo",
    "description": "The state of this session's checkout of its repo in the working folder: ready, pending (being cloned "
                   "in the background) or failed. Call it before reading the code until a system note says it is ready.",
    "inputSchema": {"type": "object", "properties": {
        "wait": {"type": "number", "description": "Seconds to wait for a pending clone (at most 300)."},
        "retry": {"type": "boolean", "description": "Clone again after a failure."}}},
}


def mcp_checkout(cwd, base, rin=sys.stdin, rout=sys.stdout):
    """`fm-relay --mcp-checkout`: a stdio MCP server (newline-delimited JSON-RPC) with the checkout_repo tool."""
    for raw in rin:
        try:
            m = json.loads(raw)
        except ValueError:
            continue
        if "id" not in m:
            continue  # a notification
        method, params = m.get("method"), m.get("params") or {}
        if method == "initialize":
            result = {"protocolVersion": params.get("protocolVersion", "2025-06-18"), "capabilities": {"tools": {}},
                      "serverInfo": {"name": "checkout", "version": "1"}}
        elif method == "tools/list":
            result = {"tools": [CHECKOUT_TOOL]}
        elif method == "tools/call" and params.get("name") == "checkout_repo":
            a = params.get("arguments") or {}
            result = {"content": [{"type": "text", "text": checkout_tool(cwd, base, a.get("wait", 0), bool(a.get("retry")))}]}
        elif method == "ping":
            result = {}
        else:
            rout.write(json.dumps({"jsonrpc": "2.0", "id": m["id"], "error": {"code": -32601, "message": f"no method {method}"}}) + "\n")
            rout.flush()
            continue
        rout.write(json.dumps({"jsonrpc": "2.0", "id": m["id"], "result": result}) + "\n")
        rout.flush()


class Relay:
    def __init__(self, engine, spool, argv, poll_wait):
        self.engine = engine
        self.spool = spool
        self.argv = argv
        self.poll_wait = poll_wait
        self.freezing = threading.Event()
        self.child = None
        self.stdout_done = threading.Event()
        self.stdin_lock = threading.Lock()
        self.watch = None  # (repo, cwd, base) while the clone is pending

    def note(self, text):
        """Writes a system note to claude as a user line; whether claude took it."""
        try:
            with self.stdin_lock:
                self.child.stdin.write(user_line(user_content({"text": text})).encode())
                self.child.stdin.flush()
            return True
        except (OSError, ValueError):
            return False

    def watcher(self, tick=1.0):
        """Copies the sidecar's clone into cwd once it is done and tells claude; tells claude of each failure."""
        repo, cwd, base = self.watch
        told = clone_state(base)[1]  # a failure at start is in the appended prompt already
        while self.child.poll() is None and not self.freezing.is_set():
            state, error = clone_state(base)
            if state == "ready" and checkout(cwd, base):
                self.note(CODE_READY.format(repo=repo, cwd=cwd))
                return
            if state == "failed" and told != error:
                told = error
                self.note(CODE_UNAVAILABLE.format(repo=repo, cwd=cwd, error=error))
            elif state == "pending":
                told = None
            time.sleep(tick)

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
                        with self.stdin_lock:
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
        env = dict(os.environ)
        self.child = subprocess.Popen(self.argv, stdin=subprocess.PIPE, stdout=subprocess.PIPE, env=env)
        self.engine.call("POST", "/status", {"state": "running"})
        threads = [threading.Thread(target=f, daemon=True) for f in (self.stdout, self.sender, self.inbox)]
        for t in threads:
            t.start()
        if self.watch:
            threading.Thread(target=self.watcher, daemon=True).start()
        code = self.child.wait()
        threads[0].join()
        threads[1].join()
        self.engine.call("POST", "/status", {"state": "exited", "code": code})
        log(f"claude exited {code}")
        return code


def main(args):
    env = os.environ
    if args[:1] in (["--clone"], ["--clone-sidecar"]) and len(args) == 2:
        if env.get("FM_REPO"):
            clone_sidecar(env["FM_REPO"], args[1], env.get("FM_GIT_TOKEN"), env.get("FM_GIT_URL", GIT_URL), once=args[0] == "--clone")
        elif args[0] == "--clone-sidecar":
            while True:  # nothing to clone; a sidecar that exits is restarted
                time.sleep(3600)
        return 0  # a failed clone never keeps the session from starting: the relay notes it
    if args == ["--mcp-checkout"]:
        mcp_checkout(os.getcwd(), env.get("FM_CLONE"))
        return 0
    if args and args[0] == "--":
        args = args[1:]
    if not args:
        log("usage: fm-relay -- <claude argv>")
        return 2
    engine = Engine(env["FM_ENGINE_URL"], env["FM_SESSION_ID"], env["FM_TOKEN"])
    spool = Spool(env.get("FM_SPOOL", "/var/spool/fm-relay"))
    if env.get("FM_RESUME"):
        args = restore(engine, args, env.get("HOME", os.path.expanduser("~")))
    repo, watch = env.get("FM_REPO"), None
    if repo:
        cwd, base = os.getcwd(), env.get("FM_CLONE")
        if not checkout(cwd, base):
            state, error = clone_state(base)
            if state == "failed":
                args = append_note(args, CODE_UNAVAILABLE.format(repo=repo, cwd=cwd, error=error))
            else:
                args = append_note(args, CODE_PENDING.format(repo=repo, cwd=cwd))
            watch = (repo, cwd, base)
    relay = Relay(engine, spool, args, int(env.get("FM_POLL_WAIT", "25")))
    relay.watch = watch
    code = relay.run()
    if relay.freezing.is_set():
        # The engine execs the snapshot into this container, then deletes the pod: stay up until then.
        while True:
            time.sleep(3600)
    return code


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
