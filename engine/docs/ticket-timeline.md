# Ticket timeline

Part of the engine docs: [index](../README.md), [engine/README.md](../../README.md).

`GET /ticket/{owner}/{repo}/{n}/timeline` (`camunda/TicketTimelineController.kt`)
serves the shape in `modules/app/src/test/resources/fixture-ticket-timeline.json`, the
contract the desktop Timeline widget was built against (#101); a test holds
the response to its field names and JSON types. `start`/`end` are epoch
seconds, every `*Ms` milliseconds.

Since #1966 it is served from a projection in the engine's own schema, with no
`HistoryService` query (a test counts them through a proxy and asserts zero;
the one Operaton read left is the in-memory `SmithSessions.live` of a running
run). The same goes for `read_ticket_runs` (`TicketTimelineController.runs`).
The projection's tables (V67; `camunda/TimelineProjection.kt`):

- `engine.ticket_flow`: one row per root flow, its `repo`, `issue`, `kind` (the
  process definition key), the approved or filed ticket's `title` (the first
  seen: an amendment rewriting the ticket leaves it) and `tier`.
- `engine.flow_instance`: every process instance, root or called, under its
  root, with its start, end and `cancelled`; `engine.flow_activity`: every
  activity instance of them, with Operaton's sequence counter (`seq`) so the
  ones that started in the same millisecond read back in the order they ran,
  live or backfilled; `engine.flow_incident`: every incident with its state and
  root cause.
