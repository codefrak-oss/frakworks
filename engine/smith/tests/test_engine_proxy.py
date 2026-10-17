"""Tests for engine/smith/engine-proxy.py, the ride-out proxy between a Smith and the engine.

It retries a refused connection and a 503 with backoff for up to its budget, passes
anything else (and a streamed body) straight through, and gives up when the budget is spent.
"""

import http.client
import http.server
import importlib.util
import os
import socket
import socketserver
import subprocess
import sys
import threading
import time
import unittest

PROXY_PY = os.path.join(os.path.dirname(os.path.dirname(os.path.abspath(__file__))), "engine-proxy.py")
spec = importlib.util.spec_from_file_location("engine_proxy", PROXY_PY)
engine_proxy = importlib.util.module_from_spec(spec)
spec.loader.exec_module(engine_proxy)


def free_port():
    s = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    s.bind(("127.0.0.1", 0))
    port = s.getsockname()[1]
    s.close()
    return port


def wait_for_port(port, timeout=10):
    deadline = time.time() + timeout
    while time.time() < deadline:
        try:
            with socket.create_connection(("127.0.0.1", port), timeout=0.5):
                return
        except OSError:
            time.sleep(0.05)
    raise TimeoutError(f"nothing listening on 127.0.0.1:{port}")


class Server(socketserver.ThreadingMixIn, http.server.HTTPServer):
    daemon_threads = True
    allow_reuse_address = True


def stub(port, statuses, seen):
    """An engine answering `statuses` in order (then 200), recording each path; /stream streams three SSE frames."""

    class H(http.server.BaseHTTPRequestHandler):
        protocol_version = "HTTP/1.1"

        def log_message(self, *a):
            pass

        def do_GET(self):
            seen.append(self.path)
            if self.path.endswith("/stream"):
                self.send_response(200)
                self.send_header("Content-Type", "text/event-stream")
                self.send_header("Connection", "close")
                self.end_headers()
                for i in range(3):
                    self.wfile.write(f"data: {i}\n\n".encode())
                    self.wfile.flush()
                    time.sleep(0.05)
                return
            status = statuses.pop(0) if statuses else 200
            body = f"status {status}".encode()
            self.send_response(status)
            self.send_header("Content-Length", str(len(body)))
            self.end_headers()
            self.wfile.write(body)

        do_POST = do_GET

    srv = Server(("127.0.0.1", port), H)
    threading.Thread(target=srv.serve_forever, daemon=True).start()
    return srv


class EngineProxyTest(unittest.TestCase):
    def start_proxy(self, upstream_port, budget="30"):
        port = free_port()
        env = dict(os.environ, ENGINE_PROXY_PORT=str(port), UPSTREAM=f"http://127.0.0.1:{upstream_port}/base", ENGINE_PROXY_BUDGET=budget)
        p = subprocess.Popen([sys.executable, PROXY_PY], env=env, stderr=subprocess.DEVNULL)
        self.addCleanup(p.kill)
        wait_for_port(port)
        return port

    def get(self, port, path="/x"):
        c = http.client.HTTPConnection("127.0.0.1", port, timeout=60)
        c.request("GET", path)
        r = c.getresponse()
        return r.status, r.read()

    def test_backoff_delays_cap_at_4s_and_end_at_the_budget(self):
        d = engine_proxy.delays(30)
        self.assertEqual(d[:5], [0.5, 1, 2, 4, 4])
        self.assertAlmostEqual(sum(d), 30)
        self.assertEqual(engine_proxy.delays(2), [0.5, 1, 0.5])

    def test_503_twice_then_200(self):
        up = free_port()
        seen = []
        self.addCleanup(stub(up, [503, 503], seen).shutdown)
        status, body = self.get(self.start_proxy(up))
        self.assertEqual((status, body), (200, b"status 200"))
        self.assertEqual(seen, ["/base/x"] * 3)

    def test_refused_for_2s_then_up(self):
        up = free_port()
        port = self.start_proxy(up)
        seen = []
        holder = {}
        t = threading.Timer(2, lambda: holder.update(srv=stub(up, [], seen)))
        t.start()
        self.addCleanup(lambda: holder.get("srv") and holder["srv"].shutdown())
        status, body = self.get(port)
        self.assertEqual(status, 200)
        self.assertEqual(seen, ["/base/x"])

    def test_500_is_not_retried(self):
        up = free_port()
        seen = []
        self.addCleanup(stub(up, [500], seen).shutdown)
        self.assertEqual(self.get(self.start_proxy(up))[0], 500)
        self.assertEqual(len(seen), 1)

    def test_gives_up_when_the_budget_is_spent(self):
        up = free_port()
        port = self.start_proxy(up, budget="1.5")
        start = time.time()
        self.assertEqual(self.get(port)[0], 502)
        self.assertLess(time.time() - start, 5)
        seen = []
        self.addCleanup(stub(up, [503] * 10, seen).shutdown)
        self.assertEqual(self.get(port)[0], 503)
        self.assertEqual(len(seen), 3)  # tries after 0, 0.5, 1.5 s

    def test_streamed_body_passes_through(self):
        up = free_port()
        self.addCleanup(stub(up, [], []).shutdown)
        status, body = self.get(self.start_proxy(up), "/stream")
        self.assertEqual(status, 200)
        self.assertEqual(body, b"data: 0\n\ndata: 1\n\ndata: 2\n\n")


if __name__ == "__main__":
    unittest.main()
