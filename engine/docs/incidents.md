# Incidents

Part of the engine docs: [index](../README.md), [engine/README.md](../../README.md).

A flow that trips stays parked on an incident until it is retried or
cancelled (`DELETE /intake/{id}`), except a Smith run that ended failed,
which the engine first runs again once by itself (below, "One automatic
rerun of a failed run"): only its second failure parks. The engine has no Cockpit and no
Operaton REST API, so `POST /camunda/incidents/{id}/retry` is the retry.
An incident's `message` is its job's last failed attempt only. Every attempt is
served as the incident's `attempts` on `GET /camunda/instances/{id}`, in the
doctor's and the Fix's brief and by `read_job_attempts`: each `{at, message,
retriesLeft}`, oldest first, read from Operaton's historic job log (`failureLog`
rows; `history-level=full` keeps one per attempt, a retry's included), so an
earlier failure (GitHub's 500 before the 422) is not lost (`camunda/JobAttempts.kt`).
It takes any of the flow's incidents: one propagated up a call activity
is followed to the incident that caused it. Then:

- **A Smith run that ended failed** (a failed job in `smith-run` whose
  `result` is set and is not `success`, or whose incident message starts
  with "the smith's result breaks the rules" — a refiner that exited 0 but
  whose drafted ticket `ReadResultDelegate.enforce` rejected, so `result`
  stays `success` even though the run failed) is **run again**. An
  unappliable bundle is one of these: `CliGitPush.verify` finds its
  prerequisite commit(s) on no branch of the repo and says so under that
  prefix. The sandbox and its commits are gone, so replaying `read result`
  on the stored bundle fails forever and only a new run from the current
  default branch can help; the retry drops the caller's `bundle` and
  `previousBundle` so the rerun does not get the stale draft, and its
  comment names the missing commit(s). A bundle without
  `refs/heads/smith/issue-<n>` is unappliable too (`GitPush.MISSING_BRANCH`,
  seen on #1471, where the fetch failed with `couldn't find remote ref`):
  `task-open-pr` fails at once, without Operaton's retries, and since that
  incident is in the flow, not in a `smith-run`, its retry starts the flow's
  work call activity (`call-do-work`, a proposal's `call-draft`) over and
  drops the stored `bundle` and `previousBundle`. A bundle that rewrites the
  PR branch is the third (`GitPush.REWRITES`, seen on #2165, where a rework
  round's bundle was not made on top of `smith/issue-<n>` and `git fetch
  --quiet` refused the non-fast-forward with no output at all): `CliGitPush`
  checks the bundle's head contains the branch's before it fetches, names
  the branch and both shas, and `task-open-pr` fails at once the same way,
  its retry starting the work call activity over. The engine never
  force-updates the branch. A retry of either `task-open-pr` refusal
  (`MISSING_BRANCH`, `REWRITES`) also leaves a `push-<n>-1` finding (review
  `push`, `<n>` counting such refusals on the flow in `pushRejections`, never
  reset) in the flow's `findings` and `raised`, as a conflict round leaves
  `rebase-<n>-1`: it quotes the refusal and tells the rerun Smith that the
  engine pushes `smith/issue-<n>` fast-forward only, to build on the branch as
  cloned, to pick up the default branch with `git merge origin/<default>` and
  never rebase, amend or reset, and to answer with `ask` when the ticket or an
  amendment demands a rewrite. The work and propose prompts say the same rule
  up front. Without it the rerun got the same brief and rebased again, the loop
  #2165 sat in through three doctor retries. `open/update pr` drops the
  `push-*` finding once a push lands, so it does not linger into later rounds.
  The rerun starts from the PR branch (`run-smith.sh` fetches `pr.branch`),
  and the retry's comment says so. A git command that fails with no output names
  its whole argv in the message. To trace one, `run-smith.sh` logs
  `smith: cloned <owner/name> at <sha> (<ref>)` after the clone and
  `smith: bundle base <sha>` when it bundles. A propose round that starts
  from an unpushed draft with no PR now bundles from the commit it cloned:
  `START` was the name `HEAD`, which the draft's checkout moved, so the
  bundle's base was the earlier round's unpushed tip (#1361, seen on #1322). A run that
  exited 0 but wrote no `result.json` is recorded with `result` `no-result`
  (not `success`), so it is run again too; it never takes the work
  overflow route back to design. Retrying its
  job would only reach "read result" and fail on the stored result, so the
  call activity that started the run is started over (process instance
  modification). That runs a new Smith with a new account pick, and the
  failed run and its incidents are cancelled.
- **Any other failed job** gets its activity's retry cycle back: the count
  of its `failedJobRetryTimeCycle` (3 tries 30 s apart for `open/update pr`'s
  `R3/PT30S`, so a transient error on the retried attempt is tried again
  rather than an incident at once), or one more try (`retries = 1`) on an
  activity without one (a call activity such as the doctor's `health check`).
  "start smith" with no usable account carries `R3/PT30S` too, so its retry
  gets three tries 30 s apart.
- **A conflict-cap incident** (a failed job on `task-rebase-pr` or
  `task-ci-conflict` whose message starts with `merge conflict with` and
  names `conflict rounds`: either `ConflictRounds` cap) would only meet the
  same conflict with the same count again, so the retry first grants the
  flow 2 more conflict rounds (`conflictRounds` back to 0,
  `conflictRoundsGranted` 2, on the job's instance and the root flow), then
  gives the job its retry cycle back (`action` `job`). If the branch still
  conflicts, the probe sends a `rebase-<n>-1` finding back to the work as an
  automatic round does; if main moved and it merges, the flow goes on. The
  lifetime cap does not count granted rounds, so a retry of the lifetime-cap
  incident grants the same; `<n>` keeps counting them. `act_on_incident`'s
  `retry` and the doctor's `retry` remedy therefore apply to it.

**A rejected work result** (#1194) is not an incident the first time. When
`WorkResult.problems` turns down a work run's result (a `done` with no
commits, a finding neither addressed nor declined, …) in a chore, bug or
feature, `read result` sets `outcome` `rejected` and `rulesRejected`, and
`gateway-work-outcome` sends the flow back to the work: a new Smith (a new
`smith-run`) works the round again, and the ticket timeline shows the
rejected run ended `rejected`. Its brief carries a `rules-<round>-1` finding
(round as the `ci` finding counts it) in `findings` and `raised`, next to the
findings the rejected round was answering: its `ask` quotes every problem
and names the valid results (`done` with commits and every other finding
answered, `ask` when nothing should change while findings stand,
`nothing-to-do` only when no other finding is open); the rules are checked
without it, so it needs no answer. The engine comments on the ticket that
the result was rejected, with the problems. A second rejection in a row
(the rerun's result is rejected too) parks as before: a `failedJob` incident
whose message starts with "the smith's result breaks the rules", which the
retry answers by running the Smith again (above). An accepted work result
clears `rulesRejected`, so a later rejection starts a fresh count.

A propose result the rules refuse (`ProposeResult.problems` or
`changeProblems`, `PitchRules` among them) is rejected the same way (#1742):
`gateway-draft-outcome` in `bpmn/proposal.bpmn` sends `rejected` back to
`call-draft`, the new draft Smith's brief carries the `rules-<round>-1`
finding (its `ask` quotes every problem and names the valid results: `drafted`
with a path, a pitch, a summary, questions, a bundle and an `options` entry for
every `data-option-id` in the page, or `ask`), the engine comments that the
draft result was rejected, and a second refusal in a row parks on the same
incident. An accepted propose result clears `rulesRejected` and drops the
`rules` finding, so the review never sees it.

An accept result the rules refuse (`AcceptResult.problems`: an `accepted`
outcome with a criterion not checked, a missing summary, an `unmet` with no
`met: false` criterion) is rejected the same way (#1875):
`gateway-accept-outcome` in `bpmn/feature.bpmn` sends `rejected` back to
`call-accept` along `flow-accept-rejected`, the new accept Smith's brief
carries the `rules-<round>-1` finding (its `ask` quotes every problem and
names the valid results, each with a summary: `accepted` with every criterion
`met` true, `unmet` with at least one `met: false`, or `unverified` when some
criterion could not be checked here), the engine comments that the accept
result was rejected, and a second refusal in a row parks on the incident.

A doctor run's incident is its ticket's (#1084): the retry needs a trusted login, and comments on the ticket's issue. A
doctor run parked on `no account available until <t>` (or `no account can run
Fable until <t>`) is retried by the doctor's tick itself once `t` has passed
("Doctor", "A doctor run's own incident"), and so is a ticket flow's own
"start smith" incident with that message (#1810).

A run that failed only on its account's **session or spend limit** never
gets that far when another account can take it, now or at a known reset.
"collect result" marks the account rejected until the reset the result names
(or the meter's), as it always has, and then sets `smithRerun` when the run
is a failed (non-zero exit) one that `reusable-smith-run` called, its account
was not pinned, its mode is not `fix`, `diagnose` or `doctor` (their failure
stays their outcome), its account has not already rerun this step, and either
the picker would choose another account of its provider right now
(`AiAccounts.peek`), or, with none usable now, the earliest reset of the
provider's enabled, credentialed accounts is known (`AiAccounts.blockedUntil`).
`reusable-smith-run`'s `rerun?` gateway then skips "read result": "note
rerun" comments on the ticket's issue, in a retry's words, and the call
activity is entered again, so a new Smith runs on a new pick. The failed run
ends normally (its `session` `ended` is published once, as for any run), and
no incident is created.

- **Another account usable now**: the rerun starts at once. The comment says
  "The engine started its Smith run again on this chore (`<root id>`): the
  last one ended on account X's session or spend limit, rejected until …;
  another anthropic account (Y) is usable."
- **Every account limited, a reset known** (the timed rerun, #1103): "collect
  result" also sets `smithRerunAt` to that earliest reset, and after "note
  rerun" the `wait?` gateway takes the wrapper to `event-wait-reset`, a timer
  (`timeDate` `${smithRerunAt}`) that Operaton's job executor fires at the
  reset with no tick, the doctor's or anyone's. The comment comes once, before
  the wait: "The engine starts its Smith run again on this chore (`<root id>`)
  at 2026-10-05T21:50:00Z: the last one ended on account X's session or spend
  limit, rejected until 2026-10-05T23:20:00Z; no other anthropic account is
  usable until 2026-10-05T21:50:00Z." When the timer fires, `call-smith-run`
  is entered on a new pick; if the picker still has no usable account then,
  "start smith" fails with `no account available until <t>` and `retries = 1`,
  as for any run. While it waits, the ticket timeline shows the gap between
  the two runs of the step as an `engine` wait (its `payload.steps` name
  `event-wait-reset`), not a `stuck` one, and `/camunda/instances/{id}` lists
  the timer under the wrapper's `timers` with its `dueAt` (and
  `smithRerunAt` among its variables). Every other rerun (an immediate one, the
  one automatic rerun after a failure) leaves `smithRerunAt` null and does not
  wait.

It is bounded: the accounts a step has been rerun after, at once or at a
reset, are kept in `limitReruns` on the caller (every variable goes in and out
of a run), each account reruns a step at most once, and a run of the step that
ends any other way clears it. A session-limited run that is not rerun (the
bound, a pin, no known reset on the run's own account, or none on any account
of the provider) ends on its incident at `task-remove-sandbox` as before, and
a retry runs the Smith again; the incident message keeps its generic first
line and adds why on the second, such as "It ended on account X's session or
spend limit, rejected until 2026-10-05T16:40:00Z; this step was already rerun
after a limit on X." or "…; no other anthropic account is usable." So the
doctor's `rate-limited` class on a failed run now means the run could not be
rerun for a reason other than every account being limited with a known reset
(a pin, the bound, no reset known), not that one account was. The
Fable limit ("no account can run Fable until …") does not take this path.

**Evicted runs are rerun** (#1428). A Kubernetes run whose pod the cluster
evicted or preempted ("Burstable pods and eviction") ended `failed` through no
fault of its own: "collect result" (`decideEvictRerun`, before every other rule)
sets `smithRerun` when `reusable-smith-run` called it and its mode is not `fix`,
`diagnose` or `doctor`, and counts it in `evictionReruns` (`<wrapper>:<n>`, kept
on the caller like `failRerunOf`), without spending the one failure rerun below.
"note rerun" comments "the last one ended evicted by the cluster (node pressure
or preemption), eviction n of at most 4". Past `MAX_EVICTION_RERUNS` (3) the next
eviction is not rerun: the flow parks on the usual incident, whose second line
reads "It ended evicted by the cluster 4 times …".

**One automatic rerun of a failed run** (#1047). A run that hit no session
or spend limit and ended `failed` (a non-zero exit: a crash, an OOM kill, a
rate limit the result did not name) or `no-result` is started again once by
the engine, with no human, Smith or doctor action, through the same `rerun?`
gateway: "collect result" (`CollectSmithDelegate.decideFailRerun`, after the
limit rule, which goes first) sets `smithRerun` when `reusable-smith-run`
called the run, its mode is not `fix`, `diagnose` or `doctor`, it is not a
`work` run that overflowed its context window in a flow with a design step
(that one goes back to design), and `failRerunOf` does not already name the
calling `reusable-smith-run` instance. It then sets `failRerunOf` to that
instance's id: that is the cap. Like `limitReruns` it is kept on the caller
(every variable goes in and out of a run), so the wrapper's next failure is
not rerun: "read result" fails on it with the generic `smith run <handle>
ended <result>; see its log` and the flow parks on one incident at
`task-remove-sandbox` after one try: its `failedJobRetryTimeCycle` is
`R1/PT0S` while the run's stored `result` is set and not `success` (a retry
would only re-read the same result) and Operaton's three immediate tries
otherwise. The doctor, Fix, `POST /camunda/incidents/{id}/retry` and
`act_on_incident` handle it as before: its retry reruns the Smith. A
later step's wrapper is a new instance, so a value left from an earlier step
never blocks it, and no caller process carries a new variable of its own.
"note rerun" comments once per rerun, in a retry's words ("The engine started
its Smith run again on this chore (`<root id>`): the last one ended failed
(exit 1); this is its one automatic rerun, a second failure parks on an
incident."), a new Smith runs on a new account pick, and the failed run ends
normally with no incident; the ticket timeline shows it as another run of the
same wrapper, as for a retry. A retry of the parked incident starts
`call-smith-run` over in the same wrapper, so it always runs the Smith again
(the cap does not stop it), and its failure parks again with no second
automatic rerun. Not rerun: a result `ReadResultDelegate.enforce` rejected
(its `result` is `success`), a `fix`, `diagnose` or `doctor` run (its failure
is its outcome), a work overflow back to design, a session-limited run the
limit rule did not rerun (its incident's second line says why), and "start
smith" with no usable account, which never reaches "collect result" and gets
its task's `R3/PT30S` cycle back.

A push the remote rejects in `open/update pr` (`PushRejected`) is not
retried by Operaton: its incident comes after one try, and its message's
first line, the one `/needs` shows, starts `push rejected:` with the cause
(for a token without `workflow` scope that touches `.github/workflows/`,
"give the repo a token with workflow scope (PUT /repos/{owner}/{name}/token), then retry"). Other push
failures keep three tries 30 s apart (the task's `R3/PT30S` cycle). The two
combine through `OpenPrDelegate.failWithoutRetries`: it sets the job's retries
to 1 and stores an exception message in a transaction of its own, so the
cycle, which would reset a job with no stored exception to its 3 retries,
only takes the 1 to 0; on a retry, which restores the cycle's 3, it sets 1
again, so a rejected push is still an incident after one try. Retrying it pushes the stored `bundle` again.

The desktop app's process view puts a Retry on the incident in a step's
detail (`observability/desktop/README.md`, "The process view"). A Smith
retries or cancels one with `act_on_incident` on frakworks-engine-diagnostics
("The diagnostics server"), under its own rule rather than a login's access; its
cancel by ticket also takes a flow that has no incident.

## Fix and Propose a fix

A ticket whose flow, or a process it called, is stuck on an open incident
can have a Smith look at it (`camunda/IncidentFixController.kt`):

- `POST /camunda/incidents/{id}/fix` starts an `incident-fix` process
  (`bpmn/incident-fix.bpmn`) in mode `fix`: a sandboxed Smith run
  (`reusable-smith-run`, with the ticket flow's model, effort, tier and
  approval) is given the incident in its brief ("The brief") and picks a
  remedy from a menu: `retry` or `unfixable`. It cannot touch the engine or
  Operaton; "apply the fix" (`ApplyIncidentFixDelegate`) retries the incident
  through the same path as `POST /camunda/incidents/{id}/retry`, only if it
  is still open.
- `POST /camunda/incidents/{id}/propose` starts one in mode `diagnose`: the
  Smith only diagnoses, and its `summary`, cut to at most 40 characters, is
  kept as the proposal. Nothing is changed.

Both take no body (the token's login acts, as for a retry; a `by` is 400) and answer `202` with
`{fixId, incidentId, mode}`; `404` when there is no such open incident (or
its flow has no repo and issue), `403` when the token's login has no access to the repo,
and `409` when a Fix or a Propose a fix is already going on that incident
(either mode blocks both). A request from a Smith sandbox, known by its
source address as in "The frakworks server in a Smith", is `403` whatever
`Authorization` says, and `503` when Docker cannot say whether the caller is one. The
process carries `fixRepo`/`fixIssue` rather than `repo`/`issue`, so it is
never taken for the ticket's own flow.

**Jev pre-triages a Fix** ("Jev accounts"), use case `incident-triage`.
Before a `fix` starts its Smith, the endpoint asks Jev a `choice` over the
incident (the state is its `activityId`, `activityName`, `processDefinition`,
the ticket's `rootFlow` and the incident `message`): `retry` (a passing
failure: a 529 or 429, no usable account, a lost wake, a timeout),
`unfixable` or `investigate`. On `retry` at confidence 0.9 or more
(`engine.jev.incident-triage-floor`), the process carries `fixJevRetry`, and
its `jev retry?` gateway goes straight to "apply the fix", which retries the
incident as the user who pressed Fix, the same way a Smith's `retry` is
applied, with no Smith run; `fixDiagnosis` says Jev read it as retryable, and
with what confidence. Jev is not asked for a Propose a fix, for an incident
that is not a failed job (a retry would be refused), or when the ticket's
latest Fix was a Jev retry that did not fix it (`fixFailed`), so pressing Fix
again always gets a Smith. **The fallback:** with `investigate`, `unfixable`,
a `retry` below the floor, or no answer (no jev account, a timeout, 429/529
past the retries, any error), the Fix Smith runs exactly as before. The
`engine.jev_call` row (`fixJevCall`) has the floor as its threshold and the
action taken (`retry: retried without a Smith`, `investigate: Fix Smith`,
`retry below floor 0.9: Fix Smith`, `no answer: Fix Smith`, ...). Its
`outcome` is written by "apply the fix": `retried without a Smith: retried`
(or `failed`, with why), or the Fix Smith's own remedy (`the Fix Smith's
remedy: unfixable, failed (…)`), which is what Jev's pick is judged against.
A retry that trips again gets `; tripped again (fixFailed): incident <id> at
<activity>` added once, when `GET /camunda/incident-fixes` or the next Fix
on the ticket first sees the new incident.

Its `fixOutcome` is `proposed`, `retried` or `failed` (`fixDetail` says why:
the Smith found it unfixable, the incident is no longer open, the retry was
refused, or the run failed), with the Smith's `fixDiagnosis`.

`GET /camunda/incident-fixes?issueUrl=<url>` is what the ticket view reads:

```json
{"incident": {"id", "activityId", "type", "message", "processInstanceId", "createdAt"},
 "fix": {"state", "fixFailed", "endedAt", "diagnosis", "detail"},
 "propose": {"state", "proposal", "diagnosis"}}
```

`incident` is the first open one under the issue's running flows (null for
none); `fix` the latest Fix for the issue and `propose` the latest Propose a
fix on that incident (null for none). `state` is `running`, `failed` when the
run is itself stuck on an incident, or else its `fixOutcome`. `fixFailed` is
true when the Fix failed, or when it retried and the flow tripped again (an
open incident created at or after the Fix ended). The desktop app's ticket
view draws these as two buttons inside the collapsible Doctor section,
below its heading and above the doctor runs list
(`observability/desktop/README.md`).

## Doctor

The doctor (`doctor/Doctor.kt`) looks for tickets whose flow has stopped
moving without anyone noticing, and has a Smith diagnose and fix each one.
A Smith run that ended failed reaches it only once its one automatic rerun
("Incidents") has failed too: a one-off failure is rerun without a doctor
run.
Every `engine.doctor.interval` (`PT1M`, one minute, by default) `DoctorTick`
reads each running ticket flow (a root `chore`, `bug`, `feature`, `proposal`
or `intake` with a repo and an issue) and works out its **position**
(`TicketPositions`): the leaf activities of the root and of every process it
called (a call activity is not a leaf; what it called is walked instead),
each as `<process>:<activity>@<wait>`, plus `incident:<id>` for each open
root-cause incident. The wait says what the leaf waits on:

- `smith`: a smith-run at `smith exited` whose sandbox is still running (a
  sandbox that cannot be asked counts as running, so an unreachable Docker
  never makes a ticket stuck);
- `user`: a user task (a question, a pre-#583 feature's acceptance), or the intake's
  `ask item started` (`event-intake-authorized`);
- `scheduled`: something with a clock of its own: the engine check
  (`reusable-ci-check:receive-ci-check`), a split feature's child
  (`feature:event-child-closed`), or a job that is due later or locked by an
  executor;
- `none`: nothing will move it: a failed job, a due job no executor took, a
  job-less wait the doctor does not know, or a Smith whose sandbox exited and
  whose exit was never collected.

The position is kept in `engine.doctor_position`, with when it was first
seen. A ticket with an open incident is **stuck** on the first tick that sees
that position; one with a leaf that waits on `none` is stuck when its position
is the same as at the previous tick. The wait is
part of the position, so a sandbox that exited a moment ago is a move, and
stuck only on the tick after. A previous row older than two intervals (the
engine was down, or a tick came late) is only a baseline, and a new flow is
one too, unless it has an open incident. The exception is a stale row whose
position is the one seen now, when a doctor run on this flow and position
already ended `none` (healthy, unfixable) or `applied`: then the gap is no
new episode. The row keeps its since, so the episode goes on and the gap
alone starts no second run. A stale row at a position whose runs all ended
`failed` (the doctor's own Smith failed, so no verdict was reached) is a
baseline as before, and the flow gets a new run once it is stuck again. A
changed position (a new incident id on the same activity is one) is always
a baseline.

For a stuck ticket the doctor starts one `doctor` process
(`bpmn/doctor.bpmn`) per **stuck episode** (the same flow, position and
since): not while a doctor run or a Fix ("Fix and Propose a fix") is going
on that flow (one that is itself on an incident counts as ended), and never a
second for the same episode, however it ended (a `failed` run is tried again
only in a new episode). The process carries
`doctorRepo`/`doctorIssue`/`doctorRoot`/`doctorPosition` rather than
`repo`/`issue`, so it is never taken for the ticket's own flow. Its
`health check` is a sandboxed Smith run (`reusable-smith-run` in mode
`doctor`, with the ticket flow's model, effort, tier and approval), given
the position in its brief as `stuck`: `rootInstanceId`, `rootFlow`,
`position`, `stuckSince`, `leaves` (each with its `processDefinition`,
`processInstanceId`, `activityId`, `activityType`, `wait`, `why`, `jobId`
and `sandboxHandle`), `incidents` (with a failed job's stacktrace and its `attempts`, every failed attempt's message and time, "Incidents"), `earlier`
(the earlier doctor runs on the same root and leaves, newest first, each
`{"id", "startedAt", "remedy", "outcome"}`) and the root flow's `variables`. The Smith cannot touch the engine, Operaton or
GitHub; it may read the engine over `$ENGINE_URL`. It picks one remedy
from a menu, which "apply the remedy" (`ApplyDoctorRemedyDelegate`) applies
only while what it names is still part of the ticket's position:

| remedy | what the engine does |
|---|---|
| `healthy` | nothing: the flow waits on something that will come |
| `unfixable` | nothing: a human must act, as the diagnosis says |
| `retry` | retries the incident `target` as `POST /camunda/incidents/{id}/retry` does, as the engine's `doctor` (no access check; the issue comment says so) |
| `collect-exit` | delivers the exit of each of the ticket's Smith runs whose sandbox exited unnoticed (or only run `target`), as the exit backstop does |
| `run-job` | executes the due job `target` the executor left |

The prompt (`engine/smith/run-smith.sh`, mode `doctor`) also lists the
incidents whose retry reruns the Smith rather than the job, as
`IncidentRetrier.retry` does ("Incidents": a rejected result, "the smith's
result breaks the rules", a failed Smith run, an unappliable bundle, and a
push without the `smith/issue-<n>` branch at `task-open-pr`), so the doctor
picks `retry` for them instead of `unfixable`; and it says that an incident's
`activityId` is the last `asyncBefore` step before the failure, while the
message names the step that really failed (`task-read-result`, with no
`asyncBefore`, fails in `smith-run`'s job at `task-remove-sandbox`).

A failed doctor run, a broken result or a remedy that cannot be applied
does not become an incident of its own: the run ends `failed`, with why.
Every result but `healthy` names a `defect`: `where` it is (`engine`, the
fleet's own code or infrastructure; `target`, the target repo; `none`, no
defect, only a human's decision) and a one-line `summary`. A remedy the
engine applies with no human (`retry`, `collect-exit`, `run-job`) is never
`where: none`: `DoctorResult.problems` refuses it, so the run ends `failed`.

**A doctor run's own incident** (#1084). A doctor run can still park on an
incident of its own: its "start smith" found no account, or a job failed. That
incident is its ticket's, not a ticketless one. `GET /needs` gives it the
ticket's repo, number and title (`doctorRepo`/`doctorIssue`, the title from the
board's listing or the copied ticket). `POST /camunda/incidents/{id}/retry`,
`DELETE /intake/{id}` and `act_on_incident` check `Access.trusted`
for the login. A refusal names it ("@<login> is not a trusted login"), and the comments go on the ticket's issue ("… retried
`call-health-check` on the doctor run (`<id>`) on this chore."). **Cancelling**
a doctor run ends that run alone (`DoctorRunEnder`): it stops the run's Smith
sandbox, deletes its instance, and finishes its `engine.doctor_run` row as
`cancelled`, with "cancelled by <who>: <reason>". The ticket's issue gets a
comment and nothing else. It is not closed and not relabelled, and the
ticket's own flow goes on.

Each tick, before it reads the tickets, the doctor does two things to its
parked runs:

- **Self-resume.** It retries, as the engine's `doctor`
  (`IncidentRetrier.retryAsSystem`), each open incident of a doctor run, or
  of a ticket flow (`Doctor.TICKET_FLOWS`: its own "start smith" too, #1810), whose
  message is `no account available until <t>` or `no account can run Fable
  until <t>`, once the tick's time is at or past `t`. The engine has no signal
  for an account window reopening, so `t` is read off the incident on every
  tick. A run still parked when the engine starts is resumed on its first tick
  if `t` has passed. The comment on the ticket's issue starts "The engine's
  doctor retried …". Before `t`, a ticket whose every open incident is such an
  account wait is waiting, not stuck: no doctor run, no sighting and no ask
  (#2513).
- **No Smith token: one incident.** A "start smith" that fails for want of a
  usable Smith token for the repo (`no Smith token for <repo>: PUT
  /repos/<repo>/smith-token`, from `RepoTokens.forSmith`, or `SmithTokens.forRun`'s
  refusal ending `; no Smith runs on <repo> until PUT …`) is cleared by no
  retry, only by the user setting the token. A ticket whose every open incident
  is one of these waits on the user, as an account wait does: no doctor run
  starts on it (`Doctor.noSmithToken`). A doctor run is a Smith in the same
  repo, so its own "start smith" would fail the same way and park on an
  incident that `GET /needs` lists under the ticket too. The ticket's own
  incident stays the one open row until it is retried or cancelled. Before
  this, each tick started such a doctor run; slow ticks made each look like a
  gap past two intervals, so every tick started a new episode, and hitlp#100012
  had 131 incident rows for one failure (#100020). A gap at a position a doctor
  run still runs on now starts no new episode, either.
- **Self-end.** It ends (`DoctorRunEnder`, outcome `ended`) every doctor run
  whose ticket's flow (`doctorRoot`) is no longer running, or is no longer at
  `doctorPosition`. The detail says "the ticket closed: …" or "the ticket
  moved: …". A diagnosis of a place the ticket left is no use. No sighting is
  written for an `ended` or `cancelled` run.

A run whose ticket has not moved and whose incident is of any other kind stays
parked for a person. Both rules run only while the tick does
(`engine.doctor.enabled`).

**Sightings** (`doctor/DoctorSightings.kt`). Every doctor run that ends on a ticket that was really stuck is a
sighting, an `engine.doctor_sighting` row "apply the remedy" writes (in its
own transaction; a failed write is a WARN, never the run's failure), with its
`kind`:

| kind | the run |
|---|---|
| `unfixable` | ended `unfixable` |
| `retried` | had its `retry` applied |
| `not-held` | ended any way but `failed`, and the run before it on the same root and leaves was an applied `retry`, named as `earlier_run_id` |
| `backstop` | had its `collect-exit` or `run-job` applied (always `where: engine`) |
| `remedy-failed` | picked a remedy the engine could not apply |
| `doctor-failed` | failed itself: its Smith failed or its result was broken (`where: engine`) |

A `healthy` run is no sighting. Each sighting has a **signature**, an exact
key (SHA-256, `Signature`) over `where`; the position's leaves, sorted and
without incident ids; and, per open incident in the brief, its activity, type
and first message line with UUIDs, hex runs of 7 or more and digit runs as
`#` (cut to 160 characters). The generic "smith run … ended failed; see its
log" names no cause, so the failed run's class is added: `oom`, `rate-limited`
(its account's 5-hour or 7-day utilization at 100% and resetting after the run
ended; for a `claude-fable-*` run, also the Fable limit: `fableLimit`
`reached` or the Fable meter at 100%, its reset after the run ended) `sandbox-failed` (the run exited 69: a step of `run-smith.sh` before `claude`, the
clone, failed) or `exit:<code>`, from `engine.smith_run_stats` and
`engine.ai_account_usage` (`doctor/RunClasses.kt`). A `doctor-failed` sighting is
signed with `doctor-failed` and the doctor's own run class instead. A new
signature is stored in `engine.doctor_signature` with no bug. Each sighting
is a `setting` `doctor` frame with `state: "sighting"` ("Events").

**Doctor bugs** (`doctor/DoctorBugs.kt`, #998). Two switches on each target repo (`engine.target_repo`, V36),
both off by default and set only by `PATCH /repos/{owner}/{name}`:
`doctorBugs`, whether the doctor files and comments for that repo's stuck
tickets, and `engineDefects`, which marks the one repo engine defects are
filed in (it needs `doctorBugs`, and only one repo may have it: a unique
index and a check say so in the database too). After its sighting is
recorded, "apply the remedy" (`DoctorBugs.act`, its failure a WARN):

- **Exact lump.** A signature already on a doctor bug lumps the sighting into
  it (`lumped_by: exact`), whatever the stuck ticket's repo and whatever the
  bug's state: an engine row. A declined bug keeps counting. A bug closed as
  fixed is "After a fix", below; an open one gets "Repeats".
- **The home repo.** A `where: engine` sighting's bug is filed in the repo
  marked `engineDefects`; a `where: target` one's in the stuck ticket's own
  repo. The stuck ticket's repo and the home repo both need `doctorBugs` on;
  otherwise the sighting is only recorded. `where: none` is never filed.
- **Jev, for a new signature** (`doctor/SightingCheck.kt`, use case
  `doctor-sighting`). A sighting whose signature is on no bug, when it may be
  filed, first asks Jev one `choice` about the home repo. The state is the sighting:
  `where`, the Smith's `defect.summary`, its diagnosis (cut to 4000
  characters), the leaves, each incident's activity, type and normalised
  message, and the stuck flow's kind. The options are up to 254: the home
  repo's open issues labelled `bug`, newest update first, each `#<n>` with
  its title and first three body lines (240 characters, as the intake's
  duplicate check describes them), then the doctor's bugs there closed in
  the last 30 days, newest close first (GitHub's title and their sightings'
  summaries); plus `new` ("none of these: a different defect"). The
  question: "Is this stuck ticket the same defect as one of these bugs? Same
  defect means the same fix would have prevented it; the same symptom with a
  different cause is `new`." A pick of a bug at
  `engine.jev.doctor-sighting-floor` (0.9) or above lumps the sighting
  (`lumped_by: jev`, `jev_call_id`) into that issue's doctor bug (made `open`
  when it has none) and puts the signature on it, so the next sighting is
  exact. `new`, a pick below the floor, or no answer (no jev account, a
  timeout, 429/529 past the retries, any error) files, below. With no
  options, or the repo's issues unreadable, there is no call and it files.
  No call is made for a known signature, or where the sighting may not be
  filed. The `engine.jev_call` row (its `repo` the home repo, its
  `process_instance` the doctor run) has the floor as `threshold` and as
  `action` what was done: `#412 (0.95): lumped`, `#412 below floor 0.9
  (0.71): new intake`, `new (0.93): new intake`, `no answer: new intake`. Its
  `outcome` is written later: when the bug closes, `lumped #412: kept` for a
  Jev lump still on it, and for the `new` that filed it `new: filed #430,
  kept`, or `new: filed #430, closed as duplicate of #412 by @x` (the
  "Duplicate of" comment's author); when a human moves the sighting (`PATCH
  /doctor/sightings/{id}`, below), `lumped #412: moved to #430 by @x` or
  `new: filed #430, moved to #412 by @x` (`no bug` when moved off any; another
  repo's bug as `owner/name#n`). A close writes only an outcome not yet
  written; a move overwrites it. `GET /jev/summary` judges the pick against
  the bug the outcome names (`new` for a filed bug kept).
- **Filing.** A `doctor-failed` sighting classed `rate-limited` (the
  doctor's own Smith hit a usage limit) is recorded and not filed: no Jev
  look, no intake; the next stuck episode gets a new doctor run. So is one
  classed `sandbox-failed` (its sandbox failed before the Smith started). Otherwise
  the sighting claims a `filing` `engine.doctor_bug` in
  the home repo (holding the signature; `lumped_by: new`, with the Jev
  call when there was one) and starts an `intake` there,
  in-engine, on a thread of its own: `submittedBy` `doctor`, and
  `authorizedByEngine` `doctor`, so `openAskItem` takes it as authorized
  by the engine's doctor (`Actor.System("doctor")`, "Authorized by the
  engine's doctor." on its ask item), with no trusted login asked.
  The ask's first line is `Engine defect: <summary>` (`Defect in
  owner/name: <summary>` for `where: target`), then `Kind: bug`, the
  stuck ticket, flow and stuck-since, the sighting's kind, leaves, incident
  lines and signature, its summary and the doctor's diagnosis (cut to 4000
  characters). The intake skips its duplicate check (the signature already
  lumped it), and its `start work` carries the authorization to a `bug` or a `feature`
  (`ProcessFlowStarter.DOCTOR_FROM_INTAKE`, a feature in the refiner's mode)
  with no trusted login: "authorized by the engine's doctor via intake #n"; any other
  kind, and a bug or feature with an open
  blocker (a deferred start is re-checked as a trusted login), waits for a
  person's `authorized`.
  `fileTicket` (or `File unlabeled ticket`) sets the bug's issue and `open`.
  An intake that does not start undoes the claim, so the next sighting tries
  again.
- **A fix merged since** (`DoctorBugs.mergedFix`). Before filing a `where:
  engine` sighting (after Jev's look), the doctor reads the home repo's PRs
  merged since the episode started (the earliest open incident, else the run's
  `stuckSince`; `IssueDetails.mergedPulls`, the 100 merged PRs updated last).
  One whose title or body names the signature's hash (12 characters or more),
  or every activity of its leaves as a whole word (`task-open-pr` for
  `chore:task-open-pr@none`), is its fix (`DoctorBugs.fixOf`, the newest
  merged first). Nothing is filed: the signature goes onto a `fixed` doctor
  bug on the issue that PR closes (the PR's own number when it closes none),
  closed at the merge, the sighting is lumped `merged-fix`, and the run's
  `detail` says "retry the ticket after the next deploy". So "After a fix"
  below holds: a sighting before the engine restarts on the fix is a
  leftover, one after it a regression, which files. A PR read GitHub fails
  is logged and the sighting files as "Filing" says.
- **Repeats** (`DoctorBugRepeats.repeat`). A
  sighting lumped (exact or by Jev) into a filed `open` or `needs-repro` bug
  from a stuck ticket with no sighting on it yet is counted per distinct
  ticket, with the runs beside it, when both repos have `doctorBugs` on. The
  2nd ticket comments on the bug: "Seen again: owner/name#n, stuck at
  `<leaves>` since <time>. Seen on K tickets (R runs)". The 3rd also steps the
  priority in the body's `<!-- engine: intake … · pN -->` trailer up one
  (`Tickets.editBody`), never above `p2`. The 4th also adds
  `needs-review`, and `GET /needs` has a `sighting` row on the bug while it
  stays `open` or `needs-repro` (`review_at`). Every later ticket comments.
  Each step is taken once (`repeat_level`), the same ticket again only counts,
  and a declined bug only counts. A bug with a flow running (a root
  `IssueInstances` finds unended) gets the comment and the priority step,
  never `needs-review` and never a second flow.
- **`needs-repro`.** The bug flow's `label needs repro` makes the issue's
  `open` doctor bug `needs-repro`; the sync below makes it `open` again once
  the label is off the open issue. A `needs-repro` bug hit by a new stuck
  ticket, with no flow running, gets its reproduce restarted: a `bug` flow
  started as the engine's doctor, its ticket the issue with a "Sightings (the
  engine's doctor)" section listing every sighting on the bug. At most twice
  per bug (`restarts`); one that does not start is not counted.
- **After a fix.** A sighting on a bug closed as fixed is a `leftover`
  (`lumped_by: leftover`, counted, nothing written) when its episode started
  before the close, or, for `where: engine`, before the engine's first start
  after the close (`fix_running_at`, set at `ApplicationReadyEvent` to the
  JVM's start time on each fixed bug closed before it that has none yet, and
  again when the sync marks a bug fixed, so a bug closed while the engine was
  down, still open at startup, runs its fix from this start), since the fix was not
  running. The episode's start is the earliest open incident's timestamp
  (Operaton's `incidentTimestamp`) when the stuck position has one, else the
  doctor's `stuckSince`: `stuckSince` is the doctor's first look, which
  restarts with the engine, so an incident raised before a redeploy and first
  seen after it would otherwise read as a regression. Otherwise it is a regression: it files through "Filing" (no Jev
  look) a new bug whose ask's first line is `Regression of #n: <summary>` and
  names the old bug; the new bug takes the signature from the old one and
  sets `regression_of`; the old bug is not reopened. With either repo off, it
  only counts.
- **The stuck ticket's comment.** In a repo with `doctorBugs` on, once per
  episode (the first sighting of the signature on that root flow), when the
  bug is `filing`, `open` or `needs-repro` and its repo has `doctorBugs` on:
  "Stuck at `<leaves>` since <time>; this is owner/name#n (seen on K
  tickets)", or "filing a bug in owner/name" while it refines.
- **Closes.** Every `engine.doctor.bug-sync` (`PT10M`), `DoctorTick.syncBugs`
  reads each repo's newest 100 closed issues once and settles the `open` and
  `needs-repro` bugs among them: closed `completed`, `fixed`; closed `not
  planned` (or `duplicate`) with GitHub's "Duplicate of #k" comment, its
  signatures and sightings move to #k's doctor bug (made `open` when #k has
  none) and it is left `declined`; any other `not planned`, `declined`,
  keeping its signatures. A doctor intake cancelled before it filed
  (`DELETE /intake/{id}`) leaves its bug `declined` with no issue.
- **Moving a sighting.** `PATCH /doctor/sightings/{id}` with `{"bug":
  "owner/name#n" | null}` moves the sighting, its signature and the
  other sightings of that signature on the same bug to that issue's doctor
  bug (made `open` when it has none), or off any bug (the next sighting of
  the signature then gets a Jev look again); they are `lumped_by: human`.
  The token's login needs `bug` access on the repo of the bug it leaves and of the one it
  goes to; a Smith sandbox is refused, and a body with `by` is 400. It
  writes the Jev call's outcome above.

With the stuck ticket's repo off, or the home repo off (for `where: engine`,
no repo marked `engineDefects`), nothing is filed, commented or labelled; the
sighting is recorded as before. While
some repo has `doctorBugs` on and none is marked, `GET /needs` has a
`sighting` row ("What needs you").

`GET /doctor/bugs?since=<iso>` serves every doctor bug whose newest sighting
(or, with none, its creation) is at or after `since` (default 7 days ago; one
that does not parse is a `400`), newest first:

```json
{"since": "2026-09-28T00:00:00Z",
 "bugs": [{"id", "repo", "issue" | null, "state": "filing" | "open" | "needs-repro" | "fixed" | "declined",
           "intakeId" | null, "createdAt", "closedAt" | null,
           "signatures": [{"signature", "where", "leaves": [...], "firstSeen"}],
           "tickets", "runs", "lastSeen" | null}]}
```

**The back-off.** The doctor starts no new run on a place (a root and its
leaves) within `engine.doctor.backoff` (`PT6H`) of the last run there when
the last 3 runs there ended `failed`, or the place's last sighting is lumped
into a doctor bug not yet `fixed`. That bug closed as fixed after the last
run there lifts both, and lets the doctor run again on a ticket still stuck
in an episode it already ran on, so the fix gets a look. It still records the
position each tick, and a moved position or a new incident on a different
place is a new episode as before. A place backed off is a `setting` `doctor`
frame with `state: "backoff"`, once per episode.

**The history** is `engine.doctor_run`, one row per doctor run, written as
it starts (`running`) and finished by "apply the remedy": `applied`, `none`
(`healthy` or `unfixable`) or `failed`; or from outside the run (`DoctorRunEnder`,
below "A doctor run's own incident"): `cancelled` when someone cancelled it, `ended`
when the tick ended it because its ticket closed or moved. `GET
/doctor/history?issueUrl=<url>` (`doctor/DoctorController.kt`) serves a
ticket's runs, newest first (`[]` for none; `400` when the URL is not
`https://github.com/<owner>/<repo>/issues/<n>`). Without `issueUrl`,
`GET /doctor/history` answers the tickets that have runs, one row each, newest
last run first (`{"tickets": []}` for none; `DoctorHistory.tickets`, one query
on `engine.doctor_run`):
`{"tickets": [{"repo", "issue", "runs", "lastStartedAt", "lastOutcome"}]}`.
The `issueUrl` form:

```json
{"issueUrl": "https://github.com/acme/widgets/issues/7",
 "runs": [{"id", "repo", "issue": 7, "rootInstanceId", "position": ["chore:task-open-pr@none", "incident:<id>"],
           "stuckSince", "startedAt", "endedAt" | null, "diagnosis" | null, "remedy" | null, "target" | null,
           "outcome": "running" | "applied" | "none" | "failed" | "cancelled" | "ended", "detail" | null, "smithRunId" | null,
           "sighting": null | {"id", "kind", "where", "bug": null | {"repo", "issue", "state"}, "tickets", "runs", "signature"}}]}
```

`sighting` is the run's sighting: null for a run still going, a `healthy` one,
a `cancelled` or `ended` one and every run that ended before V35. `tickets` and `runs` count the distinct
tickets and the sightings its bug was seen on, or its signature's while it
has no bug. `signature` is the sighting's signature hash
(`engine.doctor_signature`), on a bug or not: the desktop's **Doctor files
bugs** modal counts a repo's distinct defects recorded as the distinct
signatures among its runs' sightings (`observability/desktop/README.md`,
"Config > Repos").

`smithRunId` is the run's `health check` Smith: the `smith-run` process
instance `reusable-smith-run` called (the newest, `DoctorSmithRuns`), whose id
names the run's log in VictoriaLogs, `issue-<issue>-doctor-<smithRunId>.log`
(`smith/RunLabels.kt`, `RunLabels`). "Apply the remedy" stores it in the
row (`engine.doctor_run.smith_run_id`, V28); for a row with none (a run
still going, or one that ended before V28) this read takes it from the
engine's history, and it is null before the Smith started or once that
history is gone. The desktop's board ticket detail reads the
run's transcript by it (`observability/desktop/README.md`, "Doctor").

`GET /doctor/runs?since=<iso>` (#706) serves every ticket's runs that
started at or after `since`, newest first (`DoctorHistory.since`, one query
on `engine.doctor_run`; the runs have the same fields as the `issueUrl`
form's, though `smithRunId` is only the stored one: a run still going has
null here). `since` is an ISO-8601 date-time (`2026-09-21T00:00:00Z`); without
it, 7 days ago; one that does not parse is a `400`. The desktop's Analytics >
Doctor page reads it to classify each run's success
(`observability/desktop/README.md`, "Analytics > Doctor"): an `applied` run
failed when a later run exists on the same `rootInstanceId` with the same
leaves (the position without its incident ids).
The window always reaches now, so every later run that could mark a run in
it is in the same answer, and no second read or new column is needed:

```json
{"since": "2026-09-21T00:00:00Z",
 "runs": [{"id", "repo", "issue", "rootInstanceId", "position": [...], "stuckSince", "startedAt", "endedAt" | null,
           "diagnosis" | null, "remedy" | null, "target" | null, "outcome", "detail" | null, "smithRunId" | null,
           "sighting" | null}]}
```

`engine.doctor.enabled=false` (`ENGINE_DOCTOR_ENABLED`) leaves the tick out,
so nothing is checked and no bug's close is read; the tests run with it off
(`modules/app/src/test/resources/config/application.properties`) unless a test turns it
on.
