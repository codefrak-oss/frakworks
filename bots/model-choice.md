# Choosing `Model:` and `Effort:` for a ticket

**Reading this is mandatory before writing a `Model:` line.** That applies to
the user's sessions using `/file-bot-task`, and to Smiths releasing discovered work
under `bots/smith.md` §"Discovered work and sub-tickets". The Operator does not
enforce this; the cost of getting it wrong is every other ticket that would have
run in the same window.

Written 2026-09-15 after two `opus` Smiths (#549, #550) exhausted the secondary
account's 5-hour window twice in one evening. The first version of this guidance
said "default to sonnet, raise effort before model". That was a guess from the
burn rate, and Anthropic's own measurements say it is backwards. This is the
corrected version, with the evidence it rests on.

A ticket names a **tier** (1-10), not a model: what each tier runs, its model
and effort, is the engine's tier map, kept in its database and edited in the
desktop app's Config > frakworks > Tiers (`engine/README.md`, "Tier
resolution"; `GET /tiers`). A refining Smith gets the map in its brief and its
prompt. It was the checked-in `bots/tiers.toml` until 2026-10-05 (#1099), moved
so the user can change a tier without a commit or a redeploy. The rules below
say which model and effort a ticket needs; pick the tier whose row gives it.

## The constraint

The fleet's Claude accounts run on plan allowances: a 5-hour window and a
weekly window per account, metered in tokens weighted by model. The Operator's
spawn gate (`USAGE_MAX_SESSION_PCT`, 85% by default) refuses to start a Smith
once a window is nearly spent, and a Smith that is mid-run when the window hits
100% is reaped. Its branch survives and the next Smith resumes from it, but the
window does not come back. So a model choice is not "how much does this ticket
cost"; it is "how many tickets fit in the window".

What the 2026-09-14 evening looked like, all on `opus` at `high`:

| Run | Turns | Time | Notional cost | Outcome |
|---|---|---|---|---|
| #549 first | 66 | 9 min | ~$4.50 | reaped at 100% |
| #550 first | 44 | 9 min | ~$3.76 | reaped at 100% |
| #549 resume | 26 | 3 min | ~$1.20 | success |
| #550 resume | 88 | 14 min | ~$7.31 | success |

Two such runs in parallel took the window from 0% to 100% in about ten minutes,
then after the reset from 0% to 87% in half an hour, at which point the gate
closed the queue for the rest of the window.

## What is actually measured

1. **A bigger model at lower effort can be the cheaper way to a given quality.**
   In the Claude Opus 4.5 announcement Anthropic reported that Opus 4.5 at
   *medium* effort matched Claude Sonnet 4.5's best SWE-bench Verified score
   while using **76% fewer output tokens**, and at its highest effort beat
   Sonnet 4.5 by 4.3 points with **48% fewer tokens**.
   ([anthropic.com/news/claude-opus-4-5](https://www.anthropic.com/news/claude-opus-4-5))
   That pair is a generation old and nobody has published the same comparison
   for Sonnet 5 versus Opus 5; the platform docs say Sonnet 5 at medium is
   "comparable to Claude Sonnet 4.6 at high", so the gap has narrowed but
   there is no evidence it has closed.

2. **Effort governs turns and tool calls, not only thinking.** The platform
   docs: effort "affects all tokens in the response", and lower effort means
   Claude will "combine multiple operations into fewer tool calls", "make fewer
   tool calls" and "proceed directly to action without preamble".
   ([platform.claude.com/docs/en/build-with-claude/effort](https://platform.claude.com/docs/en/build-with-claude/effort))
   An agentic run's allowance is dominated by its turn count. The four runs
   above were 66, 44, 26 and 88 turns.

3. **`high` is the API default, and was this fleet's until 2026-09-15.**
   "Setting effort to high produces exactly the same behavior as omitting the
   effort parameter entirely" (same page). `DEFAULT_EFFORT` in
   `dispatch/config.sh` was `high` too, so writing `Effort: high` on a ticket
   changed nothing. Both #549 and #550 carried it in the belief that it was an
   upgrade. The fleet default is now `low` (below), so a blank ticket no
   longer runs in the tail by accident.

4. **Anthropic's per-model advice for Opus 5** is to "use low and medium
   liberally as your primary control for token cost and response time wherever
   your evals show quality holds", and for Opus 4.7/4.8 that `low` is "best for
   short, scoped tasks — pair low with explicit checklists if your task has
   multiple sections" (same page). A bot-task with acceptance criteria is
   exactly a short, scoped task with an explicit checklist.

5. **The allowance weights models, but only loosely documented.** The help
   center says "Opus costs several times more per turn than Sonnet, and Sonnet
   more than Haiku"
   ([support.claude.com](https://support.claude.com/en/articles/14552983-models-usage-and-limits-in-claude-code));
   API list price for the current pair is 1.67× per token. If Opus needs a
   quarter of the output tokens for the same result, it wins on allowance even
   at a several-fold weight. It loses when it is run at high effort for many
   turns, which is the only way this fleet has ever run it.

## The shape of the curve

Effort is not a straight line from "worse" to "better". Every published
measurement of score against reasoning tokens has the same shape: a steep
climb from the bottom levels, a knee somewhere around the middle, and a long
flat tail where each step up costs multiples for a point or less.

| Source | Model | Levels | Score | Tokens / compute |
|---|---|---|---|---|
| [Artificial Analysis](https://artificialanalysis.ai/articles/gpt-5-benchmarks-and-analysis) | GPT-5 | minimal → low → medium → high | 44 → 64 → 67 → 68 (Intelligence Index) | 3.5M → … → 82M tokens across the suite; high is **23×** minimal for +24 points, and medium → high is **+1 point** |
| [Cognition, FrontierCode v1.1](https://codingfleet.com/blog/frontiercode-v11-main-leaderboard-2026/) (methodology only partly public; cross-harness numbers "directional") | Claude Opus 5 | medium vs high | 53.4% main / 63.6% extended at medium; high "no meaningful gain" | ~1.5× baseline at medium, ~3× at high |
| same | Claude Fable 5 at xhigh vs Opus 5 at medium | | 53.5% vs 53.4% main | Fable at xhigh ≈ 2.3× the compute of Opus at medium |
| [Anthropic, Opus 4.5 launch](https://www.anthropic.com/news/claude-opus-4-5) | Opus 4.5 vs Sonnet 4.5 | medium (Opus) vs best (Sonnet) | equal on SWE-bench Verified | Opus uses 76% fewer output tokens |
| [Anthropic, Opus 4.7 launch](https://www.anthropic.com/news/claude-opus-4-7) | Opus 4.7 | low → max | one chart, "score on an internal agentic coding evaluation as a function of token usage at each effort level" — no numbers published | |

Read across the rows: the knee is at **medium** for every model with a
published curve. Anthropic's own reason for adding `xhigh` was "finer control
over the tradeoff between reasoning and latency on hard problems", which is a
tail-of-the-curve tool by its own description. So "does this ticket need high?"
is really "is this ticket in the tail?", and most are not.

Two honest caveats. The FrontierCode numbers come from a third party with an
only partly published method, and none of these curves were measured on this
repo's tickets. They agree with each other and with Anthropic, which is why
they are here, but the table in §"What nobody has measured yet" is the one
that would settle it for this fleet.

## The rules

**The center of the distribution is `opus` at `Effort: medium`.** Most tickets
live there. `low` for a tightly scoped ticket with a clear checklist.
`sonnet` (effort blank) for mechanical work: ports, renames, test scaffolding,
documentation sweeps, anything where reasoning is not the bottleneck.

**Blank effort means `low`.** `DEFAULT_EFFORT` in `dispatch/config.sh` is
`low` since 2026-09-15 (it was `high`, the API's own default, when the runs
above happened). The default is the floor, not a recommendation: a ticket
that needs `medium` writes `Effort: medium`. `high` is a fine level to run —
it is what tonight's runs were — but write it only when you mean the tail
deliberately, and write `xhigh` or `max` when you mean more.
On a `runner:codex` ticket blank effort means `low` instead (#561): Codex
effort is unmeasured spend, so the floor is the default there.

**`high` and above are the tail, spent on purpose.** A design or research
ticket, a security review, a run that came back thin at the default (the
requeue path in `bots/README.md` §"Review and rework").

**How many runs go at once, and on which account, is the Operator's call,
not the author's.** The usage gate picks the account per ticket from the live
meters, and #562 adds a per-account cap on concurrent heavy runs. A ticket
author chooses `Model:` and `Effort:` for the work in front of them and
nothing else; do not serialise tickets, pick a host, or hold work back to
manage a window — that is the Operator's job, and it has the numbers.

**Scale concurrency to the account, not to a rule of thumb.** The two fleet
accounts are different sizes. The pair of runs that emptied the secondary's
5-hour window in ten minutes would have cost the primary plan a fraction of
its window. The Operator enforces this itself: `MAX_HEAVY_PER_ACCOUNT` caps
concurrent heavy (`opus`/`fable`, or `xhigh`/`max`) Smiths per account on each
host, and a heavy ticket waits rather than spawn over it. Authors do not need
to stagger heavy tickets by hand; see `dispatch/README.md` §"A per-account cap
on concurrent heavy Smiths" (#562).

**A docs-first repo's ask is a chore at a low tier.** When a refine brief says
`docsFirst: true` (the repo's `engine.target_repo.docs_first`, engine/README.md
§"Docs path"), file the ticket as kind `chore` at one of the map's low tiers,
with no design run, unless the ask clearly needs code or design: say so in the
ticket's Context. A prescribed `Tier:` still binds. The push decides the rest:
a change that touches only docs skips the security review, and any other change
gets the full pipeline whatever the repo's setting.

**Review tickets follow the ladder** and are not covered here; see
`bots/README.md` §"Review and rework".

## What nobody has measured yet

Allowance per *finished ticket* across these combinations, on this fleet's
work, is the number that matters and it does not exist anywhere. Every run
already posts turns and notional cost to its issue (`📊 Run stats`), so the
table can be built from the issue history once a few weeks of `opus`/`medium`
and `sonnet` runs exist. Whoever revises this doc should start there rather
than from another burn-rate anecdote.

## Further reading

- [Claude Code effort level and model selection](https://claude.com/blog/claude-model-and-effort-level-in-claude-code)
  — Anthropic's qualitative guidance: Sonnet for routine edits, Opus for
  subtle bugs and unfamiliar domains; use the default effort unless the run
  skipped files or abandoned multi-step work.
- [Effort — Claude Platform Docs](https://platform.claude.com/docs/en/build-with-claude/effort)
  — the per-model effort table and the tool-use behaviour by level.
