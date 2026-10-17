"""Tests for engine/smith/meter-proxy.py, the MITM proxy that meters a Smith's Anthropic traffic.

They check that the proxy records the rate-limit headers of each reply, including the
7d_oi (Fable) utilization and reset headers, into meter.json.
"""

import http.client
import http.server
import json
import os
import socket
import socketserver
import subprocess
import sys
import tempfile
import threading
import time
import unittest

MITM_PY = os.path.join(os.path.dirname(os.path.dirname(os.path.abspath(__file__))), "meter-proxy.py")

RATELIMIT_HEADER_VALUES = {
    "anthropic-ratelimit-unified-5h-utilization": "0.08",
    "anthropic-ratelimit-unified-5h-status": "allowed",
    "anthropic-ratelimit-unified-5h-reset": "1790230200",
    "anthropic-ratelimit-unified-7d-utilization": "0.17",
    "anthropic-ratelimit-unified-7d-status": "allowed",
    "anthropic-ratelimit-unified-7d-reset": "1790719200",
    "anthropic-ratelimit-unified-status": "allowed",
}

# The Fable limit's pair, which only a claude-fable-* reply carries.
FABLE_HEADER_VALUES = {
    "anthropic-ratelimit-unified-7d_oi-utilization": "0.79",
    "anthropic-ratelimit-unified-7d_oi-reset": "1790719200",
}


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


