# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project

`frakworks` is the Agent Army: an autonomous bot fleet (Agent Smith / the
engine / the Oracle's services) that works tickets (engine work items) in this
repo and in target repos. The `engine/` (Operaton process engine) runs on
Kubernetes (`deploy/k8s/`, since 2026-10-06) and starts every Smith as a
sandboxed Job in namespace `frakworks-smith`; the desktop app (first mate) and
interactive sessions run locally and reach it over the network. The
Operator (`dispatch/`, the previous runner on the Smith VMs) was removed on
2026-09-24 (its manual `bots/operator.md` on 2026-09-27; git history has both).
How Smiths run now: `engine/README.md`, "Smith runs". Folder map: `README.md`. Engine docs:
`engine/README.md` (overview and index) and `engine/docs/`. The repo came over
from a private one as-is; expect stray references to its paths, hosts and
proposals until they are fixed here.

## Commands

- Deploy the engine (Kubernetes): `deploy/k8s/README.md` (a Kustomize base; the site overlay is Argo CD's). A Smith reaches the engine through `$ENGINE_URL`.
- Engine build + tests, locally (Docker): `docker compose build engine`
- The user's engine, desktop or interactive: either a local Docker Compose stack or a Kubernetes-hosted one is supported. Check config to learn which before giving a URL or port — never assume `http://localhost:8090`: look at `$ENGINE_URL` if set, or the desktop app's configured engine URL (Config > frakworks). For the Compose case (the system-in-a-box model, not how the fleet runs): `bash scripts/local-up.sh` (how: `docs/local-stack.md`); the engine is then on host port 8090.

## MDR was removed

The Macrodata Refiner (the second-brain MCP server, `macrodata-refiner/`) was
removed from this repo on 2026-09-27. It lives on in a separate, private
repo. Nothing here talks to it: Smiths write their
narrative into the issue thread (`bots/smith.md` §5), and an `mdr` MCP server
attached to an interactive session is the user's own, not part of the fleet.

## Filing tickets: a stated tier binds; otherwise the refiner picks

When the user asks to file work, use `submit_ask` on the engine's `frakworks`
MCP server; a refiner's question or a proposal is decided on the HITLP
surface (the desktop's decision card), not through the engine. That is the
only way to file: the `/ticket` and `/file-bot-task` skills were
removed on 2026-09-26, the `bots/tickets-mcp` server on 2026-09-27, and the
`/intake` skill on 2026-10-10 (it posted with `curl` and its own token, a
leftover from before the MCP server). Never post to `POST /intake` yourself.
Pass the ask as the user's own words, `repo`, and a `context` summary of the
conversation. The tool answers the ticket's number at once (`ticket`, with
`repo`): reply with one line naming it as a link,
`[#<ticket>](frakworks://ticket/<owner>/<name>/<ticket>)`, saying the refiner
will fill it in (the number changes only if it files in another repo), and
stop, with no intake id, status call or polling. Do not stop to ask for a tier. Three cases:

- **The user names a tier** — a number 1–10 from the engine's tier map (`GET /tiers`;
  the desktop's Config > frakworks > Tiers), or a
  model/effort pair that maps to one ("fable low" = tier 8) — it binds: pass
  it as `submit_ask`'s `tier`, which puts `Tier: <n> (<model> <effort>) —
  prescribed by the user; do not re-judge` on the ask's first line, so the
  refining Smith files at that tier.
- **The user says "you pick"** (or asks for a recommendation and says go):
  choose one, state it in the same line, and file at it.
- **No tier is mentioned**: file with no `Tier:` line. The refining Smith
  chooses by the rubric in `bots/model-choice.md` and cites it in the
  ticket's Context, as the engine's brief requires. Never invent a tier
  yourself in this case, and leave the tier out of the reply entirely: no
  "no tier given, the refiner picks" line.

The intake run itself stays at the template's tier. Decided by the user on
2026-09-25, relaxing the earlier rule that the user named the tier every
time. This binds interactive sessions only; a Smith releasing discovered work
follows `bots/smith.md`.

## Tickets are links in an interactive session

When working in an interactive Claude Code session with the user, any reference
to a ticket is a link that opens it in the desktop app, never a bare `#872`:

```
[#872](frakworks://ticket/codefrak-oss/frakworks/872)
```

- The form is `frakworks://ticket/<owner>/<name>/<n>`: the repo's full name and
  the issue number, nothing after it (`observability/desktop/README.md`, "Links
  that open a ticket in the app"). The link text is `#<n>`, or `<name>#<n>` when
  the ticket is in another repo than the one being discussed.
- A ticket is an issue the engine works, in a target repo. A pull request is not
  one, and the app answers `no such ticket` for its number: link a PR to GitHub
  (`[PR #870](https://github.com/codefrak-oss/frakworks/pull/870)`).
- This is for what the user reads in the session. Anything written to GitHub (an
  issue, a comment, a PR body, a commit message) keeps plain `#<n>`: GitHub
  strips the scheme. A Smith (`SMITH_ID` set) has no user and is not bound by this.

## Auto-Commit & Push

Whenever an agent/AI updates any `README.md` or `CLAUDE.md` file in this repo,
**immediately commit and push** those changes. Do not batch them up or wait
for the user to ask.

## Interactive sessions work in a worktree

Check `SMITH_ID` first. If it is set, you are a Smith in a sandbox: skip this
section, your clone is already yours. If it is unset, you are an interactive
session and other sessions may be running in this checkout: call
`EnterWorktree` (or start with `claude --worktree <name>`) before your first
edit, work and commit on that branch, merge or push it when done, then
`ExitWorktree`. Leave the main checkout on `main`; touch it only to
fast-forward.

## Interactive sessions read current code

This binds an interactive Claude Code session conversing with the user. Check
`SMITH_ID` first: if it is set, you are a Smith, skip this section, your clone
is your run's and you work from it as it is.

Before answering how the code behaves, or diagnosing the engine or the desktop
app from source, run `git fetch` and compare the checkout with `origin/main`
(a stale checkout once had a session describe behavior `main` no longer had). Smiths
merge to `main` many times an hour, so do this every time, not once per
session. The running engine and the installed desktop app are built from
`main`: they behave as `origin/main` says, not as a stale checkout does.

- **Behind:** fast-forward (`git merge --ff-only origin/main`), or read from
  `origin/main` directly (`git show origin/main:<path>`, `git grep <pattern>
  origin/main`).
- **A fast-forward is not possible** (local changes in the way, or not on
  `main`): ask the user before doing anything else to the checkout, and read
  from `origin/main` meanwhile.

## Modeler edits ride along

In an interactive session, if a `.bpmn` file changes that you did not change
(the user editing in the Camunda Modeler, on your worktree's copy), commit and push it with your work, after
the engine build and tests pass, and tell the user in one line what changed and in
which commit. If it conflicts with your work (say, the Modeler saved a stale
copy over your edits), keep both: keep their change and re-apply yours rather
than picking one, then say what you merged. Check `git status` before
committing engine work.

## Say "the user", not a name

Instructions in this repo (`CLAUDE.md`, skills, READMEs addressed to Claude)
refer to the person in the session as "the user". When the user dictates a
rule that names them, write it with "the user" instead, and do the same when
you touch an existing instruction that uses a name. Names that are identifiers
stay as they are: repo names, logins, assignees, and literal strings other
code or prompts match on.

## A setting that moves leaves nothing behind

When a setting moves from a property, an environment variable or a config file
into the database (or from any one home to another), the old home is deleted in
the same change: the property and its `@Value` read, the `.env` and compose
lines, the tests that set it, and the README text. Never keep the old path as a
default or a fallback, and never document one as intended.

- **Existing values are carried over, not fallen back to.** A Flyway migration
  writes them where they are known in the repo. A value only the deployment
  knows (the gitignored `.env`) is the interactive session's to move: set it
  through the engine's API, then delete the `.env` line.
- **An ask that moves a setting says so.** Write into the ask that the old path
  is deleted in the same ticket and how existing values are carried over, so the
  refiner puts both in the acceptance criteria.
- **A Smith that cannot finish the removal says so.** It still deletes
  everything in the repo, and names what is left for the deployment in its
  closing comment. It does not keep a fallback to cover the gap.

The same holds for a plain removal: nothing optional, nothing "harmless when
unset".

## Everything has a repo context: there is no default repo

frakworks works many target repos, so every call, tool and setting that is
about a repo names that repo. Never add a "default repo", in a property,
an environment variable, a config file or a database row, and never let a
missing `repo` fall back to one: a call without it is refused, naming the
target repos. The one thing that stands in for a named repo is the caller's
own context: a Smith's ask is filed in its run's repo. When filing from an
interactive session, pass `repo` to `submit_ask` (this checkout's own unless
the user names another).

## Security

Never commit secrets (passwords, API keys, tokens, credentials) to this
repository. Secrets live in 1Password, in the engine's sealed store (AI
account credentials, `engine/README.md` "AI accounts") and in the gitignored
`.env`, never in git. Keycloak (`servers/`) and the secrets broker
(`bots/secrets-mcp/`) were removed on 2026-09-27: nothing on the engine's
path used them.
