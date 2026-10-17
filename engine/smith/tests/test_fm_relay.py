"""Tests for engine/smith/fm-relay.py, the session pod's relay between claude and the engine.

A fake claude (an echo script) runs behind the relay against an in-process fake engine that speaks the pod protocol
(engine/README.md, "First-mate sessions", "The pod relay protocol"): inbox long-poll and ack, outbox stored once per out_seq, status
and snapshot. The fake engine keeps its state across a stop and restart on the same port, as Postgres does.
"""

import base64
import http.server
import io
import json
import os
import re
import socket
import subprocess
import sys
import tarfile
import tempfile
import threading
import time
import unittest
import urllib.parse

HERE = os.path.dirname(os.path.abspath(__file__))
RELAY_PY = os.path.join(os.path.dirname(HERE), "fm-relay.py")
TOKEN = "fw_pod-token"
SESSION = "s-1"

# Echoes each stream-json user line back as an assistant line; prints its argv first so a test sees --resume.
FAKE_CLAUDE = r'''
import json, sys
print(json.dumps({"type": "system", "subtype": "init", "argv": sys.argv[1:]}), flush=True)
for raw in sys.stdin:
    m = json.loads(raw)
    print(json.dumps({"type": "assistant", "echo": m["message"]["content"]}), flush=True)
'''


def free_port():
    s = socket.socket()
    s.bind(("127.0.0.1", 0))
    port = s.getsockname()[1]
    s.close()
    return port


class FakeEngine:
    """The engine side of one session: user messages, delivered_seq, stored out lines, statuses, an optional snapshot."""

    def __init__(self):
        self.lock = threading.Condition()
        self.users = []  # (seq, body): the row's body, {"text"} or {"text", "images"}
        self.delivered = 0
        self.out = {}  # out_seq -> line
        self.posts = 0
        self.statuses = []
        self.freezing = False
        self.snapshot = None
        self.server = None
        self.port = free_port()

    def add_user(self, text, images=None):
        with self.lock:
            body = {"text": text} if images is None else {"text": text, "images": images}
            self.users.append((len(self.users) + 1, body))
            self.lock.notify_all()

    def handler(self):
        engine = self

        class H(http.server.BaseHTTPRequestHandler):
            def log_message(self, *a):
                pass

            def answer(self, code, body):
                data = json.dumps(body).encode()
                self.send_response(code)
                self.send_header("Content-Type", "application/json")
                self.send_header("Content-Length", str(len(data)))
                self.end_headers()
                self.wfile.write(data)

            def route(self):
                if self.headers.get("Authorization") != f"Bearer {TOKEN}":
                    self.answer(401, {"error": "token"})
                    return None
                u = urllib.parse.urlparse(self.path)
                prefix = f"/first-mate/pod/{SESSION}"
                assert u.path.startswith(prefix), u.path
                return u.path[len(prefix):], urllib.parse.parse_qs(u.query)

            def do_GET(self):
                r = self.route()
                if r is None:
                    return
                path, q = r
                if path == "/inbox":
                    after, wait = int(q["after"][0]), float(q["wait"][0])
                    with engine.lock:
                        deadline = time.time() + wait
                        while not engine.freezing and not [u for u in engine.users if u[0] > after] and time.time() < deadline:
                            engine.lock.wait(0.05)
                        if engine.freezing:
                            return self.answer(200, {"freeze": True})
                        msgs = [{"seq": s, "role": "user", "body": b} for s, b in engine.users if s > after]
                    return self.answer(200, msgs)
                if path == "/snapshot":
                    return self.answer(200, engine.snapshot) if engine.snapshot else self.answer(404, {"error": "none"})
                self.answer(404, {})

            def do_POST(self):
                r = self.route()
                if r is None:
                    return
                path, _ = r
                body = json.loads(self.rfile.read(int(self.headers["Content-Length"])))
                with engine.lock:
                    if path == "/inbox/ack":
                        engine.delivered = max(engine.delivered, body["seq"])
                        return self.answer(200, {"deliveredSeq": engine.delivered})
                    if path == "/outbox":
                        engine.posts += 1
                        for i, line in enumerate(body["lines"]):
                            engine.out.setdefault(body["from"] + i, line)
                        return self.answer(200, {"accepted": max(engine.out, default=0)})
                    if path == "/status":
                        engine.statuses.append(body)
                        return self.answer(200, {})
                self.answer(404, {})

        return H

    def start(self):
        self.server = http.server.ThreadingHTTPServer(("127.0.0.1", self.port), self.handler())
        self.server.daemon_threads = True
        self.server.handle_error = lambda *a: None  # a killed relay's broken pipe
        self.server.allow_reuse_address = True
        threading.Thread(target=self.server.serve_forever, daemon=True).start()

    def stop(self):
        self.server.shutdown()
        self.server.server_close()

    def echoes(self):
        with self.lock:
            lines = [json.loads(self.out[k]) for k in sorted(self.out)]
        return [l["echo"] for l in lines if l.get("type") == "assistant"]

    def wait_for(self, cond, timeout=15):
        end = time.time() + timeout
        while time.time() < end:
            with self.lock:
                if cond():
                    return True
            time.sleep(0.05)
        raise AssertionError("timed out")


class RelayTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.mkdtemp()
        self.claude = os.path.join(self.tmp, "claude.py")
        with open(self.claude, "w") as f:
            f.write(FAKE_CLAUDE)
        self.engine = FakeEngine()
        self.engine.start()
        self.procs = []

    def tearDown(self):
        for p in self.procs:
            if p.poll() is None:
                p.kill()
                p.wait()
        try:
            self.engine.stop()
        except Exception:
            pass

    def relay(self, resume=False, home=None):
        env = dict(os.environ, FM_ENGINE_URL=f"http://127.0.0.1:{self.engine.port}", FM_SESSION_ID=SESSION, FM_TOKEN=TOKEN,
                   FM_SPOOL=os.path.join(self.tmp, "spool"), FM_POLL_WAIT="1", HOME=home or self.tmp)
        if resume:
            env["FM_RESUME"] = "1"
        p = subprocess.Popen([sys.executable, "-I", RELAY_PY, "--", sys.executable, "-I", self.claude, "-p"],
                             env=env, cwd=self.tmp, stderr=subprocess.DEVNULL)
        self.procs.append(p)
        return p

    def test_engine_stopped_and_restarted_mid_session_loses_and_doubles_nothing(self):
        self.relay()
        for i in range(1, 6):
            self.engine.add_user(f"m{i}")
        self.engine.wait_for(lambda: len(self.engine.out) >= 6)
        self.engine.stop()
        time.sleep(0.5)
        for i in range(6, 11):
            self.engine.add_user(f"m{i}")  # queued in the DB while no replica serves
        self.engine.start()
        self.engine.wait_for(lambda: len(self.engine.out) >= 11)
        for i in range(11, 21):
            self.engine.add_user(f"m{i}")
        self.engine.wait_for(lambda: len(self.engine.out) >= 21)
        time.sleep(0.5)
        self.assertEqual([f"m{i}" for i in range(1, 21)], self.engine.echoes())
        self.assertEqual(list(range(1, 22)), sorted(self.engine.out))
        self.assertEqual(20, self.engine.delivered)

    def test_a_row_with_images_becomes_a_block_list_user_line_and_a_plain_row_the_text(self):
        self.relay()
        png = {"media_type": "image/png", "data": "iVBORw0KGgo="}
        jpeg = {"media_type": "image/jpeg", "data": "/9j/4AAQ"}
        self.engine.add_user("what is this?", [png, jpeg])
        self.engine.add_user("plain")
        self.engine.wait_for(lambda: len(self.engine.out) >= 3)
        blocks, plain = self.engine.echoes()
        self.assertEqual([
            {"type": "image", "source": {"type": "base64", "media_type": "image/png", "data": "iVBORw0KGgo="}},
            {"type": "image", "source": {"type": "base64", "media_type": "image/jpeg", "data": "/9j/4AAQ"}},
            {"type": "text", "text": "what is this?"},
        ], blocks)
        self.assertEqual("plain", plain)
        self.assertEqual(2, self.engine.delivered)

    def test_lines_printed_while_the_engine_is_down_are_spooled_and_delivered_once(self):
        self.relay()
        self.engine.add_user("a")
        self.engine.wait_for(lambda: len(self.engine.out) >= 2)
        # The message was delivered; then the engine goes away before claude's reply to the next one is posted.
        self.engine.add_user("b")
        self.engine.wait_for(lambda: self.engine.delivered >= 2)
        self.engine.stop()
        time.sleep(1)
        self.engine.start()
        self.engine.wait_for(lambda: len(self.engine.out) >= 3)
        self.assertEqual(["a", "b"], self.engine.echoes())

    def test_a_relay_restart_in_the_pod_continues_out_seq_from_the_spool_and_inbox_after_delivered_seq(self):
        p = self.relay()
        self.engine.add_user("one")
        self.engine.wait_for(lambda: len(self.engine.out) >= 2)
        p.kill()
        p.wait()
        self.engine.add_user("two")
        self.relay()
        self.engine.wait_for(lambda: len(self.engine.out) >= 4)
        time.sleep(0.5)
        # out_seq 1-2 from the first relay, 3 (the new claude's init) and 4 from the second; "one" is not re-sent.
        self.assertEqual([1, 2, 3, 4], sorted(self.engine.out))
        self.assertEqual(["one", "two"], self.engine.echoes())

    def test_freeze_closes_stdin_flushes_posts_exited_and_stays_up_for_the_snapshot(self):
        p = self.relay()
        self.engine.add_user("last")
        self.engine.wait_for(lambda: len(self.engine.out) >= 2)
        with self.engine.lock:
            self.engine.freezing = True
            self.engine.lock.notify_all()
        self.engine.wait_for(lambda: any(s["state"] == "exited" for s in self.engine.statuses))
        self.assertEqual({"state": "exited", "code": 0}, self.engine.statuses[-1])
        self.assertEqual(["last"], self.engine.echoes())
        time.sleep(0.5)
        self.assertIsNone(p.poll(), "the relay stays up so the engine can exec the snapshot")

    def test_resume_restores_the_snapshot_and_starts_claude_with_resume(self):
        buf = io.BytesIO()
        with tarfile.open(fileobj=buf, mode="w:gz") as t:
            data = b"kept\n"
            info = tarfile.TarInfo("notes.txt")
            info.size = len(data)
            t.addfile(info, io.BytesIO(data))
        cwd = os.path.join(self.tmp, "work", "repo")
        self.engine.snapshot = {"claudeSessionId": "0b5e-77", "cwd": cwd, "deliveredSeq": 0,
                                "sessionFile": base64.b64encode(b'{"type":"user"}\n').decode(),
                                "workdirTar": base64.b64encode(buf.getvalue()).decode(), "size": 5, "dropped": False}
        home = os.path.join(self.tmp, "home")
        self.relay(resume=True, home=home)
        self.engine.wait_for(lambda: len(self.engine.out) >= 1)
        init = json.loads(self.engine.out[1])
        self.assertEqual(["-p", "--resume", "0b5e-77"], init["argv"])
        with open(os.path.join(cwd, "notes.txt")) as f:
            self.assertEqual("kept\n", f.read())
        session_file = os.path.join(home, ".claude", "projects", re.sub(r"[^A-Za-z0-9]", "-", cwd), "0b5e-77.jsonl")
        self.assertTrue(os.path.exists(session_file), session_file)
        self.assertEqual("running", self.engine.statuses[0]["state"])


if __name__ == "__main__":
    unittest.main()