class StubHandler(http.server.BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"

    def log_message(self, fmt, *args):
        pass

    def _handle(self):
        length = int(self.headers.get("Content-Length", "0"))
        body = self.rfile.read(length) if length else b""
        self.server.last_request_body = body
        self.server.last_request_path = self.path
        self.server.last_request_headers = dict(self.headers.items())

        behavior = self.server.behavior

        if behavior in ("echo", "fable"):
            self.send_response(200)
            extra = FABLE_HEADER_VALUES if behavior == "fable" else {}
            for k, v in {**RATELIMIT_HEADER_VALUES, **extra}.items():
                self.send_header(k, v)
            self.send_header("Content-Type", "application/octet-stream")
            self.send_header("Content-Length", str(len(body)))
            self.end_headers()
            self.wfile.write(body)
        elif behavior == "429":
            payload = b'{"error":"rate_limited"}'
            self.send_response(429)
            for k, v in RATELIMIT_HEADER_VALUES.items():
                self.send_header(k, v)
            self.send_header("Content-Length", str(len(payload)))
            self.end_headers()
            self.wfile.write(payload)
        elif behavior == "stream":
            self.send_response(200)
            for k, v in RATELIMIT_HEADER_VALUES.items():
                self.send_header(k, v)
            self.send_header("Transfer-Encoding", "chunked")
            self.end_headers()
            for chunk in self.server.stream_chunks:
                self.wfile.write(("%x\r\n" % len(chunk)).encode())
                self.wfile.write(chunk)
                self.wfile.write(b"\r\n")
                self.wfile.flush()
                self.server.chunks_sent.append(chunk)
                if self.server.chunk_delay:
                    time.sleep(self.server.chunk_delay)
            self.wfile.write(b"0\r\n\r\n")

    do_GET = _handle
    do_POST = _handle
    do_PUT = _handle


class ThreadingStubServer(socketserver.ThreadingMixIn, http.server.HTTPServer):
    daemon_threads = True
    allow_reuse_address = True


class MeterProxyTestBase(unittest.TestCase):
    behavior = "echo"

    def setUp(self):
        self.stub_port = free_port()
        self.stub = ThreadingStubServer(("127.0.0.1", self.stub_port), StubHandler)
        self.stub.behavior = self.behavior
        self.stub.last_request_body = None
        self.stub.last_request_path = None
        self.stub.last_request_headers = None
        self.stub.stream_chunks = []
        self.stub.chunks_sent = []
        self.stub.chunk_delay = 0
        self.stub_thread = threading.Thread(target=self.stub.serve_forever, daemon=True)
        self.stub_thread.start()

        self.tmpdir = tempfile.mkdtemp()
        self.meter_path = os.path.join(self.tmpdir, "meter.json")
        self.proxy_port = free_port()

        env = dict(os.environ)
        env["METER_PROXY_PORT"] = str(self.proxy_port)
        env["UPSTREAM"] = f"http://127.0.0.1:{self.stub_port}"
        env["METER_OUT"] = self.meter_path

        self.proxy_proc = subprocess.Popen(
            [sys.executable, MITM_PY], env=env,
            stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
        )
        wait_for_port(self.proxy_port)

    def tearDown(self):
        self.proxy_proc.terminate()
        try:
            self.proxy_proc.wait(timeout=5)
        except subprocess.TimeoutExpired:
            self.proxy_proc.kill()
            self.proxy_proc.wait()
        self.stub.shutdown()
        self.stub.server_close()
        self.stub_thread.join(timeout=5)

    def read_meter(self, timeout=5):
        # The proxy writes meter.json after it has finished streaming the
        # reply to the client, which is a moment after the client's own read
        # returns; poll briefly rather than racing that write.
        deadline = time.time() + timeout
        last_error = None
        while time.time() < deadline:
            try:
                with open(self.meter_path) as f:
                    return json.load(f)
            except (FileNotFoundError, json.JSONDecodeError) as exc:
                last_error = exc
                time.sleep(0.05)
        raise last_error


class TestHeaderCapture(MeterProxyTestBase):
    behavior = "echo"

    def test_headers_and_status_captured(self):
        conn = http.client.HTTPConnection("127.0.0.1", self.proxy_port, timeout=10)
        conn.request("GET", "/v1/messages", body=b"")
        resp = conn.getresponse()
        resp.read()
        self.assertEqual(resp.status, 200)
        conn.close()

        reading = self.read_meter()
        self.assertIn("observedAt", reading)
        self.assertEqual(reading["status"], 200)
        for name, value in RATELIMIT_HEADER_VALUES.items():
            self.assertEqual(reading[name], value)
        for name in FABLE_HEADER_VALUES:
            self.assertIsNone(reading[name])


class TestFableHeaderCapture(MeterProxyTestBase):
    behavior = "fable"

    def test_fable_pair_captured(self):
        conn = http.client.HTTPConnection("127.0.0.1", self.proxy_port, timeout=10)
        conn.request("POST", "/v1/messages", body=b"{}")
        resp = conn.getresponse()
        resp.read()
        conn.close()

        reading = self.read_meter()
        for name, value in {**RATELIMIT_HEADER_VALUES, **FABLE_HEADER_VALUES}.items():
            self.assertEqual(reading[name], value)

    def test_fable_pair_kept_across_a_later_reply_without_it(self):
        # A Fable run's side calls on other models must not wipe the pair (#465).
        def call():
            conn = http.client.HTTPConnection("127.0.0.1", self.proxy_port, timeout=10)
            conn.request("POST", "/v1/messages", body=b"{}")
            conn.getresponse().read()
            conn.close()

        call()
        self.read_meter()
        os.remove(self.meter_path)   # so the read below waits for the second reply's write
        self.stub.behavior = "echo"
        call()

        reading = self.read_meter()
        self.assertEqual(reading["anthropic-ratelimit-unified-status"], "allowed")
        for name, value in FABLE_HEADER_VALUES.items():
            self.assertEqual(reading[name], value, f"{name} wiped by a later non-Fable reply")


class TestByteIdenticalForwarding(MeterProxyTestBase):
    behavior = "echo"

    def test_body_forwarded_unmodified(self):
        payload = os.urandom(4096)
        conn = http.client.HTTPConnection("127.0.0.1", self.proxy_port, timeout=10)
        conn.request("POST", "/v1/messages", body=payload, headers={"X-Test": "yes"})
        resp = conn.getresponse()
        received = resp.read()
        conn.close()

        self.assertEqual(received, payload)
        self.assertEqual(self.stub.last_request_body, payload)
        self.assertEqual(self.stub.last_request_path, "/v1/messages")
        self.assertEqual(self.stub.last_request_headers.get("X-Test"), "yes")


class TestStreamingResponse(MeterProxyTestBase):
    behavior = "stream"

    def test_response_arrives_incrementally(self):
        self.stub.stream_chunks = [b"a" * 16, b"b" * 16, b"c" * 16]
        self.stub.chunk_delay = 0.5

        sock = socket.create_connection(("127.0.0.1", self.proxy_port), timeout=10)
        sock.sendall(b"GET /v1/messages HTTP/1.1\r\nHost: x\r\nConnection: close\r\n\r\n")

        f = sock.makefile("rb")
        # Read and discard the status line and headers.
        while True:
            line = f.readline()
            if line in (b"\r\n", b""):
                break

        start = time.time()
        first_chunk_size_line = f.readline()  # chunk-size in hex
        first_chunk = f.readline()
        elapsed = time.time() - start

        self.assertLess(elapsed, self.stub.chunk_delay * len(self.stub.stream_chunks))
        self.assertEqual(first_chunk.rstrip(b"\r\n"), b"a" * 16)

        f.close()
        sock.close()


class Test429Recorded(MeterProxyTestBase):
    behavior = "429"

    def test_429_recorded_in_meter(self):
        conn = http.client.HTTPConnection("127.0.0.1", self.proxy_port, timeout=10)
        conn.request("POST", "/v1/messages", body=b"{}")
        resp = conn.getresponse()
        resp.read()
        self.assertEqual(resp.status, 429)
        conn.close()

        reading = self.read_meter()
        self.assertEqual(reading["status"], 429)


if __name__ == "__main__":
    unittest.main()
