---
entity: doc/bots-delegation
---

# The Delegation Triangle — Authority Model for the Bot Fleet

> Status (2026-09-06): **design stage, not implemented.** This is a stub. The
> full write-up is the artifact **"The Delegation Triangle"** (a private
> claude.ai artifact in the user's account, created 2026-09-06) and MDR thought
> **#853**. This file captures the shape so
> it is discoverable from [README.md](README.md); flesh it out here when the
> launcher is actually built.

> First concrete slice (2026-09-09) — per-ticket secret grants as a manifest in git, process-bound
> handover (`SO_PEERCRED` proxy / inherited fd), and per-run containers.

## The problem

A fleet of ~20 Claude Code sessions (Agents Smith) needs least-privilege access
to secrets and permissions, instead of every secret being handed to every bot —
and when a Smith can spawn sub-Smiths, the authority model has to hold
recursively.

## The launcher as trust boundary

A **launcher** sits between agents and the secret store — the only thing holding
broad store access. Per-role manifest → it composes three scoped things per
session: **secrets**, **tools** (allowed Bash/MCP/paths/egress), **instructions**.
Then it materializes an ephemeral workdir (generated `.claude/settings.json`,
`.mcp.json` referencing secrets by env var), spawns `claude -p` in a sandbox
(container / UID per session, egress allowlist, tmpfs secrets), streams output,
and shreds + revokes on exit.

- The **bot→secret map is the real asset**: keep it as reviewed code, generate
  backend policies from it, use roles not per-bot rows.
- **Keep secrets out of the model's context**: prefer the MCP server holding the
  key (Claude never sees it), or a local auth-injecting proxy; plain env vars
  only as a fallback, plus a `PreToolUse` hook blocking `env` / `printenv`.

## The triangle

When agents spawn sub-agents, apply object-capability / least-authority
recursively.

- **Invariant — monotonic attenuation**: a child's authority is always a subset
  of its parent's. No node grants what it doesn't hold. Enforced by a
  `mint(parent, request)` function that checks `child ⊆ parent`, caps child TTL
  at the parent's, and carves the child's budget out of the parent's. The apex
  node (**an Operator** — one per host, each the apex of its own tree) can mint
  anything but never uses broad authority directly.
- **Two channels**: authority flows **down** (capability tree); results and
  escalation flow **up** (supervision tree, Erlang/OTP style). Upward messages
  are `result` / `error` and structured `need_authority {capability, reason,
  chain}`.
- **Escalation** ("asked to do X, lack the permission"), preference order:
  1. **Fail up with a typed error** — each parent decides: run the sub-step
     itself, spawn a narrow sibling with exactly that permission, propagate up,
     or deny. *(default)*
  2. **Bubble to the first ancestor that can grant**, mint a downscoped
     capability back down — faster, but the leaf ends up holding the permission.
  3. **Handback** — the ancestor runs the permission-requiring step itself and
     passes only the result down; the leaf never holds the permission. *(use for
     sensitive permissions)*

## Failure modes to design against

- **Confused deputy** — the upward channel is an attack surface; treat child
  output as untrusted.
- **Fan-out** — make depth, child-count, and `$budget` attenuable too, not just
  capabilities.
- **Revocation** — bearer tokens don't revoke; use short TTL + a revocation
  check, or a launcher session registry that cascades kills.
- **Prompt injection** on untrusted input pulled into a session.

## Storage backend fit

| Backend | Best when |
|---|---|
| Cloud secrets manager + per-bot IAM | already running in that cloud |
| Vault | dynamic / short-lived creds + deep audit needed |
| SOPS + age | GitOps, no runtime service wanted |
| 1Password service account, vault-per-bot | static secrets, simple (fair) |
| Roll-your-own DB | avoid |

## Prior art to steal from

Macaroons · Biscuit · UCAN · OAuth token exchange (RFC 8693) downscoping · AWS
session policies / GCP credential access boundaries · the confused-deputy
problem (Hardy, 1988) · Erlang/OTP supervision trees.
