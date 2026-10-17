# Events

Part of the engine docs: [index](../README.md), [engine/README.md](../../README.md).

`GET /events?repo=<owner/name|*>` is the engine's event stream (built in parts;
this is the engine's half):
one long-lived `text/event-stream` fed by the in-process bus
`events/EngineEvents.kt`, which every place in the engine that knows of a
change publishes to. The frame types it publishes are `events/EngineEvent.kt`. Besides the
streams, `EngineEvents.onDeliver` hands every frame this replica delivers (its
own and relayed ones) to an in-process reader: the live parent's
`await_children` waits on it (`mcp/ChildWatch.kt`, `docs/feature.md`,
"await_children").
The desktop app holds one connection per repo it shows
(`repo=owner/name`, a target repo's full name matched ignoring case; a repo
that is not one is 400, as `GET /needs?repo=` answers) or one for the fleet
(`repo=*`, which receives every event, fleet-wide ones included: those carry
`repo: "*"` and reach no repo subscription). `repo` is required: there is no
default repo, and a call without it is 400 naming the target repos.

**The frames.** Each is `event: <type>`, then `data: <one JSON object>`, then
a blank line, and nothing else: no `id:` line, since nothing is replayed. The
types and their `data` are the proposal's table: every event carries `repo`
and `at` (an ISO-8601 instant), and `number` where it is about one issue. The
first frame on every connection is `event: hello` with `{"repo": "<the
subscription>", "at": "<instant>"}`, and the subscriber is on the bus *before*
`hello` is written, so every frame published after `hello` is on the stream:
a client that reads its snapshot after `hello` misses nothing. While nothing
happens a comment frame, `: ping`, goes out every 15 s
(`EngineEvents.PING_INTERVAL`), so a client that sees nothing for 45 s knows
the connection is dead and reconnects. The emitter is created with no timeout
of its own (`SseEmitter(0L)`): it does not rely on
`spring.mvc.async.request-timeout`, and the stream ends only at shutdown, when
the client goes, or when the engine closes a slow consumer. The response is
`Cache-Control: no-cache` and `X-Accel-Buffering: no`.

**No replay.** The engine keeps no frame once it is offered to the
subscribers. A frame a client was not connected for is gone; the client learns
what it missed from the reads that are the record (`GET /issues`,
`GET /needs`, …), taken on every connect after `hello`. There is no buffer, no
`Last-Event-ID`, and no per-client state but the open connection.

**Fan-out.** `publish(event)` serializes the frame once and offers it to each
subscriber's bounded queue of 1000 frames; a virtual thread per subscriber
drains the queue into the response. The publisher never blocks on a client: a
subscriber whose queue is full, or whose write fails, is closed by the engine
and the client reconnects and snapshots.

**Who may read it.** Unauthenticated, like every read, but a Smith sandbox is
refused outright, told by the request's source address (`SmithSandbox.runAt`,
as `TargetRepoController` and the MCP endpoint do): 403, and 503 when Docker
cannot say whether the caller is a Smith, since a request is never taken for a
person's because Docker did not answer. A Smith learns of its ticket from its
brief and its findings, not from the fleet's stream. A request with an
`Origin` header is 403, the DNS-rebinding guard `POST /mcp/<server>` applies:
a page in a browser must not read the fleet.

**After commit, never inside the command.** A publish from inside an Operaton
command (a delegate, a listener, the history handler below) goes through
`EngineEvents.publishAfterCommit`, which defers it to the command's commit with
the same `TransactionState.COMMITTED` listener `CloseWake.afterCommit` uses,
so a step that rolls back is never announced and a client never sees a state
the database does not hold. With no command context bound (a controller after
its write), the publish is direct.

