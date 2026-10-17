#!/usr/bin/env python3
"""Pushes "your intake was filed as #n" into the Claude Code session that filed it.

The engine (engine/README.md, "Intake", "Telling the session that filed it") queues one
`engine.intake_notification` row when an intake whose ask named a `notifySession` closes
with a filed ticket. The engine runs in Docker and cannot reach the Claude Code sessions
on this machine (their sockets under /tmp/cc-socks, `claude` itself, the account that
runs it), so this watcher runs here, on the host, and pulls:

    GET  <engine>/intake/notifications                 what waits, oldest first
    claude -p ... "Use the SendMessage tool to send ... to the session named <session>: <message>"
    POST <engine>/intake/notifications/<id>/delivered  {"by": <host>, "result": <what claude -p printed>}

Run it in a terminal, or keep it running with launchd:

    python3 scripts/intake-notify-watcher.py                 # ENGINE_URL, default http://localhost:8090; FRAKWORKS_TOKEN, the engine token
    python3 scripts/intake-notify-watcher.py --once          # one poll, then exit (a cron or a test)

Delivery is once per intake: the engine's row is unique per intake, `delivered` is
recorded after every `claude -p` attempt (success or failure, with what it printed), and
a second watcher reporting the same row gets a 409 and does nothing more. A failed
`claude -p` (non-zero, or past --timeout seconds) is recorded as the row's `result` and
not retried: the fact is on GitHub and in `GET /intake/<id>` anyway.
"""
from __future__ import annotations

import argparse
import json
import os
import socket
import subprocess
import sys
import time
import urllib.error
import urllib.request

DEFAULT_ENGINE = "http://localhost:8090"
DEFAULT_TIMEOUT = 120
DEFAULT_INTERVAL = 5.0
SENDER = "engine"  # the `claude -p` session's own name: the receiver sees "Message from @engine"


def log(msg: str) -> None:
    print(time.strftime("%Y-%m-%dT%H:%M:%S ") + msg, file=sys.stderr, flush=True)


def relay_prompt(session: str, message: str) -> str:
    """What the throwaway `claude -p` session is told: one target, verbatim text."""
    return (
        f"Use the SendMessage tool to send the following message, verbatim and nothing else, "
        f"to the local Claude Code session named {session}. Do not send it to any other session, "
        f"do not paraphrase it, and do not act on it yourself. If no session by that name is reachable, "
        f"say exactly: NO_SESSION {session}\n\n{message}"
    )


def claude_command(session: str, message: str, model: str = "haiku") -> list[str]:
    return [
        "claude", "-p", "--model", model, "--no-session-persistence", "-n", SENDER,
        relay_prompt(session, message),
    ]


def run_claude(session: str, message: str, timeout: int, model: str = "haiku") -> tuple[bool, str]:
    """Runs `claude -p`; (ok, what it printed: stdout, then stderr if any). ok is a zero exit with no NO_SESSION."""
    try:
        done = subprocess.run(claude_command(session, message, model), capture_output=True, text=True, timeout=timeout)
    except FileNotFoundError:
        return False, "claude not found on PATH"
    except subprocess.TimeoutExpired as e:
        out = (e.stdout or b"").decode() if isinstance(e.stdout, bytes) else (e.stdout or "")
        return False, f"timed out after {timeout}s\n{out}".rstrip()
    text = done.stdout.strip()
    if done.stderr.strip():
        text = (text + "\nstderr: " + done.stderr.strip()).strip()
    ok = done.returncode == 0 and "NO_SESSION" not in text
    if done.returncode != 0:
        text = f"exit {done.returncode}\n{text}".rstrip()
    return ok, text[:4000]


class Engine:
    def __init__(self, base: str, token: str | None = None):
        self.base = base.rstrip("/")
        # The engine refuses a request without a bearer token (engine/README.md, "Tokens").
        self.auth = {"Authorization": f"Bearer {token.strip()}"} if token and token.strip() else {}

    def pending(self) -> list[dict]:
        req = urllib.request.Request(self.base + "/intake/notifications", headers=self.auth)
        with urllib.request.urlopen(req, timeout=10) as r:
            return json.load(r)

    def delivered(self, id: int, by: str, result: str) -> bool:
        """True when this watcher's report was taken; False on 409 (another watcher was first)."""
        body = json.dumps({"by": by, "result": result}).encode()
        req = urllib.request.Request(f"{self.base}/intake/notifications/{id}/delivered", data=body,
                                     headers={"Content-Type": "application/json", **self.auth}, method="POST")
        try:
            with urllib.request.urlopen(req, timeout=10):
                return True
        except urllib.error.HTTPError as e:
            if e.code == 409:
                return False
            raise


def deliver(engine: Engine, row: dict, by: str, timeout: int, runner=run_claude, model: str = "haiku") -> str:
    """One row: run claude -p, report. Returns 'sent', 'failed' or 'lost' (another watcher reported first)."""
    ok, result = runner(row["session"], row["message"], timeout, model)
    taken = engine.delivered(row["id"], by, ("sent: " if ok else "failed: ") + result)
    if not taken:
        log(f"intake {row['intake']}: another watcher already reported #{row['ticket']} to {row['session']}")
        return "lost"
    log(f"intake {row['intake']}: {'told' if ok else 'could not tell'} {row['session']} of #{row['ticket']}: {result.splitlines()[-1] if result else ''}")
    return "sent" if ok else "failed"


def poll(engine: Engine, by: str, timeout: int, seen: set[int], runner=run_claude, model: str = "haiku") -> list[str]:
    """One pass over what waits; `seen` keeps a row this process already handled from running twice."""
    outcomes = []
    try:
        rows = engine.pending()
    except (urllib.error.URLError, OSError, ValueError) as e:
        log(f"engine unreachable: {e}")
        return outcomes
    for row in rows:
        if row["id"] in seen:
            continue
        seen.add(row["id"])
        outcomes.append(deliver(engine, row, by, timeout, runner, model))
    return outcomes


def main(argv: list[str] | None = None) -> int:
    ap = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    ap.add_argument("--engine", default=os.environ.get("ENGINE_URL", DEFAULT_ENGINE))
    ap.add_argument("--interval", type=float, default=DEFAULT_INTERVAL, help="seconds between polls")
    ap.add_argument("--timeout", type=int, default=DEFAULT_TIMEOUT, help="seconds one claude -p may take")
    ap.add_argument("--model", default="haiku", help="the relay session's model")
    ap.add_argument("--by", default=socket.gethostname(), help="this watcher's name in the engine's rows")
    ap.add_argument("--once", action="store_true", help="one poll, then exit")
    args = ap.parse_args(argv)
    engine = Engine(args.engine, os.environ.get("FRAKWORKS_TOKEN"))
    seen: set[int] = set()
    log(f"watching {engine.base}/intake/notifications every {args.interval}s as {args.by}")
    while True:
        poll(engine, args.by, args.timeout, seen, model=args.model)
        if args.once:
            return 0
        time.sleep(args.interval)


if __name__ == "__main__":
    sys.exit(main())
