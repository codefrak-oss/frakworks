#!/usr/bin/env python3
"""Ride-out proxy between a Smith and the engine.

Only the lease holder takes traffic (engine/README.md, "The engine in the cluster"),
so for a few seconds of a handover the engine's Service has no ready endpoint:
a connection is refused or answered 503. Claude Code's MCP client cannot be
taught to wait, so run-smith.sh hands it (and the Smith's own $ENGINE_URL) this
proxy on 127.0.0.1, which forwards every request verbatim to UPSTREAM and,
while the upstream refuses the connection or answers 503, retries it with a
0.5, 1, 2, 4 s (then 4 s on) backoff for up to BUDGET seconds. Once the
upstream answers anything else the reply streams back as it arrives (SSE and
streamable HTTP pass through). When the budget runs out the last 503 is passed
on, or a 502 for a refused connection. The connection to the engine stays the
pod's own, so the engine still knows the run by the sandbox's address.

Env:
  ENGINE_PROXY_PORT    local port to bind on 127.0.0.1   default 8789
  UPSTREAM             the real engine URL                required
  ENGINE_PROXY_BUDGET  seconds to keep retrying           default 30
"""
import http.client
import http.server
import os
import socketserver
import ssl
import sys
import time
from urllib.parse import urlsplit

TIMEOUT_SECONDS = 660
BACKOFF = [0.5, 1, 2, 4]

# Hop-by-hop headers (RFC 7230 6.1), as meter-proxy.py drops them; the body's
# framing headers pass through with the raw bytes they describe.
HOP_BY_HOP = {"connection", "keep-alive", "proxy-authenticate", "proxy-authorization", "te", "trailer", "upgrade"}


def delays(budget):
    """The waits between attempts: the backoff, then 4 s on, until `budget` seconds are spent."""
    out, spent, i = [], 0.0, 0
    while True:
        d = BACKOFF[min(i, len(BACKOFF) - 1)]
        if spent + d > budget:
            d = budget - spent
            if d <= 0:
                return out
        out.append(d)
        spent += d
        i += 1


def make_handler(upstream, budget, sleep=time.sleep):
    parsed = urlsplit(upstream)
    is_https = parsed.scheme == "https"
    host = parsed.hostname
    port = parsed.port or (443 if is_https else 80)
    prefix = parsed.path.rstrip("/")

    def connect():
        if is_https:
            return http.client.HTTPSConnection(host, port, timeout=TIMEOUT_SECONDS, context=ssl.create_default_context())
        return http.client.HTTPConnection(host, port, timeout=TIMEOUT_SECONDS)

    class EngineProxyHandler(http.server.BaseHTTPRequestHandler):
        protocol_version = "HTTP/1.1"

        def log_message(self, fmt, *args):
            sys.stderr.write("engine-proxy: %s - %s\n" % (self.address_string(), fmt % args))

        def _attempt(self, body, headers):
            """One try: (conn, response), or (None, None) when the connection was refused."""
            conn = connect()
            try:
                conn.request(self.command, prefix + self.path, body=body, headers=headers)
                return conn, conn.getresponse()
            except (ConnectionRefusedError, ConnectionResetError, http.client.RemoteDisconnected, OSError) as exc:
                conn.close()
                self.log_message("upstream: %s", exc)
                return None, None

        def _forward(self):
            length = self.headers.get("Content-Length")
            body = self.rfile.read(int(length)) if length is not None else None
            headers = {k: v for k, v in self.headers.items() if k.lower() not in HOP_BY_HOP and k.lower() != "host"}
            headers["Host"] = parsed.netloc

            waits = iter(delays(budget))
            while True:
                conn, resp = self._attempt(body, headers)
                if resp is not None and resp.status != 503:
                    break
                wait = next(waits, None)
                if wait is None:
                    break
                if resp is not None:
                    resp.read()
                    conn.close()
                sleep(wait)
            if resp is None:
                self.send_error(502, "engine unreachable")
                return
            self._stream(conn, resp)

        def _stream(self, conn, resp):
            self.send_response(resp.status)
            for k, v in resp.getheaders():
                if k.lower() not in HOP_BY_HOP:
                    self.send_header(k, v)
            self.send_header("Connection", "close")
            self.end_headers()
            if self.command == "HEAD":
                conn.close()
                return
            # The raw socket after the headers, read a piece at a time (as
            # meter-proxy.py does) so an event stream is never held back.
            raw = resp.fp
            if conn.sock is not None:
                conn.sock.settimeout(None)
            content_length = resp.getheader("Content-Length")
            chunked = (resp.getheader("Transfer-Encoding") or "").strip().lower() == "chunked"
            remaining = int(content_length) if content_length is not None and not chunked else None
            tail = b""
            try:
                while remaining is None or remaining > 0:
                    piece = raw.read1(8192 if remaining is None else min(8192, remaining))
                    if not piece:
                        break
                    self.wfile.write(piece)
                    self.wfile.flush()
                    if remaining is not None:
                        remaining -= len(piece)
                    elif chunked:
                        tail = (tail + piece)[-5:]
                        if tail.endswith(b"0\r\n\r\n"):
                            break
            except (BrokenPipeError, ConnectionResetError):
                pass
            conn.close()

        do_GET = do_POST = do_PUT = do_DELETE = do_PATCH = do_HEAD = do_OPTIONS = _forward

    return EngineProxyHandler


class ThreadingHTTPServer(socketserver.ThreadingMixIn, http.server.HTTPServer):
    daemon_threads = True
    allow_reuse_address = True


def main():
    port = int(os.environ.get("ENGINE_PROXY_PORT", "8789"))
    upstream = os.environ["UPSTREAM"]
    budget = float(os.environ.get("ENGINE_PROXY_BUDGET", "30"))
    ThreadingHTTPServer(("127.0.0.1", port), make_handler(upstream, budget)).serve_forever()


if __name__ == "__main__":
    main()
