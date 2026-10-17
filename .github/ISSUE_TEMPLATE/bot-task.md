---
name: Bot task
about: Work for an autonomous bot (Agent Smith) to pick up
title: ""
labels: []
assignees: ""
---

<!--
Filed by the engine (an intake's refiner, `engine/README.md`) this issue is
created with `bot-task` + a required priority (p1..p5) and is queued at once.
(The ticket MCP's `file_bot_task` was retired 2026-09-27.) Filed by hand
with `gh`, leave `bot-task` off and it lands in human triage; a human adds
`bot-task` + a priority to release it. A Smith filing discovered work stays
within bots/smith.md §"Discovered work": scope no wider than its own, never
p0, a Tier: plus Model:/Effort: it has judged, "Blocked by" set for open dependencies. See
bots/README.md for the protocol.
-->

## Goal

<!-- One or two sentences: what should be true when this is done. -->

## Acceptance criteria

- [ ]
- [ ]

## Allowed scope

<!-- Be explicit. A Smith stays inside this and fails up rather than expanding it. -->

- Paths / files:
- Services / hosts:
- Spend / budget:
- Tier: <!-- REQUIRED — integer 1..10. The Operator treats this as authoritative when resolving the runner-specific model and effort. -->
- Model: <!-- REQUIRED — one of: sonnet / opus / haiku / fable (or a full model id). No default: an Operator skips and blocks a ticket with no valid Model: line. READ bots/model-choice.md BEFORE FILLING THIS IN — mandatory. Default: opus with Effort: medium. -->

- Effort: <!-- REQUIRED for tickets filed through the MCP; low / medium / high / xhigh / max. Retained alongside the authoritative Tier: field. See bots/model-choice.md. -->

- Turns: <!-- optional; integer, default from Operator config (MAX_TURNS in dispatch/config.sh / dispatch/op/config.py). Raise it for a ticket you expect to run long — clamped to the host's MAX_TURNS_CEILING (400 by default) if you ask for more. -->
- Budget: <!-- optional; dollars, default from Operator config (MAX_BUDGET_USD). A notional list-price guardrail, not a real charge. Clamped to the host's MAX_BUDGET_USD_CEILING (40.00 by default) if you ask for more. -->

- Not before: <!-- optional; leave blank (or delete the line) for "eligible now", which is what every ticket without it means. ISO 8601 with an EXPLICIT ZONE, seconds optional: 2026-09-20T14:00Z, 2026-09-20T14:00:00Z, 2026-09-20T09:00-05:00. No Operator claims this ticket until the instant has passed on its own clock; a value with no zone designator is refused with `blocked`. Nothing else about the ticket changes, and there is no scheduler — the queue simply skips it until then. -->

- Review model: <!-- optional; leave blank unless you have an opinion. Blank means the Operator reviews this ticket's PR one rung above Model: on MODEL_LADDER (haiku → sonnet → opus, saturating at opus); a `fable` ticket is reviewed by `fable` at one effort step up instead, since `fable` is on no rung. Set it to force a specific reviewer model — an explicit value is used verbatim and is never stepped up. See "Review and rework" in bots/README.md. -->
- May open a PR: yes / no
- May push to `main`: no

## Stop and ask if…

<!-- Conditions under which the Smith must stop and hand back to a human. -->

-

## Context

<!-- Where this surfaced: linking issue, commit, conversation.

If this must not start until another issue is done, say so with GitHub's
"Blocked by" relationship in the sidebar — NOT with a line here. The queue reads
the relationship and nothing else, and it unblocks itself when the blocker
closes. See "Dependencies" in bots/README.md. -->
