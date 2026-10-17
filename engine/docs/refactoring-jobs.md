# Refactoring jobs

Part of the engine docs: [index](../README.md), [engine/README.md](../../README.md).

A **refactoring job** (#1352, part 1 of #1351) is a system-owned job the
engine keeps for a target repo: on its schedule it files a **refactor-scan
ticket** (#1542), whose own flow has a no-code Smith inspect the repo's code
against the repo's **refactoring values** and files at most a few small,
reasoned tickets, never refiling work that is still open. Package
`refactor/`; the job's run is the `refactor` branch of `bpmn/job.bpmn` (it only
files the scan), the scan is `bpmn/refactor-scan.bpmn`.

**The config** is one row of `engine.refactor_config` per repo (V55), read and
written by `GET`/`PUT /repos/{owner}/{name}/refactor` (the endpoint table,
above; `RefactorConfigController`, `RefactorConfigs`). A repo with no row
answers the defaults: off, `schedule` 604800 (weekly), the shipped values,
`maxTickets` 2, `maxLines` 300, `approval` `human`, `tier` null. `values` is prose, the
text the Smith reads: the shipped default is
[`bots/refactor-values.md`](../../bots/refactor-values.md) (copied into the jar
as `refactor/refactor-values.md` by `processResources`; the Dockerfile copies
it from the root context), stored as NULL so a later edit of the file reaches
every repo that never changed it (`valuesDefault: true`); `values: null` in a
`PUT` returns to it. `PUT` is guarded as `PATCH /repos/{owner}/{name}` is: a
Smith sandbox is refused by its source address (enabling would start work in
its own repo), any other caller may, and the token's login is recorded as
`updatedBy`. Each write puts a `repo` frame `changed` with field `refactor` on
the stream.

**The tier.** `tier` (1 to 10, V57) is the tier the refactor-scan ticket is
filed at (its trailer's `· tier <n>`) and its Smith runs at: the start resolves
it through the tier map (`TierTable`, read at each start, so a map edit reaches
the next scan) into the run's `model` and `effort`, which
`call-callable-smith` passes on. **A null tier files no scan**: the job's run
ends `failed: refactoring on <repo> has no tier: set one with PUT
/repos/<repo>/refactor`, so no scan runs outside the tier map (before #1542 a
null tier ran the inspection untiered at `claude-sonnet-5` medium). It is not
the filed proposals' tier: those are filed with none, as below. The desktop's
Config > frakworks > Refactoring has a tier picker (tiers 1-10 with their model
and effort from `GET /tiers`; "default" there is null, which files no scan).

**The one system job.** Enabling inserts one `engine.job` row for the repo:
owner `system`, kind `refactor`, name `refactor <repo>`, config `{}`, signal
`every <schedule>`, `created_by` the login that enabled it; its id is the
config's `job_id`. Disabling deletes the row (a run already started finishes
as `failed: refactoring is off for <repo>`, below); a schedule change while
enabled rewrites the job's signal and `next_run_at`. The job is a job like
any other: the ticker starts it when due and `POST /jobs/{id}/execute` (or
the diagnostics server's `run_job`) starts a run now, its runs never overlap,
`GET /jobs` lists it with `owner: system`. What it may not be is registered or
deleted by hand: `POST /jobs` with kind `refactor` is 400, `DELETE /jobs/{id}`
(and `delete_job`) on it is 409 naming the repo and the `PUT` that disables
it. User jobs are untouched: every row from before V55 reads `owner: user`.

**A job run** (`bpmn/job.bpmn`, `gateway-kind` → `refactor`) is one step,
`task-file-refactor-scan` (`fileRefactorScan`, `FileRefactorScanDelegate`). It
reads the config row (no row, disabled, or its `job_id` not this job: `outcome`
`failed: refactoring is off for <repo>`; no tier: `failed: refactoring on
<repo> has no tier ...`; nothing filed either way), lists the repo's open issues
once, and while one labelled `refactor-scan` is open files nothing (`summary`
`filed nothing: refactor-scan #<n> is still open`), so a short schedule never
piles up scans. Otherwise it files `Refactoring scan of <repo>` through
`TicketFiler`, label `refactor-scan`, trailer `<!-- engine: refactor job <id> ·
tier <n> · p3 · by <login> -->`, whose body is the instructions,
`RefactorPrompt.build`: the values verbatim under `## The repo's refactoring
values`, the rule that each proposal is one PR-sized change of at most
`maxLines` changed lines (a verbatim move, as in a file split, counts its
moved lines once plus the new file's KDoc, package line and imports; the filed
ticket states the same rule) with a stable `key` (a slug of the module or path it
touches, the same for the same work on any run), at most `maxTickets`
proposals, the trailer keys (`· key <k>`, `TicketFiler.keyOf`) of the open
issues labelled `refactor` under `## Do not propose these keys`, and the exact
`summary` shape: `{"proposals": [{"key", "title", "body", "lines"}]}`, each
`body` with Goal, Acceptance criteria (the first always `at most <maxLines>
changed lines`), Allowed scope and a Context saying how the change moves the
code toward the values. It starts the ticket at once through `AuthorizedStart`
(label event 0, kind `refactor-scan`, the config's tier, authorized by the
enabling login, detail `refactor job <id>`) and labels it `authorized` as the
visible record, as approval `none` does below; `summary` is `filed
refactor-scan #<n>` and the `job` frames carry `owner: system`. A
`refactor-scan` ticket is only ever started this way: the issue scan does not
take the kind from a label.

**The scan** (`bpmn/refactor-scan.bpmn`, started on `refactor-scan filed` by
`ProcessFlowStarter` like any ticket, with `model`/`effort`/`tier` from the
ticket's tier): `task-prepare-scan` (`prepareRefactorScan`) sets `prompt` to
the ticket's body less the engine trailer (as bytes, `Brief.textValue`: with
the values quoted it runs past the 4000 characters a String variable holds,
#1238), or, with refactoring off meanwhile, `outcome` `failed: refactoring is
off for <repo>` and `refactorSkipped` true, straight to the close with no
sandbox. Then `call-callable-smith` (mode `job`, no issue on the run: it
changes no file and cuts no bundle), then `task-file-refactor-tickets`
(`fileRefactorTickets`, `FileRefactorTicketsDelegate`): a `failed` outcome
passes through with nothing filed; else `result.summary.proposals` (anything
else: `failed: the run's summary has no proposals array`) is filtered,
dropping a malformed proposal, a key already open (re-read now, not at the
filing of the scan) or already filed this scan, and `lines` over `maxLines`
(the config's now), and the first `maxTickets` left are filed; `summary` says
what happened (`filed #a, #b; skipped 1 open, 1 oversize, 1 over the cap`) and
`outcome` is `done`. Last, `task-close-scan` (`closeRefactorScan`) comments the
summary (or the failed outcome) on the scan ticket and closes it, completed
after a `done` scan, not planned otherwise. No answer page and no ack, unlike
`research`. The doctor watches it as any ticket flow. A scan whose Smith
proposed nothing at all says so: `summary` `found nothing`, but only when its
`summary` names what it read (`inspected`, a non-empty array of paths); an empty
scan without one ends `failed: the run proposed nothing and named nothing it
inspected` (#2283: scans that wrote the empty fallback unread in 15 s looked
like real ones). The scan's Smith is a job run with no ticket, so
`read_transcript` on the scan ticket finds nothing.

**Run history** (#1653). Every job run, of any kind, leaves a row in
`engine.job_run` (V59, `JobRuns`, keyed by the `job` instance; no foreign key,
so a disabled repo's history stays): `noteJobStarted` inserts it (job id,
repo, kind, `started_at`), `noteJobEnded` closes it with `ended_at`, `outcome`,
`summary` and `status`: `failed` for an outcome `failed...` (refactoring off,
no tier), `nothing` for a summary `filed nothing...` (a scan still open),
else `done`. A refactor run adds its scan: `fileRefactorScan` sets
`scan_issue`; the scan's `fileRefactorTickets` writes `filed` (the ticket
numbers), `skipped` (counts by reason: `open`, `oversize`, `overCap`,
`malformed`) and `found_nothing` (no proposals at all) onto the newest run of
the repo with that `scan_issue`, and `closeRefactorScan` its `scan_outcome`,
`scan_summary` and `scan_ended_at` (a failed scan: the outcome, nothing
filed). `GET /jobs/{id}/runs` and the diagnostics server's `read_job_runs`
answer it newest first; the desktop's Analytics > Refactoring page lists
a repo's recent runs, each with its outcome, the filed tickets as links and a
`did nothing: <reason>` line when nothing was filed.

**Runs started before #1542.** A `job` instance on the old refactor branch
(`prepareRefactor`, the old `fileRefactorTickets` step) left running across the
deploy cannot finish: `prepareRefactor` is gone and the filing step now needs
a scan ticket. Cancel it (a run lasts minutes, one per repo); the next tick
files a scan.

**The filed ticket** goes through `TicketFiler` as a `feature`: labels
`feature`, the mode label, and `refactor`; priority `p3`, no tier (the start
resolves the default), `by` the scan's asker (the login that enabled
refactoring); trailer `<!-- engine: refactor-scan #<n> · p3 · by <login> · key
<key> -->`. The body is the Smith's, then `### The values this serves`: the
scan ticket, the cap
(**at most N changed lines**, and the proposal's estimate) and the values text
quoted line by line, so every ticket states both whatever the Smith wrote.
`approval` decides the mode (decided by the user on #1352's design round):

- `human` (the default): `feature:confirm`, no `authorized`. A human
  authorizes it as any ticket and confirms each round.
- `none`: `feature:auto`, and the engine starts the feature at once through
  `AuthorizedStart` (label event 0, mode `auto`, authorized by the enabling
  login, detail `refactor-scan #<n>`) and labels the ticket `authorized` as the
  visible record. The start is the engine's because the scan takes an
  `authorized` label only from a trusted login, which the fleet token may not
  be; the claim makes the scan skip that label, as an intake's ticket's
  (#1802). The label is written only after the filing job commits
  (`AuthorizedStart`'s `label`), so the scan its webhook wakes at once finds
  the claim; written inside the job, it was refused as an untrusted login's
  before the claim was visible (#1787-#1790). No human is in the loop: a scheduled job starts
  code-changing runs on its own, which is what `none` means.

**Not refiling.** The dedup key is the trailer's `key`, read from the repo's
open issues labelled `refactor` (one listing when the job files the scan, and
again when the scan files). A closed ticket frees its key: the next run may propose that work
again if the Smith still sees the gap. A proposal whose key is open is dropped
unread, so a Smith that ignores the do-not-propose list costs a slot, not a
duplicate.