**Flows and incidents: the history handler.** The engine runs at
`history-level=full`, so Operaton emits a history event for every process
instance start and end, activity instance start, and incident. The plugin
`events/FlowEventsPlugin.kt` registers one custom `HistoryEventHandler`
through `customHistoryEventHandlers`, which Operaton composes with its own
database handler, so history is written as before and no diagram changes. It
is not a `HistoryEventHandler` bean: the Spring Boot starter would take one
of those as *the* handler and drop the database one. The handler maps a root
instance's start and end to `flow` `started` and `ended` (`cancelled` when
the instance was deleted: a cancel), an activity's start to `flow` `waiting`
for a wait state (a user task, a receive task, a message catch event) and
`step` for anything else but a call activity, whose called process reports its
own steps; and an incident's create, resolve and delete to `incident` `open`,
`resolved` and `deleted`, with `incidentId`, `instance`, `root`, `activityId`,
`type` and `message`. Events of a called process (`smith-run`,
`reusable-ci-check`) are reported under their root: `instance` is the called
one, `root` the root, `step` the innermost activity (its name, else its id)
and `stage` the root's activity, in the words `GET /issues`'s `flow` uses.
`kind` is the root's process definition key. The frame's `repo` and `number`
come from the root instance's variables, `repo` and `issue`, else the issue in
its `issueUrl` or `ticketUrl` (an intake's), through `events/FlowIssues.kt`,
read from the history after the commit; a root with neither (the `hello` test
process, the doctor) publishes nothing. `incident` frames carry no board row.

**The board row, coalesced.** A `flow` frame carries the issue's whole board
row, `issue`: the `BoardIssue` that `GET /issues` lists, built by
`IssueBoard.row(repo, number)` (the per-issue builder `board()` uses, for one
issue: one listing read from the store (`Tickets.openListing`), and one blockers read; to find
the issue's split parent it reads the split of each of the repo's latest
`feature` approvals, the only flow that has one, not of every approval) and
serialized by the same mapper, so its shape is the board's by construction. It
is built on the bus's own thread after the commit, never inside the Operaton
command, and `flow` changes are coalesced per root over 1 s
(`EngineEvents.FLOW_WINDOW`): a Smith step that moves through three activities
in a burst costs one listing read and one blockers read and sends one frame,
with the row as it stands at the end of the window and the last change's
`step` and `stage`. A `started` in the window is reported over the steps that
came with it in the same command, and an `ended` or `cancelled` over
everything, so a lifecycle change is never swallowed by a step; a `flow` frame
is therefore up to 1 s late. When the listing read fails (GitHub down, a rate
limit, the issue closed meanwhile), the frame still goes out on time with
`issue: null` and every other field filled, and a view that holds rows
refetches `GET /issues` for that repo on it.

**A new issue's row, on `ticket` and `created`.** `GET /issues` lists open
issues through GitHub's issue list, which lags a just-created issue by
seconds, so a view that refetched on the frame announcing a new issue read a
list without it. The two frames that announce one carry its row instead, in
`issue`: built by `IssueBoard.row(repo, number)`, read by number and never
through `openIssues`, and serialized as a `flow` frame's `issue`. The read is
bounded, not moved off the publisher's path: it runs on a virtual thread and
the publisher waits at most 3 s for it (`rowWithin`, `events/FlowEvents.kt`),
so the webhook answers inside GitHub's 10 s timeout and frames keep the order
of the deliveries and creates; running apart also keeps a failed GitHub read
out of the Operaton command. A read that fails or is late gives `issue: null`
and a warn log, and the frame still goes out. The desktop applies a carried
row in place, on the Issues board and in the Tickets tree, with no refetch; a
frame whose `issue` is `null` refetches as before.

**The other sources.** Every other type is published by the place that
makes the change, after its commit when it is inside a command, directly
when it is a controller:

- `intake`: `POST /intake` (`intake/IntakeController.kt`, `submitted`, once
  the start message is correlated, which is when the id is known); `openAskItem`
  (`intake/OpenAskItemDelegate.kt`, `issue` with the ask work item's ticket
  `number` and `issueUrl`; no GitHub issue is involved; it runs inside the correlation, so its frame is
  out before `submitted`: a reader keys on `intakeId`, not on the order of
  the two); `postQuestion` (`intake/IntakeEndDelegates.kt`,
  `question` with the question's text); `fileTicket`
  (`filed`) and `fileUnlabeledTicket` (`triage`), each with `ticket`,
  `ticketUrl`, `kind` and `tier`; `closeIntake` (`ended`, with `ticket`);
  and a cancel (`intake/FlowCanceller.kt`, `cancelled`, with `by`).
- `ticket`: the webhook alone (`webhook/GitHubWebhook.kt`, "When it scans").
  The engine's own GitHub writes publish none: GitHub delivers the fleet
  account's label, comment and close like anyone's, so a `RestGitHub` frame
  would say each change twice. Each carries `issue` (below, "A new issue's
  row"): the board row for an `issues` delivery whose issue is open, `null`
  for a closed issue, an `issue_comment`, a `pull_request` delivery or a row
  not read in time.
- `created`: every place the engine creates an issue, the moment GitHub
  answers, and every intake's ask item once it is committed (`openAskItem`): `intake/TicketFiler.kt`, which
  `fileTicket`, `fileUnlabeledTicket`, `fileChild` and the proposal's build
  ticket file through. `{repo, number, url, issue, at}`, `issue` the new
  issue's board row. Published with `publish`, not `publishAfterCommit`: the
  issue is on GitHub whether or not the command commits, and the step's own
  frames (`intake` `issue`, `filed`) still wait for the commit, so they
  follow it.
- `push`: the webhook, for a verified `push` delivery: `{repo, at, ref,
  before, after, default}`, `ref` the pushed ref as GitHub names it
  (`refs/heads/main`), `before` and `after` its SHAs, and `default` whether
  `ref` is the repository's default branch. Of the payload only `ref`,
  `before`, `after` and `repository.default_branch` are read. And, since
  #100043, a work run's `push_chunk` (`mcp/PushChunkTool.kt`), right after
  its push to `refs/heads/smith/issue-<n>`, with `before` empty (the engine
  does not read the old head) and `default` false, so a live parent's
  `await_children` wakes on the chunk whether or not the webhook delivers
  (a webhook delivery of the same push is a second frame). A push is not
  on the wake allowlist, so it wakes no scan: the frame is for First mate's
  fresh clones, which fetch
  through the git proxy below when one says `default: true`.
- `deploy`: `deploy/DeployEvents.kt` (#100008; engine/README.md, "Deploys"), for
  every `POST /repos/{owner}/{name}/deploys` and for the engine's own start on
  its build repo, after the `engine.deploy_event` row is written: `{repo, at,
  sha, source}`, `sha` the commit now in production (lowercased, 7 to 40 hex
  digits) and `source` who said so (the request's `source`, `engine start`, or
  null). Published with `publish`; the `DeployWaits` hooks are called after it.
- `needs`, from the sources of the rows `GET /needs` derives, never from a
  poll or a diff; each frame carries `for` as the row does (whom it waits on,
  from the flow's `askedBy` or `submittedBy`; null when the publisher cannot
  read the flow, as after it ended): `question` `added` on `postQuestion` and `removed` on the
  answer endpoint (or a cancel); `decision` `added` on `postDecision`
  (`proposal/ProposalDelegates.kt`) and `removed` on its answer; a
  `question` `removed` (the root's id the `actionId`) after every `ended` or
  `cancelled` `flow` frame (`events/FlowEvents.kt`), since a flow that ends
  takes its `question`, `acceptance` or `decision` row with it, recorded
  question or not (#1172); `incident` `added` and `removed` from the history
  handler for every incident, beside its `incident` frame (a propagated
  incident is a row too once its root cause is gone, so it cannot be left
  out); `reauthorize`
  `added` when the scan's resume pass (`trigger/IssueScanner.kt`) first
  records a blocker closed without a merge on an `engine.deferred_issue` row,
  and `removed` when that row resumes, starts, is withdrawn or is replaced by
  a fresh approval. Each carries the row's `kind`, `number` and `actionId`.
  The two GitHub-backed kinds, `authorize` and `insufficient-permissions`,
  are made of labels and publish no `needs` frame: the webhook's `ticket`
  frames carry those changes, and the Needs view refetches on them. The
  `sighting` rows publish nothing: a doctor bug's leaves on its close (a
  `ticket` frame), and the no-defects-repo row's change reaches the desktop
  on its next read (a connect's catch-up, ⟳, or any `needs` or `ticket`
  frame). HITLP tasks publish no `needs` frame: the engine is no surface
  ("No surface in the engine"); the desktop and the phone follow the HITLP
  server's own events.
- `question`: a child's question under a running live parent (#100049;
  `docs/feature.md`, "Children ask the parent"), from flow-feature's
  `ParentQuestions`: `asked` when `post the smith's question` routes a child's
  `ask` to the parent instead of the user, with `text` (the question in full),
  `askedOf` (`#<parent>`), `number` (the child) and `instance` (the child's
  flow); `answered` when the parent's `answer_child` answered it (`by`
  `#<parent>`); `escalated` when the parent handed it to the user, or `note
  the live parent ended` did (a `needs` `question` `added` frame follows, as
  the user's row is then filed). A question asked of the user publishes no
  `question` frame: its `needs` frame is the wake, as before. A live parent's
  `await_children` wakes on `asked`; `answered` and `escalated` are
  bookkeeping.
- `session`: `startSmith` (`smith/StartSmithDelegate.kt`, `started`, after its
  job commits) with `runId`, `instance` (the root flow's), `mode`, `model`,
  `tier`, `account` and the `filename` and `host` the transcript pane queries
  VictoriaLogs with (`smith/RunLabels.kt`, `RunLabels`); `collectSmith`
  (`ended`, once per run: the exit notifier makes the collect run once) with
  `exitCode`, `seconds` and `peakMiB` from the `engine.smith_run_stats` row
  `SmithRunRecords` writes.
- `job`: a custom job's run (`jobs/JobDelegates.kt`, "Custom jobs and the
  SDK"): `started` from `task-note-job-started` of `bpmn/job.bpmn`, after its
  job commits, and `ended` from `task-note-job-ended` with `outcome` (`done`
  or `failed`; a run that incidents never says `ended`). Each carries `jobId`
  (the `engine.job` row), `name`, `kind`, `owner` (`user` or `system`,
  "Refactoring jobs"), `instance` (the `job` process instance) and `repo`,
  the job's target repo.
- `repo`: `repos/TargetRepoController.kt`: `added` on `POST /repos` (a repo
  already present publishes nothing), `removed` on `DELETE`, `changed` with
  `fields` on `PATCH` (`ciCheck`, which covers `ciPaths`; `securityReview`;
  `scanIntervalMinutes`; `doctorBugs`; `engineDefects`, also when
  `doctorBugs: false` cleared it) and on the token and webhook-secret `PUT` and
  `DELETE` (`token`, `webhookSecret`).
- `setting` (`repo: "*"`): `PUT /intake/tier` (`intake/IntakeTier.kt`,
  `intake.tier` with the value); `PUT /settings/public-url` (`engine.public-url`
  with the URL or null); `PUT /settings/parent-await-cap`
  (`parent.await-cap-seconds` with the seconds); every write of `settings/HitlpServerController.kt`
  (`engine.hitlp`, with `{changed: [field names]}` as the value and never a
  value of its own, since a token is among the fields; the HITLP task stream
  runs its `sync()` on it); `PUT /tiers/{tier}` (`tier/TierController.kt`,
  `tiers` with the row, `{tier, model, effort}`, as the value) and
  `PUT /security/tier` (`security.tier` with the tier); every write of
  `accounts/AiAccountController.kt` (`accounts`, with the account's name as
  the value: add, credential, enabled, remove, a usage refresh); and the
  doctor when it starts a fix (`doctor/Doctor.kt`, `doctor`, with
  `state: "started"`, the run, the root and the ticket as the value), when a
  run is a sighting (`state: "sighting"`, with the run, the stuck ticket's
  `repo`, `number` and `root`, the `sighting` id, `kind`, `where`,
  `signature`, `bug` (null until filing is built) and the `tickets` and
  `runs` counts), and once per episode when a place is backed off
  (`state: "backoff"`, with the ticket, the root, the `position`, the
  `reason`, `failed` or `bug`, and `until`).
- `first-mate`: `firstmate/FirstMatePods.kt` and `FirstMatePodController`, one
  per stdout line of a first-mate session pod, per pod status and per phone
  message (`line` `{"type":"user","text"}`) ("First-mate sessions"): `sessionId`, `seq` (the
  transcript row's) and `line` (the stream-json object, or the raw text).
- `setup`: `repos/RepoSetupChecksController.kt`, for a `POST /repos/setup/check`
  ("A new target repo's GitHub setup"): `started` when a background check
  begins; `done` with `report`, the `SetupReport` exactly as
  `GET /repos/{owner}/{name}/setup` serializes it, and `checkedAt`, when the
  report was read (a fresh kept report goes out as one `done` with its
  original `checkedAt` and no `started`); `failed` with `error`, GitHub's
  error text as the 502 gives it, and no report.
- `heartbeat` (`repo: "*"`): `DoctorTick` (`doctor/DoctorTick.kt`) on every
  tick, after the tick, with `running` (the root ticket flows), `waiting`
  (those parked on a user task or on a deferred row of their own) and
  `incidents` (the open incidents). With `engine.doctor.enabled=false` the
  tick bean is absent and nothing beats.

```
curl -H "Authorization: Bearer $FRAKWORKS_TOKEN" -N 'localhost:8090/events?repo=codefrak-oss/frakworks'
```
