# Friction triage: from a pile of notes to tickets

How an interactive session turns the engine's friction notes
(`engine/README.md`, "Friction notes") into fixes and tickets, the way it was
done on 2026-10-05/06 (62 groups, 21 asks, 3 direct fixes). This is a
procedure for the session conversing with the user, not for a Smith. This
file is the repeatable part of the first pass (#1046, #1098; the desktop's
Analytics > Friction notes view).

The whole thing is five steps, and each one ends with the user deciding
before the next starts.

## 1. Check the grouping before trusting it

The groups are Jev's work, so spot-check them first: read the raw notes
(`GET /friction/notes`, or `read_friction_notes` on the diagnostics MCP
server) against the groups (`GET /friction/groups`) and answer two
questions.

- **Did unrelated notes land together?** Rare. Read every group with more
  than two members and skim its summaries.
- **Did one problem land in many groups?** Common. Sort the summaries by
  their key line and count how many groups each distinct problem spans. On
  2026-10-05 one problem (`check-acceptance-criteria` not executable) spanned
  16 groups, because the grouper's floor is 0.8 and once a problem has three
  near-identical groups Jev's probability is divided among them.

When groups split, read the Jev calls behind a few of the split notes
(`jev_call_id` on the note) and confirm the top choice was a sibling of the
same problem. Then **merge by hand** (`POST /friction/groups/{id}/merge`, or
Merge into… in the desktop's Friction notes view), largest group as the
receiver. Every merge is recorded as the user's correction to Jev's call, so
it feeds Jev's agreement stats; that is why merging is better than ignoring.

A grouper defect found this way (the floor, the self-feeding split) is **one
ticket**, filed after the merges, not a reason to stop.

## 2. Read every group against current `main`

With the groups honest, read all of them. For each, decide one of:

- **Already fixed on `main`.** Many are, because friction is noted at the
  time and `main` moves many times an hour. Check the code, not the memory:
  `git grep` on `origin/main` for the thing the note complains about. Mark
  the group fixed (`PATCH /friction/groups/{id}` with the ticket that did
  it, when known) and move on.
- **A real defect or a missing tool.** Something the engine, a flow, First
  mate or the desktop does wrong or cannot do.
- **Deliberate.** The limit is intended (no Gradle in the sandbox, because
  the 3 GiB cap gets it OOM-killed). Smiths still note it, which means the
  explanation they were given is not landing. The fix is words: rewrite the
  passage in `bots/smith.md` or the brief with the why and what to do
  instead. Never "leave alone": a deliberate limit that keeps producing
  notes is a docs defect.
- **Leave alone.** Nothing to do and nothing to explain. Say so and why.

Present this to the user as a few buckets ordered by what to do first, each
group as one line: id, note count, severity when high, and what is wrong.
Lead with the bucket that has the most notes for the least work.

## 3. Fix the cheap, heavy one directly

If one group holds a large share of the notes and the fix is small (a file
mode, a stale path in a docstring, one sentence in `smith.md`), do it in the
session, in a worktree, with a PR, and merge it. Do not file a ticket for a
change smaller than the ticket. Then restate the buckets with the fixed
groups removed so the user sees what is left, in the same words as before.

## 4. One ticket per fix

The user's rule on 2026-10-06, after being offered three bundle tickets:
**granular, not 5-in-1**. One ask per fix. Two groups share an ask only when
the change is in the same code: `g21 + g53` were one tool (run history and
review rounds both come from the same missing history), `g28 + g16` one tool
(`read_ticket_pr` paging and draft PRs). "Same area" is not "same code": seven
First mate tool gaps became seven asks, not one "First mate read gaps".

Why: a 5-in-1 ticket is sized, reviewed and merged as its slowest part,
blocks on its hardest part, and its closing links every group to one PR that
fixed some of them. One fix per ticket keeps each group's `fix` honest.

Each ask:

- **Names its groups in the first sentence**: `(friction group g6, 11 notes,
  high)` or `(friction groups g28, g16)`. The engine links each named group
  to the ticket it files as `fix-filed` (`engine/README.md`, "Fixed by"), the
  sync follows the ticket to `fixed`, and a new note after the merge marks
  the group `recurred`. Without the name the group stays `open` and the
  work is invisible to the view.
- **Carries the evidence** from the notes: which tickets, how many times,
  the exact message. The refiner has no access to the friction table.
- **Says what done looks like** in one or two sentences, including the test
  that proves it when it is code. "Docs only" when it is words.
- **Follows CLAUDE.md "Filing tickets"**: no `Tier:` line unless the user
  named one. The refiner sizes it.

Write the asks to a file first (`asks.json`, one string per ask), show the
user the table of buckets with the group ids, and post them in one loop
through `POST /intake` (or `submit_ask`) with `repo` set. Then report: how
many filed, which groups were skipped as already fixed, and that each intake
records `authorizedBy` so the tickets start on their own.

Alternatively, link the intake before it files: `link_friction_fix_intake`
(or `POST /friction/groups/{id}/fix-intake`) puts the group at `fix-pending`
on the intake id, which covers an ask whose text does not name the group.

## 5. What a batch of twenty does to the fleet

Twenty tickets refining at once is the normal result of this, and it has
hit limits every time. Before posting, tell the user:

- **Accounts.** All refiners run at the intake tier, on one model. Check
  `read_tier_accounts` for what is disabled or rate-limited and say where the
  queue will form.
- **CI.** CI runs on self-hosted runners only, so twenty PRs
  queue behind each other at about 20 minutes a build.
- **Closes.** Many flows closing at the same moment found the
  `CloseWake` pool deadlock (#1196). Watch the engine's pool and incidents
  for the first hour.

Then babysit: the session stays on, retries incidents, reruns flaky-only
checks, merges its own PRs, and leaves a morning summary. The friction view's
`state=unsolved` filter is the scoreboard: when it is empty of the groups
that were filed, the batch is done.

## Checklist

1. Spot-check groups; merge splits by hand; one ticket for a grouper defect.
2. Read every group against `origin/main`; bucket: fixed / defect / deliberate (explain) / leave.
3. Fix the heaviest cheap one in the session; restate what is left.
4. One ask per fix, groups named in the first sentence, evidence and done
   criteria in the body, no tier unless given; post the batch; report.
5. Warn about accounts, CI and closes; babysit until `unsolved` is clear.
