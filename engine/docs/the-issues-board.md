# The issues board

Part of the engine docs: [index](../README.md), [engine/README.md](../../README.md).

`GET /issues` is what a UI lists first (decided with the user, 2026-09-24:
the desktop app moves onto the engine, and the engine proxies GitHub *reads*
for its target repos rather than the app carrying its own `gh`). It is every
open issue of every target repo, read live from GitHub with each repo's token
(never stored), newest update first, with the engine's own state joined on
each:

| Field | From |
|---|---|
| `repo`, `number`, `title`, `author`, `labels`, `url`, `createdAt`, `updatedAt` | GitHub, as listed |
| `kind` | the one kind label (`chore`, `intake`); null with none or several |
| `authorized` | whether the `authorized` label is on it now |
| `flow` | the latest approval the scan acted on (`engine.handled_issue`): `flow`, `outcome` (started, refused, ignored), `detail`; for a start, its `instance`, `startedAt`, `endedAt`, `ended`, the `step` it waits at (the innermost called process's activity, so a chore shows its Smith run's step), the `stage` it waits at (the id of the outermost flow's own activity, such as `call-do-work`, `call-review-pr` or `task-merge-pr`, so the Smith run a chore is in can be told apart; null once `ended`), `prUrl` / `ticketUrl` once set, `incident` (`{id, activityId, type, message, processInstanceId, createdAt}`, the first open incident in it or a process it called; null for none, and once `ended`), and for the progress bar: `stageStartedAt` (when the flow entered `stage`: its activity's start in the history, or, while it still waits on its `asyncBefore` job, when the step before it ended), `stageEstimateMs` (how long `stage` should take, see "Stage estimates" below the table; null for a stage that waits on someone), `stageEstimateBasis` and `stageEstimateRuns` (where that estimate came from, for the bar's tooltip; null with it) and `stageRound` (how many times the flow has entered `stage`, so a rework round is 2 or more); all three null once `ended`. For a `proposal` flow, `pitch` is the pitch page (`docs/proposals/<slug>.html`) its stored propose result names, read from the flow's variables with no GitHub call; null for any other flow or a draft with no pitch, so a UI can mark a proposal that has one and fetch it from `GET /ticket/{owner}/{repo}/{n}/pitch`. When there is no `handled_issue` row (an intake started by `POST /intake`), the `intake` process instance whose `issueUrl` variable matches the issue's URL instead, with `outcome`/`detail` null since there is no approval row |
| `pendingQuestion` | the `engine.intake_question` row waiting on the user, if any |
| `blockedBy`, `deferred` | GitHub's "blocked by" issues as `[{number, state}]` (empty for none, or when GitHub could not say, which also lands in `failures`), and whether its approval waits on one (`engine.deferred_issue`); a waiting issue's `flow` has outcome `deferred`, detail `waiting on #n`. See "Blocked by" |
| `children`, `parent` | a split feature's parts as `[{index, title, kind, state: pending \| open \| merged \| satisfied \| dropped, number, url, prNumber, points}]` from its flow's `split` variable (`points` a filed part's story points from `engine.handled_issue`, null while pending or unpointed), and on each filed child `{number, url}` of its parent. See "Split features" |
| `askedBy` | who asked for the ticket: the latest `engine.handled_issue` row's `asked_by` (the trailer's `by <login>`, else the issue's author, "Intake", `by`); null when the engine has not seen the issue, or its row is from before the column and the scan has not filled it (yet, or ever: a row on an issue closed before V47 stays null) |
| `points` | its story points: the latest `engine.handled_issue` row's `points` that has them (the trailer's `points`, "Intake", `points`; `HandledIssues.points`'s rule, read with the board's one `handled_issue` read); null when the engine has not seen the issue, or it was filed unpointed |
| `intake` | the intake that filed it, `{id, number, url}`: the `intake` process instance (its id) whose `repo` is this repo and whose `ticket` variable is this issue, with that instance's `issue` and `issueUrl` (the ask item). The reverse of "An earlier intake"'s lookup (`IntakeRefs.filedBy`), one history read per repo; with several, the most recently started wins. Null for an issue no intake filed. The desktop's Explorer shows it as the ticket's provenance |

A repo GitHub could not be read for lands in `failures` and the rest are
still listed. Nothing is cached or written: the board is a read of GitHub and
the engine's tables each time, so a UI polling it every few seconds is
spending GitHub's rate limit, one listing call per target repo per poll, and
one GraphQL call for blockers per 50 of its open issues.

A closed issue leaves the board on a `ticket` `closed` frame: the webhook's for
a close made on GitHub, and `IssueCloseFrames`'s for every close the engine
makes itself (a ticket ended done or cancelled, `Tickets.end`), so a repo whose webhooks do not reach the
engine (no webhook secret) loses its closed tickets too (#1762).

```
curl -H "Authorization: Bearer $FRAKWORKS_TOKEN" localhost:8090/issues
```

**Stage estimates.** `flow.stageEstimateMs` (`board/StageEstimates.kt`) is read once per
`GET /issues` from `engine.smith_run_stats`, successful runs (exit 0) with a
`mode` only, and computed in Kotlin. A Smith stage maps to its role
(`call-design` design, `call-reproduce` reproduce, `call-do-work` work,
`call-security-review` security, `call-review-pr` review, `call-accept`
accept, `call-verify` verify, `call-replan` replan; an intake's
`call-refine-ask` refine, a proposal's `call-draft` propose and `call-review`
review). Its estimate is the median
duration of that role's runs in the same root flow kind at the same tier;
with none, of the role in that kind; with none, of the role; with none, a
default per role (design 20 min, reproduce 15, work 30, the rest 10). A stage
that is not a Smith run takes its default only: `call-ci-check` 10 min,
`task-rebase-pr` 2, any other `task-*` or `start-*` 1. A stage that waits on
someone (`user-task-*`, `event-intake-authorized`, `event-child-closed`,
`event-merge-conflict`) has
none. In a rework round (`stageRound` > 1) a stage with an estimate takes this
ticket's own first round of it instead, from the flow's history. Tier is the
flow's `tier` variable. No LLM is asked.

Above the tier level sits a **scope level** (#975): the median of the role's
runs in the same kind at the same tier on tickets of the same *scope bucket*,
used only when that bucket has at least 5 runs (`MIN_SCOPE_RUNS`); with fewer
the chain goes on to the tier median as before. The signal is the size of the
ticket's Allowed scope: how many distinct paths and globs its granting lines
name (`AllowedScope.grantCount`, the same reader the workflow-path check uses,
so a `May not touch:` line and what follows "except" count for nothing), read
from the flow's `approvedTicket` variable with no GitHub call and stored per
run in `smith_run_stats.scope_paths`. The buckets are `small` (up to 3),
`medium` (4 to 8) and `large` (9 and up), cut in Kotlin (`StageEstimates.bucket`)
so they can be retuned without a migration; a run or a flow with no ticket
falls in none. Why this signal: the Allowed scope is the refiner's own
statement of how much of the repo the work touches, every ticket has one in
the same form, and the engine already parses it; the body's length tracks the
writer's style more than the work, the count of acceptance criteria mostly
repeats the scope, a split child shows up as a smaller scope anyway, and
per-repo buckets are too thin today. Why 5 runs: the median of fewer is mostly
the one odd run in it, and the coarser levels keep their one-run rule, so the
board's numbers change only where a bucket has history.

Each estimate says where it came from (`StageEstimates.Estimate`): its
`basis` is `scope`, `tier`, `kind`, `role`, `default` or `prior-round`, and
its `runs` how many runs the median rests on (0 for the last two). The board
sends them as `stageEstimateBasis`/`stageEstimateRuns`; `GET /intake/{id}`
sends the same estimate on the open step of the root flow as `estimateMs`,
`estimateBasis` and `estimateRuns`, from one snapshot per request, with the
stage's own rework rule (the root's first ended round of the activity), so
the detail's Steps bar and the board's bar agree. A step that waits on
someone, has no start or has ended, any step of a called process, and every
step of an ended flow carry none.

The desktop app (`observability/desktop`) reads GitHub only through the
engine: the board, one ticket's
detail (`GET /ticket/{owner}/{repo}/{n}`; each of its `prs` carries `mergeCommitSha`, the merge commit once merged, which the detail's main-build strip matches against a `main` `images` run's `headSha` in "CI runs of a repo"), its comments as REST objects for
the answer flow (`GET /ticket/{owner}/{repo}/{n}/comments` and
`GET /ticket/{owner}/{repo}/comments/{id}`), a proposal's pitch page
(`GET /ticket/{owner}/{repo}/{n}/pitch`), what needs a person
(`GET /needs`). Every read uses the token of the repo
it is about, through `IssueDetails`
(`RestGitHub`); tickets themselves are read from the store; the comment reads need no scope beyond the others. None of
them is cached either: a ticket's detail one per 100 comments, its REST comments one call
per 100 and one comment one call, a pitch one or two contents reads
(`GET /repos/{r}/contents/{path}?ref=`, raw) and, with no kept record, one
GraphQL call, or `POST /markdown` for a draft from before pitches,
and `GET /needs` one listing call per target repo (the `authorize` rows and
titles); `GET /version` costs nothing after startup. The done list
(`GET /issues?state=closed`) reads no GitHub at all: it is the engine's done and
cancelled work items.

## Builds in progress

`GET /builds/active` (unauthenticated, read-only, no GitHub call) lists the
builds the engine waits on: every flow parked at `reusable-ci-check`'s
`receive-ci-check` is one engine check of one pushed branch. It answers
`{ builds, timeout }`: `builds` oldest first, each `repo`, `issue`, `check`,
`headSha`, `since` (when the wait began) and `processInstanceId`; `timeout`
is `engine.ci-check.timeout` (ISO-8601), the longest a wait lasts before its
incident. The desktop app's Analytics > Build gate strip draws it
(`observability/desktop/README.md`, "Build gate").

## CI runs of a repo

`GET /repos/{owner}/{name}/ci/runs?limit=<n>&workflow=<w>&branch=<b>`
(unauthenticated, read-only) answers a target repo's newest GitHub Actions
workflow runs, newest first, with the repo's token: one call to GitHub's
workflow-runs API plus one read of the repo's `default_branch` (taken as
`main` when that read fails), no caching. It answers `{ repo, runs, summary }`
as First mate's `read_ci_runs` does, from the same code (`ci/CiRuns.kt`):
each run `id, workflow, branch, event, status, conclusion, createdAt,
startedAt, completedAt, durationSeconds, queuedSeconds, url, area, trigger,
headSha, runNumber`.

- `area` is the workflow file's name without its extension (`engine` for
  `engine.yml`, `desktop-release` for `desktop-release.yml`), `null` when
  GitHub names no file. Nothing is particular to frakworks' workflows.