- `engine.ci_wait`: one row per `receive-ci-check` wait, with the check it
  waited for and how it ended (`conclusion` the check run's, `outcome` the
  flow's `ciOutcome` after it, `rerun` when `read check` re-ran the check), so
  two waits in one flow read back separately where the history kept one value.
- `engine.review_finding`: a review or security run's findings as `read result`
  stamped them, one row each; `engine.smith_run_stats` carries the run itself
  ("Smith runs": `root`, `wrapper`, `outcome`, `question`, `turns`,
  `duration_ms`, `result`, `review_no`); `engine.intake_ticket` the intake's
  refine result (`refine_outcome`, `blocker`, `reason`) for `blocked`, written
  in its own transaction like the filing stamps, since `file ticket` runs in
  the same command and would wait on the command's row lock. Cost
  keeps its home in `engine.claude_usage.cost_usd` ("Consumption ledger").
- `engine.timeline_backfill`: one row per ticket filled from history, with when
  and by which path.

Two feeds write it. The history handler (`FlowEventHandler`, "Events") records
every process start and end (sub-processes too), activity start and end,
incident create, resolve and delete, and the variables the timeline keys on
(`repo`, `issue`, `tier`, `approvedTicket`, `filedTicket`, `ciCheck`,
`ciConclusion`, `ciOutcome`, an intake root's `output`) through the
`FlowRecorder` interface, inside the Operaton command's transaction: the rows
commit with the history they mirror and roll back with it; the stream's frames
are unchanged. The Smith delegates write the run: `start smith` inserts the
`smith_run_stats` row, `collect result` finishes it, `read result` numbers a
review (`review_no` = 1 + the highest under the root), writes its findings and
marks a work run it turned down `rejected`. Every write is an upsert.

The backfill (`camunda/TimelineBackfill.kt`) fills a ticket from history once:
`TimelineHistoryReader` (`camunda/TimelineHistoryReader.kt`, the history walk the controller used to make) replays
the ticket's root instances and everything they called into the same
projection, through the same calls the handler makes, and records
`engine.timeline_backfill` (`backfilled_at`, `source`). It runs on the lease
holder after startup, over every ticket `engine.handled_issue`,
`engine.intake_ticket` and `engine.smith_run_stats` know with no row, one at a
time (`source` `startup`), and on a ticket's first request when it has no row
(`source` `request`; the request then reads the projection). A ticket with no
instance in history gets no row. A live flow backfilled mid-way is completed by
the handler's upserts, not duplicated; a parity test runs the fixture flows,
truncates the projection, backfills and gets the identical response. The
truncation (`TimelineProjection.clear`) leaves the `smith_run_stats` rows and
their findings in place: since #1976 the row is the one home of a run's
model, effort, tier, turns, duration and `review_no`, so history cannot
rebuild it, and the backfill's upsert only fills a null column, never
overwrites one.

- **Steps.** Each `smith-run` is one step, whatever its `mode` — every
  `call-*` activity (`call-refine-ask`, `call-reproduce`, `call-do-work`,
  `call-security-review`, `call-review-pr`, `call-verify`, `call-design`,
  `call-replan`, `call-accept`, ...) gets a step, not only `work`/`security`/
  `review`. `role` is its `mode` (`work` → `coding`, `security` →
  `security`, `review` → `correctness`, any other mode under its own name,
  e.g. `refine`, `reproduce`, `verify`); `model`/`effort` are the run row's
  (`smith_run_stats.model`/`effort`, from `start smith`), `account` the AI
  account the run is on (`smith_run_stats.account`; null when unknown, and the
  desktop's running card then draws no account label), `cost`/`turns` the
  run's own once `collect result` has run (the closing stream-json `result`'s
  `total_cost_usd`/`num_turns`, exactly, in `claude_usage.cost_usd` and
  `smith_run_stats.turns`), with `tokens: null`. While the run is live they are its live tally
  (`LiveTally`, kept per run by `SmithSessions` from every stdout line
  `SmithTelemetry`'s follow hands it): `turns` is the number of distinct
  main-thread `assistant` messages read so far (`message.id`,
  `parent_tool_use_id` null; Claude Code writes one event per content block
  repeating the id and `usage`, so a message counts once), and `tokens`
  (`{input, output, cacheRead, cacheWrite}`) sums each distinct message's
  `usage` (`input_tokens`, `output_tokens`, `cache_read_input_tokens`,
  `cache_creation_input_tokens`), subagents' messages included since they
  are billed. `cost` stays null until `collect result`: Claude Code emits
  no running dollar figure, and the user chose cost at the end over an
  estimate priced by a table of the engine's (#1009), so there is no price
  table. All three are null before the first `assistant` event, and for a
  run whose stream has none (a `runner:codex` run: its card keeps "not yet
  known"). `collect result` logs the tally's turn count next to `num_turns`
  at INFO, so the live definition can be checked against Claude's, and
  forgets the tally. The tally is in memory; one lost to an engine restart
  is rebuilt once from `engine.smith_session_line`, where a line stored cut
  (over 64 KiB) no longer counts. `outcome` its result.json's `outcome` (`in progress` while it runs, else
  its `result` when the result.json has none). `round` is 1 + the pushes
  (`task-open-pr`) before a `work` run, and the pushes before any other run
  (at least 1), so a rework run is a new step at a higher round. A
  `security` run counts with the push before it: since 2026-09-26 it
  reviews the round already pushed ("Why the engine check runs before the
  reviews"); before, it ran ahead of its round's push. `catchUp` is true for a `security` or `review` run in a round
  (2 or later) that `bring pr up to date`'s merge of the default branch
  opened rather than a work push: no `call-do-work` started between the
  round's previous push and the run. Such a push still counts as a round;
  its PR comment reads "Round N, catch-up: merged main into the branch; no
  changes from review". `durationMs` is wall-clock: end − start, or now − start with
  `running: true` and `end: null`. A run stuck on an incident ends at the
  incident; the wait after it is the incident's. A step's span reaches out
  to its `reusable-smith-run` wrapper's own start and end (`TicketTimeline.runSpan`),
  so the wrapper's own activities (`start-smith-run`, `task-read-result`,
  `end-smith-run`) don't turn up as short `engine` waits either side of it —
  the whole `call-*` span is one step. A retry re-runs `call-smith-run` in
  place (`IncidentController.rerunSmith`) rather than starting a new wrapper,
  so a wrapper can hold several runs: only its first reaches back to
  `start-smith-run`, and only its last (the one it actually finished
  through) reaches forward to `end-smith-run`.
- **Waits.** The span runs from the first root's start to the last root's
  end, or to now while one runs. Every stretch of it no step covers (before
  the first, between two, after the last) is cut into waits by what history
  shows in it: a user task is `person-question` (`{question, answer}` from
  `engine.intake_question`, or the question in the result.json of the run
  before it when the flow records none, answer null); the message catch
  waiting for `authorized` (e.g. `event-intake-authorized`) is
  `person-authorization` — the `gateway-authorized` exclusive gateway that
  routes straight to `call-refine-ask` when the intake is already authorized
  never waited, so it is not one, however its id ends; a root-cause incident is `stuck`
  (`{incident: "<activity>: <message>", retrier, steps}`, `retrier` the
  logins every trusted login, joined with `, `, or
  null); `reusable-ci-check`'s receive task `receive-ci-check` (`wait for
  the engine check`) is `ci` (`{steps, conclusion, check}`: `check` the
  flow's `ciCheck`; `conclusion` the check run's `ciConclusion` when the wait
  ended, `rerun` for a wait `read check` re-ran the check after, null while
  it waits or when the flow recorded none; history keeps a variable's last
  value only, and the endpoint makes no GitHub call for it); anything else is
  `engine`. Where these overlap, stuck beats a question beats authorization
  beats ci beats engine. `payload.steps` names the activities in the
  wait. A wait still open ends now, a number like every other `end`.
- **Ticket and totals.** `state` is `OPEN` while any root runs, else
  `CLOSED`; `kind` and `tier` are the newest non-intake root's (else the
  intake's); `title` its approved (or the intake's filed) ticket's; `round`
  the highest step round. `blocked` is there only for an intake whose
  refine result (the `output` that `call-refine-ask` copies onto the
  intake, still readable after `task-file-unlabeled` filed the ticket
  and the intake closed) has outcome `insufficient-permissions`: `{"kind": "insufficient-permissions", "summary",
  "reason"}`, `reason` the refiner's full reason and `summary` its
  `blocker`, or when it gave none the first sentence of `reason`, cut to 20
  words with an ellipsis (`TicketTimeline.blocked`). Any other outcome, or
  no refine result yet, has no `blocked` key; the fixture's
  `_blockedIntake` shows one. `totals` are summed from the steps and waits:
  `elapsedMs = workingMs + waitingMs`, `waitingMs = waitingPersonMs +
  stuckMs + ciMs + engineMs` (`ciMs` 0 for a ticket with no CI wait),
  `costUsd` the steps' costs.
