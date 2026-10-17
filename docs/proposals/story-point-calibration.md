# Story-point calibration in target repos

Ticket #100120. The rubric's reference tickets were all frakworks issues, so a
refiner in another target repo compared asks with descriptions of code it had
never seen. Four pitches were put to the user; the user picked B + C.

| Pitch | How it calibrates | Cost | Bootstrap (no tickets of its own) |
|---|---|---|---|
| A. Periodic rerun | A monthly custom job per repo reruns the exchange-rate study (`docs/research/story-point-exchange-rate-2026-10.md`) on the repo's closed, pointed tickets | one Smith run per repo per month | **None**: nothing to study until ~20 closed tickets; early tickets use frakworks anchors only |
| **B. Point by shape** | The refiner reads the code the ask touches and matches places touched and unknowns with the anchor table; switches to the repo's own tickets at ~10 | no runs, a few refine turns | Covered: this is the bootstrap rule |
| **C. Seed from history** | One run reads the repo's git history and merged PRs and writes per-value reference changes to `docs/research/story-point-calibration-<owner>-<name>.md` | one run per repo, rerun on request | Covered from day one if the repo has history |
| D. Engine drift check | The engine compares points with Smith cost per repo; the desktop flags drift | engine and desktop code, no runs | **None** until data accrues, like A |

Built: B and C, as docs only (`bots/story-points.md`, "Calibration in target
repos"; the refiner bullet in `bots/smith.md`). C needs no new job kind,
secret, host or permission: the seed is an ordinary ask filed in frakworks.
A and D stay open as later options, once repos have closed tickets to measure.
Points already recorded never change.