- `trigger` is `pr` for a `pull_request` (or `pull_request_target`) run,
  `main` for a `push` to the default branch, `branch` for a `push` to any
  other branch, and otherwise the event name (`workflow_dispatch`,
  `release`, `schedule`...).

`limit` (1 to 100) is required; a missing or other limit, and a repo that is
not a target repo (the message names the target repos), are refused with 400;
a GitHub failure answers 502 with its message. The desktop app's Analytics >
Builds view draws it (`observability/desktop/README.md`, "Analytics > Builds").

## What needs you

`GET /needs` lists everything waiting on a person, built from the engine's
own state; each row is `repo`, `number`, `title`, `kind`, `wanted` (what is
wanted, in a line), `since`, `actionId` (what to act on), `question` and `for`
(both null on every kind below). A flow's question, acceptance or decision is
no row: it is filed as a HITLP task on the HITLP server ("The HITLP
requester"), decided there, and `HitlpAnswers` continues the flow from the
decision record. The engine serves no HITLP since #2484 and is no HITLP
surface since #100013: the desktop and the phone read the server's open tasks
there directly ("No surface in the engine"), so no row is a HITLP task. First mate's `read_needs` serves the
same rows. `GET /needs?for=<login>` keeps the rows whose `for` is that login
(ignoring case) or null: what waits on that person, the desktop's "Needs <login>".
Agents as askers are deferred (#1261): `for` is a plain login string:

| `kind` | From | `since` | `actionId` |
|---|---|---|---|
| `incident` | an open Operaton incident, one per root cause (a propagated incident whose root cause is no longer open stands for it); `repo`/`number` from the root flow's `repo` and `issue` (a doctor run's `doctorRepo` and `doctorIssue`, its ticket's; an intake's `issueUrl` or `ticketUrl`), null when it has none | the incident | the incident id, for `POST /camunda/incidents/{id}/retry` |
| `insufficient-permissions` | an open issue in a target repo an intake filed unlabeled for human triage (`File unlabeled ticket for human triage`, "Intake"): its engine trailer ends with `· triage` and it has no kind label (`intake`, `chore`, `feature`, `bug`, `proposal`, `research`, `incident`). `wanted` is the refiner's blocker from its `## Needs human triage` section, else the reason's first line. Labelling it with a kind drops the row (one kind label and no `authorized` makes it an `authorize` row); so does closing it | created | the issue's URL, to triage |
| `authorize` | an open issue in a target repo with exactly one kind label (`intake`, `chore`, `feature`, `bug`, `proposal`), no `authorized` label, no `engine.handled_issue` row and no `engine.deferred_issue` row, and not the issue of a running `intake` instance (found by its `issueUrl`) whose `authorizedBy` is set, as `POST /intake` sets for a submitter who may authorize (decided with the user, 2026-09-25) | created | the issue's URL, to label `authorized` |
| `reauthorize` | an `engine.deferred_issue` row whose blockers are all closed and at least one closed without a merge ("Blocked by") | last checked | the issue's URL, to authorize again; from the desktop, First mate's `reauthorize` ("re-authorize #N") |
| `sighting` | some target repo has `doctorBugs` on and none has `engineDefects` ("Doctor"): one row, `repo` and `number` null, `wanted` "mark a repo for engine defects" | none | `engineDefects`, to set with `PATCH /repos/{owner}/{name}` |
| `sighting` | a doctor bug still `open` or `needs-repro` after its 4th stuck ticket put `needs-review` on it, in a repo with `doctorBugs` on ("Doctor"): one row per bug, `wanted` "review the doctor's bug: 4 stuck tickets hit it and it is still open" | when `needs-review` was added | the issue's URL |

Titles come from the board's listing, else the approved ticket's or the
deferred snapshot's, else `""`. A repo GitHub cannot list loses only its
`authorize` and `insufficient-permissions` rows and its titles; the rest are still listed.

## Database pool

The engine's connections come from one Hikari pool (`application.properties`):
`maximum-pool-size=30`, `connection-timeout=5000` (5 s) and
`leak-detection-threshold=30000` (30 s). A request that cannot get a connection
within 5 s answers `503` with `Retry-After: 5` (`PoolExhausted`) instead of
hanging until the client's 30 s timeout; a connection held past 30 s is logged
at warn with the stack that took it. An Operaton command holds one connection
from its first SQL to its commit, so a step must never wait, inside it, on
something that needs a second one: a `REQUIRES_NEW` transaction, or a read on
another thread it waits for. Such work runs after the commit on a thread of its
own (`EngineEvents.afterCommitApart`, as `CloseWake` does); 11 intakes filing
at once emptied the pool that way on 2026-10-07 (#1650).
