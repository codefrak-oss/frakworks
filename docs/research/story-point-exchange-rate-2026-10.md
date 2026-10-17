# What a story point costs the fleet, 2026-10-06

Retro-pointing of every completed ticket against what its Smith runs spent,
to calibrate the story-point rubric the intake refiner started using with
#1273. The interactive page, with charts, the full table and every ticket's
points: [`story-point-exchange-rate-2026-10.html`](story-point-exchange-rate-2026-10.html)
(open it in a browser; it is self-contained).

## Data

- **Tickets:** 297 issues in `codefrak-oss/frakworks` closed as completed,
  labelled `chore`, `bug` or `feature`, with Smith runs in the engine (188
  chores, 49 bugs, 60 features). Proposals, research and intake issues are
  left out.
- **Points:** ten agents pointed 30 tickets each from the ticket's Goal,
  Acceptance criteria and Allowed scope against `bots/story-points.md`, blind
  to cost, PRs and history. The scale was opened above 13 (21, 34, 55) for
  features only. A spec that only pointed at a design doc was sized from it.
- **Cost and working time:** `GET /ticket/{owner}/{repo}/{n}/timeline`
  totals, refine runs excluded.
- **Tokens:** the engine stores none (the timeline's `tokens` is always
  null). Each Smith run's final `result` event in VictoriaLogs carries the
  run's usage; summed per ticket, it covers 265 of the 297 (the logs reach
  back to 2026-09-26) and matches the timeline's cost to the cent for most.

## Findings

| Points | Tickets | Median cost | Middle half | $ per point | Median working min | Median tokens | Median output tokens |
|---|---|---|---|---|---|---|---|
| 1 | 14 | $0.53 | $0.43–1.11 | $0.53 | 1.4 | 0.9M | 4k |
| 2 | 45 | $1.04 | $0.78–1.33 | $0.52 | 3.9 | 1.7M | 8k |
| 3 | 59 | $1.18 | $0.85–1.95 | $0.39 | 2.9 | 1.2M | 9k |
| 5 | 95 | $2.75 | $1.86–4.53 | $0.55 | 6.2 | 3.6M | 28k |
| 8 | 55 | $7.35 | $5.35–11.14 | $0.92 | 16.8 | 9.6M | 72k |
| 13 | 22 | $11.04 | $8.20–18.23 | $0.85 | 28.9 | 11.5M | 124k |

- **Points track spend.** Rank correlation between points and cost is 0.81
  over all tickets (chores 0.78, bugs 0.59, features 0.38). Bugs vary with how
  hard the cause is to find, which a spec does not show; a split feature's own
  runs are only its design step.
- **About $0.50 a point from 1 to 5, about $0.90 at 8 and 13.** Cost grows
  faster than Fibonacci above 5: an 8 costs 2.7 times a 5.
- **2 and 3 cost the same** ($1.04 and $1.18). 1s really are half a 2, so the
  soft spot is 3, not 2: either 3 should mean more, or refiners call some 2s a 3.
- **Big features were guessed low.** In 8 of 10 split features the children add up
  to more points than the parent got (#507 came in under, #1261 even):

  | Feature | Parent's points | Children's points summed | Total cost |
  |---|---|---|---|
  | #1212 custom jobs | 34 | 55 (4 children) | $94 |
  | #955 SSE | 21 | 37 (4) | $119 |
  | #1093 friction signatures | 21 | 29 (3) | $97 |
  | #509 multi-repo | 21 | 36 (6) | $37 |
  | #507 | 21 | 16 (2) | $12 |
  | #994 doctor to bugs | 13 | 43 (6) | $47 |
  | #973 | 13 | 21 (2) | $51 |
  | #751 | 13 | 21 (2) | $23 |
  | #1261 | 13 | 13 (2) | $26 |
  | #724 | 5 | 8 (2) | $7 |

  This supports an open scale above 13 for features, with children pointed
  at the split so their sum checks the parent.
- **Tokens are mostly cache.** 94% of tokens are cache reads, so the total
  mostly measures context growth. Output tokens are the cleaner size signal.

## Limits

One model pointed, after the fact, from the final ticket text rather than the
original ask, so specs refined to fit the work may have helped it. Treat the
rates as a calibration signal, not ground truth. Rerun once the refiner has
pointed a few dozen tickets live, and compare its points with these.
