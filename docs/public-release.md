# Public-release checklist

What to exclude or rework when frakworks is recreated as a fresh public repo. The docs
slice is done in this repo (#2557); code and config references (the old private repo,
deployment hosts, LAN IP ranges) are tracked by their own tickets.

## Personal tooling to exclude or rework

- `ios/fastlane/`: the TestFlight lane is set up for one developer's Apple account and Mac.
  Exclude it, or rework it to read the team, app id and signing from the environment.

## Docs

- `docs/proposals/`: emptied on 2026-10-15; proposals start over from scratch.
- `CLAUDE.md`, `AGENTS.md`: the fleet's rules stay; check no personal names or private
  hosts came back in since this change.
