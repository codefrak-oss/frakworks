"""scripts/intake-notify-watcher.py: one `claude -p` per row, the report the engine gets, and the failures.

The engine is a fake HTTP server in-process (what the watcher polls and reports to), and
`claude -p` is a recorded runner, so no session, no socket and no Claude are involved.
"""
from __future__ import annotations

import importlib.util
import json
import os
import subprocess
import sys
import threading
import unittest
from http.server import BaseHTTPRequestHandler, HTTPServer

HERE = os.path.dirname(os.path.abspath(__file__))
spec = importlib.util.spec_from_file_location("watcher", os.path.join(HERE, "..", "intake-notify-watcher.py"))
watcher = importlib.util.module_from_spec(spec)
spec.loader.exec_module(watcher)

ROW = {
    "id": 7, "intake": "9d661652", "session": "first-mate", "repo": "acme/tickets", "ticket": 928,
    "ticketUrl": "https://github.com/acme/tickets/issues/928",
    "message": "Intake 9d661652 was refined and filed as ticket #928 in acme/tickets: https://github.com/acme/tickets/issues/928. "
               "Tell the user, in one line, that the ticket you created is #928, with #928 a markdown link to that url.",
    "deliveredAt": None,
}


class FakeEngine:
    """Serves /intake/notifications from `rows`; records every delivered report; 409 on a second report."""

    def __init__(self):
        self.rows = []
        self.reports = []
        self.delivered_ids = set()
        engine = self

        class Handler(BaseHTTPRequestHandler):
            def log_message(self, *a): pass

            def do_GET(self):
                assert self.path == "/intake/notifications", self.path
                body = json.dumps([r for r in engine.rows if r["id"] not in engine.delivered_ids]).encode()
                self.send_response(200); self.send_header("Content-Type", "application/json"); self.end_headers(); self.wfile.write(body)

            def do_POST(self):
                _, _, _, id_, verb = self.path.split("/")
                assert verb == "delivered"
                payload = json.loads(self.rfile.read(int(self.headers["Content-Length"])))
                if int(id_) in engine.delivered_ids:
                    self.send_response(409); self.end_headers(); return
                engine.delivered_ids.add(int(id_)); engine.reports.append((int(id_), payload))
                self.send_response(200); self.send_header("Content-Type", "application/json"); self.end_headers()
                self.wfile.write(b"{}")

        self.server = HTTPServer(("127.0.0.1", 0), Handler)
        threading.Thread(target=self.server.serve_forever, daemon=True).start()
        self.url = f"http://127.0.0.1:{self.server.server_port}"

    def close(self): self.server.shutdown(); self.server.server_close()


class RecordingRunner:
    def __init__(self, ok=True, text="msg_id: abc"):
        self.calls = []; self.ok = ok; self.text = text

    def __call__(self, session, message, timeout, model):
        self.calls.append((session, message, timeout, model)); return self.ok, self.text


class WatcherTest(unittest.TestCase):
    def setUp(self):
        self.fake = FakeEngine(); self.engine = watcher.Engine(self.fake.url)

    def tearDown(self): self.fake.close()

    def test_one_row_is_one_claude_p_call_and_one_sent_report(self):
        self.fake.rows = [dict(ROW)]
        runner = RecordingRunner()
        self.assertEqual(["sent"], watcher.poll(self.engine, "mac", 120, set(), runner))
        (session, message, timeout, model), = runner.calls
        self.assertEqual(("first-mate", ROW["message"], 120, "haiku"), (session, message, timeout, model))
        (id_, report), = self.fake.reports
        self.assertEqual(7, id_)
        self.assertEqual({"by": "mac", "result": "sent: msg_id: abc"}, report)

    def test_the_same_row_is_never_run_twice_by_one_watcher_or_by_two(self):
        self.fake.rows = [dict(ROW)]
        runner = RecordingRunner(); seen = set()
        watcher.poll(self.engine, "mac", 120, seen, runner)
        watcher.poll(self.engine, "mac", 120, seen, runner)
        self.assertEqual(1, len(runner.calls), "the engine lists it no more, and `seen` would stop it anyway")
        self.assertEqual(1, len(self.fake.reports))
        # A second watcher that read the row before the first reported: its report is a 409, and 'lost'.
        self.fake.delivered_ids.clear(); self.fake.rows[0]["id"] = 7
        other = RecordingRunner()
        outcomes = watcher.poll(self.engine, "other-mac", 120, set(), other)
        self.assertEqual(["sent"], outcomes)
        self.fake.delivered_ids.add(7)
        late = watcher.deliver(self.engine, dict(ROW), "late-mac", 120, RecordingRunner())
        self.assertEqual("lost", late)
        self.assertEqual(2, len(self.fake.reports), "the late one's report was refused")

    def test_a_failed_claude_p_is_reported_as_failed_with_what_it_printed_and_not_retried(self):
        self.fake.rows = [dict(ROW)]
        runner = RecordingRunner(ok=False, text="exit 1\nNO_SESSION first-mate")
        seen = set()
        self.assertEqual(["failed"], watcher.poll(self.engine, "mac", 120, seen, runner))
        self.assertEqual("failed: exit 1\nNO_SESSION first-mate", self.fake.reports[0][1]["result"])
        self.assertEqual([], watcher.poll(self.engine, "mac", 120, seen, runner), "delivered rows are off the list")
        self.assertEqual(1, len(runner.calls))

    def test_an_unreachable_engine_is_logged_and_the_poll_returns_nothing(self):
        dead = watcher.Engine("http://127.0.0.1:9")
        self.assertEqual([], watcher.poll(dead, "mac", 120, set(), RecordingRunner()))

    def test_the_claude_command_names_the_target_once_and_carries_the_message_verbatim(self):
        cmd = watcher.claude_command("first-mate", ROW["message"])
        self.assertEqual(["claude", "-p", "--model", "haiku", "--no-session-persistence", "-n", "engine"], cmd[:7])
        prompt = cmd[7]
        self.assertIn("session named first-mate", prompt)
        self.assertIn("NO_SESSION first-mate", prompt)
        self.assertTrue(prompt.endswith(ROW["message"]))
        self.assertEqual(1, prompt.count("SendMessage"))

    def test_run_claude_times_out_and_says_so(self):
        real = subprocess.run

        def slow(cmd, **kw):
            raise subprocess.TimeoutExpired(cmd, kw["timeout"], output=b"partial")
        subprocess.run = slow
        try:
            ok, text = watcher.run_claude("first-mate", "hi", 3)
        finally:
            subprocess.run = real
        self.assertFalse(ok)
        self.assertEqual("timed out after 3s\npartial", text)

    def test_run_claude_without_claude_on_path(self):
        real = subprocess.run

        def missing(cmd, **kw): raise FileNotFoundError(cmd[0])
        subprocess.run = missing
        try:
            ok, text = watcher.run_claude("first-mate", "hi", 3)
        finally:
            subprocess.run = real
        self.assertEqual((False, "claude not found on PATH"), (ok, text))


if __name__ == "__main__":
    unittest.main()
