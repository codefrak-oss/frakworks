#!/usr/bin/env python3
"""Passive usage-meter proxy for a Smith sandbox.

Sits in front of Claude Code's own traffic (ANTHROPIC_BASE_URL points at this
process) and forwards every request verbatim to UPSTREAM, streaming the reply
back as it arrives. It never originates a request of its own: it only
observes what Claude Code sends and records the anthropic-ratelimit-unified-*
headers from each reply to /work/out/meter.json, latest reading wins; the
7d_oi pair (FABLE_HEADERS) is the last one any reply in this run carried.

Env:
  METER_PROXY_PORT  local port to bind on 127.0.0.1   default 8788
  UPSTREAM          origin to forward to               default https://api.anthropic.com
  METER_OUT         path to write the meter reading to  default /work/out/meter.json
"""
import http.client
import http.server
import json
import os
import socketserver
import ssl
import sys
import threading
import time
from urllib.parse import urlsplit

TIMEOUT_SECONDS = 660  # >= 10 minutes, matches the upstream reply budget

RATELIMIT_HEADERS = [
    "anthropic-ratelimit-unified-5h-utilization",
    "anthropic-ratelimit-unified-5h-status",
    "anthropic-ratelimit-unified-5h-reset",
    "anthropic-ratelimit-unified-7d-utilization",
    "anthropic-ratelimit-unified-7d-status",
    "anthropic-ratelimit-unified-7d-reset",
    "anthropic-ratelimit-unified-status",
    # The Fable limit, on claude-fable-* replies only (absent on other models).
    "anthropic-ratelimit-unified-7d_oi-utilization",
    "anthropic-ratelimit-unified-7d_oi-reset",
]

# The Fable pair only comes on a claude-fable-* reply, and a Fable run also
# makes calls on other models: a reply without it keeps the last pair seen.
FABLE_HEADERS = [h for h in RATELIMIT_HEADERS if "-7d_oi-" in h]
_last_fable = {}
_meter_lock = threading.Lock()

# Hop-by-hop headers (RFC 7230 6.1) that describe one specific connection,
# not the message: dropped both ways since the client<->proxy and
# proxy<->upstream connections are independent. Content-Length and
# Transfer-Encoding are NOT hop-by-hop here — the body bytes they describe
# pass through raw and unmodified, so their framing headers must too.
HOP_BY_HOP = {
    "connection",
    "keep-alive",
    "proxy-authenticate",
    "proxy-authorization",
    "te",
    "trailer",
    "upgrade",
}


def _write_meter(out_path, status, headers):
    # Keyed by the full header name (not stripped) so the account-wide
    # "anthropic-ratelimit-unified-status" never collides with this reply's
    # own HTTP "status".
    reading = {
        "observedAt": time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime()),
        "status": status,
    }
    for name in RATELIMIT_HEADERS:
        reading[name] = headers.get(name)
    with _meter_lock:
        if any(reading[name] is not None for name in FABLE_HEADERS):
            _last_fable.update({name: reading[name] for name in FABLE_HEADERS})
        else:
            reading.update(_last_fable)
        _write_json(out_path, reading)


def _write_json(out_path, reading):
    tmp_path = f"{out_path}.tmp.{os.getpid()}.{time.time_ns()}"
    with open(tmp_path, "w") as f:
        json.dump(reading, f)
        f.flush()
        os.fsync(f.fileno())
    os.replace(tmp_path, out_path)


def make_handler(upstream, out_path):
    parsed = urlsplit(upstream)
    is_https = parsed.scheme == "https"
    upstream_host = parsed.hostname
    upstream_port = parsed.port or (443 if is_https else 80)

    class MeterProxyHandler(http.server.BaseHTTPRequestHandler):
        protocol_version = "HTTP/1.1"

        def log_message(self, fmt, *args):
            sys.stderr.write("meter-proxy: %s - %s\n" % (self.address_string(), fmt % args))

        def _forward(self):
            length = self.headers.get("Content-Length")
            body = self.rfile.read(int(length)) if length is not None else None

            if is_https:
                conn = http.client.HTTPSConnection(
                    upstream_host, upstream_port,
                    timeout=TIMEOUT_SECONDS,
                    context=ssl.create_default_context(),
                )
            else:
                conn = http.client.HTTPConnection(
                    upstream_host, upstream_port, timeout=TIMEOUT_SECONDS,
                )

            forward_headers = [
                (k, v) for k, v in self.headers.items()
                if k.lower() not in HOP_BY_HOP and k.lower() != "host"
            ]
            forward_headers.append(("Host", upstream_host))

            try:
                conn.request(self.command, self.path, body=body, headers=dict(forward_headers))
                upstream_resp = conn.getresponse()
            except Exception as exc:
                self.send_error(502, f"upstream error: {exc}")
                return

            response_headers = [
                (k, v) for k, v in upstream_resp.getheaders()
                if k.lower() not in HOP_BY_HOP
            ]
            content_length = upstream_resp.getheader("Content-Length")
            is_chunked = (upstream_resp.getheader("Transfer-Encoding") or "").strip().lower() == "chunked"

            self.send_response(upstream_resp.status)
            for k, v in response_headers:
                self.send_header(k, v)
            self.send_header("Connection", "close")
            self.end_headers()

            # Bypass HTTPResponse's own chunked-decoding: reading through it
            # with read(N) tries to accumulate N bytes across chunk
            # boundaries, blocking on the *next* chunk even when this one
            # already arrived — the opposite of streaming. response.fp is the
            # buffered socket positioned right after the headers; fp.read1()
            # makes at most one underlying read and returns whatever is
            # already there, so each piece is forwarded as soon as it lands.
            raw = upstream_resp.fp
            try:
                if is_chunked:
                    tail = b""
                    while True:
                        piece = raw.read1(8192)
                        if not piece:
                            break
                        self.wfile.write(piece)
                        self.wfile.flush()
                        tail = (tail + piece)[-5:]
                        if tail.endswith(b"0\r\n\r\n"):
                            break
                elif content_length is not None:
                    remaining = int(content_length)
                    while remaining > 0:
                        piece = raw.read1(min(8192, remaining))
                        if not piece:
                            break
                        self.wfile.write(piece)
                        self.wfile.flush()
                        remaining -= len(piece)
                else:
                    while True:
                        piece = raw.read1(8192)
                        if not piece:
                            break
                        self.wfile.write(piece)
                        self.wfile.flush()
            except BrokenPipeError:
                pass

            _write_meter(out_path, upstream_resp.status, upstream_resp.headers)
            conn.close()

        def do_GET(self):
            self._forward()

        def do_POST(self):
            self._forward()

        def do_PUT(self):
            self._forward()

        def do_DELETE(self):
            self._forward()

        def do_PATCH(self):
            self._forward()

        def do_HEAD(self):
            self._forward()

        def do_OPTIONS(self):
            self._forward()

    return MeterProxyHandler


class ThreadingHTTPServer(socketserver.ThreadingMixIn, http.server.HTTPServer):
    daemon_threads = True
    allow_reuse_address = True


def main():
    port = int(os.environ.get("METER_PROXY_PORT", "8788"))
    upstream = os.environ.get("UPSTREAM", "https://api.anthropic.com")
    out_path = os.environ.get("METER_OUT", "/work/out/meter.json")

    handler = make_handler(upstream, out_path)
    server = ThreadingHTTPServer(("127.0.0.1", port), handler)
    server.serve_forever()


if __name__ == "__main__":
    main()
