# Smith runs

Part of the engine docs: [index](../README.md), [engine/README.md](../../README.md).

The engine starts every Smith itself, and there is no Operator (decided
2026-09-23). The fleet's engine runs as a pod on Kubernetes (`deploy/k8s/`,
"The engine in the cluster" below) and starts each Smith as a `batch/v1` Job
in namespace `frakworks-smith`; the local Compose stack starts it as a
**sibling container** over the Docker socket ("Hosting models"). The desktop
app and interactive sessions run locally and reach the engine over the network. `smith-run.bpmn` is:

    start smith → (smith exited: message) → collect result → remove sandbox → end
                        ▲
        Docker `die` event ┘ ─ or the backstop scan (startup · every 3 min · after a stream reconnect)

`start smith` (`StartSmithDelegate`) creates and starts a container from the
Smith image and remembers its id; the process then waits at a message catch
event (`event-smith-exited`, message `message-smith-exited`), so no engine
job is locked for the hours a run can take and nothing polls. `collect
result` keeps the exit code, the whole log as a bytes variable (`log`) and
its last 3900 characters as text (`logTail`: a String variable lives in a
VARCHAR(4000), which the first live run overflowed); `remove sandbox` frees
the container in the next transaction, so a failed commit never loses the
log. `SmithSandbox` is the one interface between the process and whatever
runs the Smith: `SmithSandboxes` routes it to Docker (`DockerSandbox`) or to
the k3s cluster (`KubernetesSandbox`), see "Hosting models" below; tests
substitute a fake. What follows describes the Docker model; the Kubernetes one
does the same through the cluster's API.

**How the exit arrives** (`SmithExitNotifier`, `smith/SmithExit.kt`). Two
ways, and they collect exactly once:

- **the event**: `DockerSandbox.watchExits` subscribes to Docker's event
  stream (`/events`, filtered to container `die` events carrying the
  `frakworks.smith.run` label, on a second HTTP client with no response
  timeout, since the stream stays open for as long as the engine runs). A
  `die` carries the exit code and the run id from the label, and the
  container id in `Actor.ID` (`exitOf`): the top-level `id` is a legacy
  field a current daemon omits, and reading it alone dropped every exit
  on 2026-09-25, leaving each run to the backstop; the notifier
  correlates `message-smith-exited` to that process instance with `exitCode`
  as a message variable, and the run goes on at once. The stream is reopened
  with a capped backoff when it drops (daemon restart), and a scan runs after
  each reopen for anything that died in the gap;
- **the backstop**: `scanExits` lists every run waiting on the message, asks
  Docker for each sandbox's status, and delivers for those already exited (a
  container Docker no longer knows reads as exited with code -1, never as a
  run that waits forever). It runs at **startup** (exits while the engine was
  down), every **`engine.smith.exit-scan-interval`** (3 min; an event the
  stream dropped), and after a **reconnect** — the issue scan's startup /
  wake / interval shape. `engine.smith.exit-scan.enabled=false` turns the
  subscription and the scans off (the tests do, and scan by hand).

Exactly once follows from Operaton: a waiting run holds one message event
subscription, and the first correlation consumes it in its own transaction.
The other delivery then finds nothing to correlate
(`MismatchingMessageCorrelationException`) or loses the row
(`OptimisticLockingException`); both are ignored, so an event and a scan
racing on one exit yield one `collect result` and one `remove sandbox`. A
`die` that lands before the start job's transaction has committed its
subscription (a container that dies within a moment of starting) is retried
a few times over about a minute, then left to the next backstop scan.
`engine.smith.poll-interval` is gone; there is no poll.

`engine.smith_run_stats` has one row per run from `start smith` on (since V67,
#1966: root, wrapper, mode, model, effort, tier, account, start), so the ticket
timeline can place a running run; `ended`, `exit_code` and what collect records
are null until then, and every reader of collected runs (`read_ticket_runs`'s
`stat`, the stage estimates' durations) filters on `ended`.
`collect result` also records what the sandbox used: `run-smith.sh` copies
its cgroup v2 `memory.peak` to `/work/out/memory.peak` on exit, and collect
sets `peakMemoryBytes` (null when unread) and finishes the run's row in
`engine.smith_run_stats` (end, exit, turns, duration, result, outcome, the
question its result.json asked, peak,
inspect's `OOMKilled`, since V56 `evicted` (the cluster evicted or preempted
the pod; null where unknown), and since V17 the run's `mode`: its role, `work`,
`review`, `design`, `job` (a `callable-smith` run on a prompt, with no issue:
"Custom jobs and the SDK") and so on, as the flow's call activity passed it; rows from
before V17 took it from `smith_session` where there was one), and since V34
`scope_paths`: how many paths and globs the run's ticket's Allowed scope grants
(`AllowedScope.grantCount` of the `approvedTicket` variable; null for a run with
no ticket, such as an intake's refiner, and for older rows), the scope signal of
the stage estimates. It logs `run <id>: peak <MiB> MiB, <s> s, exit <n>`.
Neither ever fails the collect. Measurement only: no admission gate.

It also reads what the Smith cost from Claude's closing stream-json `result`
event (`closingResult`: the last log line that parses as one; the collect
reads the log's last 5000 lines, and that event is among the last):
`total_cost_usd` (a Double), `num_turns` and `duration_ms`. Each is null when
the event or its field is missing or garbled, which changes neither `result`
nor the exit handling. They go to the run's rows, never to a process variable
(#1976): turns and duration to `engine.smith_run_stats`, cost and duration to
`engine.claude_usage` ("Consumption ledger"); the collect holds them in hand
(`SmithRunRecords.ResultStats`) for the live-tally log line and the ledger
row it writes in the same command. The model, effort and tier the run was
started with are likewise read back from the row `start smith` wrote, by the
collect's Fable and session limit checks, its rerun decision and its `session`
`ended` event. `overflowHit` and `fableLimitHit` read the same event.

**One home per value.** Every value the ticket timeline reads has exactly one
place it is written (#1962, question 2; #1976). A process variable stays one
only where the flow itself branches on it or hands it to its caller; the rest
live in the engine's schema, and nothing sets them as variables any more
(`TimelineBackfill` alone still reads the former variables from Operaton's
history, by name, for tickets worked before #1966).

| Value | Home | Changes after first written |
|---|---|---|
| `mode` | process variable: the flow's call activity passes it; `start smith` resolves the tier from it, `read result` dispatches on it | no (per run) |
| `tier` | process variable: the approved ticket's tier, read by `start smith` (every mode but `security` and `refine`); `engine.ticket_flow.tier` mirrors it for the timeline | yes: a replan or an amendment can re-tier the ticket |
| `runModel` | `engine.smith_run_stats.model` (and `engine.smith_session.model` for the live transcript), written by `start smith` | no |
| `runEffort` | `engine.smith_run_stats.effort`, written by `start smith` | no |
| `runTier` | `engine.smith_run_stats.tier`, written by `start smith`; the stage estimates read it for a flow with no `tier` of its own | no |
| `cost` | `engine.claude_usage.cost_usd`, written by `collect result` | no |
| `turns` | `engine.smith_run_stats.turns`, written by `collect result` | no |
| `durationMs` | `engine.smith_run_stats.duration_ms` (and `engine.claude_usage.duration_ms`, the same figure for the ledger's window), written by `collect result` | no |
| `result` | process variable: the run's hand-off to its caller (`success`, `failed`, `no-result`); `engine.smith_run_stats.result` mirrors it | no (per run) |
| `output` | process variable: the run's hand-off to its caller (its result.json); an intake root's copy goes to the projection for the timeline | no (per run) |
| `reviews` | gone: `engine.smith_run_stats.review_no`, 1 + the highest under the root, written by `read result` and used to number the findings in the same step | no |
| `approvedTicket` | process variable: `start smith` builds the brief from it; the flow branches on its Allowed scope | yes: an amendment rewrites it |
| `filedTicket` | process variable: the intake's hand-off, the ticket flow starts from it | no |
| `ciCheck` | process variable: `read check` reads which check the wait was for; `engine.ci_wait.check` records each wait | no |
| `ciConclusion` | process variable: `read check` turns it into `ciOutcome`; `engine.ci_wait.conclusion` records each wait's | yes: every check run rewrites it |
| `ciOutcome` | process variable: the flow branches on it at `gateway-ci-passed` and the `reusable-ci-check` gateways (`waiting`, `rerun`, `failed`, `conflict`); `engine.ci_wait.outcome` records each wait's | yes: every check run rewrites it |
| `outcome` | process variable: the flow branches on it at `gateway-work-outcome`, `gateway-security-passed` and `gateway-review-outcome` after `read result`; `engine.smith_run_stats.outcome` carries the run's own | yes: every run rewrites it |
| `repo`, `issue` | process variables: the flow's identity, every delegate reads them; `engine.ticket_flow` and every run row carry them | no |

A row with two homes names one writer and one mirror the projection keeps for
its reads; no value is written to two places by two writers.

**The Smith image** is `engine.smith.image` (`ENGINE_SMITH_IMAGE`). By
default it is `frakworks-smith:main`, the registry-less tag of the local build
(`scripts/local-up.sh`, `engine/compose.build.yaml`, "Images"), used as it is.
With `FRAKWORKS_REGISTRY` set in `.env`, `compose.local.yaml` sets it to
`$FRAKWORKS_REGISTRY/frakworks-smith:main`, published on every merge to `main`
(the examples below write the registry as `registry.example:5000`). Before it creates a sandbox,
`DockerSandbox.start` runs `ensureImage`:

- **missing locally**: pulled. A pull that fails (registry unreachable, tag
  missing) or takes longer than `engine.smith.image-pull-timeout` (default
  `PT5M`) throws `smith image <ref> could not be pulled: <cause>`, so
  `start smith` fails into an incident (after its `R3/PT30S` retries) instead
  of hanging;
- **present under a mutable tag on a registry** (`:main`, anything but
  `:sha-<7>` or `@sha256:`): re-pulled when it was last pulled
  `engine.smith.image-refresh-interval` or longer ago (default `PT5M`; `PT0S`
  pulls before every run; the first run after an engine start always
  refreshes). So a newly pushed `:main` is what the first run started after
  the interval gets, with no manual step. A refresh that fails is a warning
  (`smith image <ref> refresh failed, using the local copy`), and the run goes
  on with what is there; the next attempt waits for the interval again;
- **pinned** (`:sha-<7>`, `@sha256:`) or **registry-less**
  (`frakworks-smith:main` of the local build): used as it is, never refreshed.

Pulls go through a third docker-java client with no response timeout (a large
layer outlasts the 60 s one); the timeout above is what bounds them. The
registry is plain HTTP with no auth: the Docker daemon the engine talks to must
list `registry.example:5000` under `insecure-registries` (the engine host's
Colima does).

A pull never touches a run in progress: Docker resolves the reference to an
image id when it creates the container, and the container keeps that id
whatever `:main` points at later. **Which build a run used** is in the engine's
log, on the line every sandbox start writes:

    smith sandbox started: container=… name=smith-<issue>-<run8> issue=#<n> run=<id> image=registry.example:5000/frakworks-smith:main digest=registry.example:5000/frakworks-smith@sha256:…

(`docker compose logs engine | grep 'smith sandbox started'`). `digest` is the
image's RepoDigest for the configured repo, the image id for a local build
that has none, or `unknown` when Docker could not say (which never fails the
run). Match it against the registry with `docker buildx imagetools inspect
registry.example:5000/frakworks-smith:sha-<7>`.

**Transcripts and the live-run gauge** (`SmithTelemetry`,
`smith/SmithTelemetry.kt`). The desktop's transcript pane
(`observability/desktop/src/transcript-data.js`) reads a live Smith from
VictoriaLogs and VictoriaMetrics, which the Operator's Alloy pipeline fed until
2026-09-24; the engine feeds them now, to the same contract. `start smith`
hands the started sandbox to `SmithTelemetry`, which follows its **stdout**
(`SmithSandbox.follow`: `docker logs --follow`, stdout only, on the stream
client with no response timeout; `run-smith.sh` and the image are unchanged)
for as long as the container runs:

- **each stdout line is one VictoriaLogs row**, posted to
  `<logs-url>/insert/jsonline` (`_stream_fields=issue,host,filename`) while the
  run goes on, in batches of what has queued, from a thread of the run's own.
  `_msg` is the raw line (Claude's stream-json event, or any other line
  `run-smith.sh` prints); `_time` is when the engine read it, strictly
  increasing within a run. The fields: `issue` (the ticket number), `host`
  (the engine instance that follows the run: its hostname, a pod's name;
  "Replicas and the lease"), `filename` (`issue-<n>-<mode>-<runId>.log`: the
  run id is the `smith-run` instance's id, so one value per run, the same on
  every row of it, and the newest filename is the newest run), `phase` (the
  run's mode, `work`, `refine`, `review`, `security`, `propose`, `job`, ...: the
  Operator's phases are gone; a `job` run has an empty `issue`, a `job` field
  with its job id and a `job-<jobId>-...` filename), `model` (the model id the run resolved to, as
  passed to `claude --model`), plus `repo` and `run` (the run id);
- **`smith_run_active{host, issue, model, kind, repo} 1`** is served on
  `GET /metrics` (Prometheus text) for as long as the run is live, and
  `observability/vmetrics/scrape.local.yml` scrapes it every 15 s (job
  `engine`). `host`, `issue`, `model` and `repo` are the rows' values (a mode
  `job` run's series has an empty `issue` and a `job` label with its job id;
  a ticket run's series carries no `job` label); `kind` is
  `security-review` for a `security` run, `review` for a `review` run, `job`
  for a committing run (`work`, `propose`), else the mode itself. `/metrics`
  also serves `smith_fleet_last_scrape_timestamp_seconds{host}`, always.

The same live runs, counted per account and model, are `running` on each row
of `GET /accounts` ("AI accounts"). The account is the run's (`billedAccount`)
and is on neither the rows nor the series: `smith_run_active`'s labels and
values are as above.

**How the series ends.** The run leaves `/metrics` when its followed stream
ends and Docker says the sandbox is no longer running (the exit; a stream
that drops while the sandbox runs is followed again from now, up to five
times), or at `collect result`, whichever comes first; the rows still queued
get up to 10 s to post first, so the pane's last poll sees them. The next
scrape no longer finds the series, and VictoriaMetrics writes a staleness
marker for it, after which `smith_run_active{issue="<n>"}` returns **no
series** (not a `0`: the reader treats any series as live). Bound: **within
30 s of the sandbox's exit** (10 s drain + one 15 s scrape interval, plus the
scrape's own latency), and at the latest one scrape after `collect result` if
the stream never reported the exit. An engine that is down is a failed scrape,
which marks all its series stale the same way. A run that was live across an
engine restart is followed again from now at startup
(`SmithTelemetryResume`), under the same filename, so nothing is shipped
twice; what the Smith printed while the engine was down is not shipped (it is
still in `log` at collect).

**Configuration.** `engine.telemetry.logs-url` (`ENGINE_TELEMETRY_LOGS_URL`,
VictoriaLogs' base URL; empty: no rows shipped), `engine.telemetry.metrics.enabled`
(`ENGINE_TELEMETRY_METRICS_ENABLED`, default true; false: `/metrics` answers
404) and
`engine.telemetry.timeout` (one post's connect and request timeout, 5 s). The
engine needs no VictoriaMetrics URL: VictoriaMetrics pulls, so an unset or down
VictoriaMetrics cannot reach a run at all, and not scraping `/metrics` is how
the gauge is off. With both off nothing is followed. `compose.local.yaml` sets
`http://victorialogs:9428` and `host=engine` for the local stack. No
credential: both endpoints are the stack's own, unauthenticated.

**The session in the database.** The same follow also hands every stdout line
to `SmithSessions` (`smith/SmithSessions.kt`), which stores it in
`engine.smith_session_line` as the run goes on, whatever the two settings
above say: with both off the sandbox is still followed for this. It is what the
desktop's Summarize button summarizes; see "Summarize: a live run summarized".

**Shipping never touches a run.** The calls from `start smith` and `collect
result` are wrapped and only log; lines are queued without blocking (a full
queue of 10 000 drops lines) and posted from their own virtual thread; a
VictoriaLogs that is unset, unreachable, slow or answering an error costs the
batch, logged at WARN once per failing streak, never a failed job, a retry or
an incident. `log`, `logTail` and `peakMemoryBytes` are set by `collect result`
from the container's own log exactly as before, and the run's rows get its
cost, turns and duration from it ("Smith runs").

**Recovering from a restart mid-start.** The sandbox container's name is
derived from the run id (`smith-<issue>-<runId prefix 8>`, `DockerSandbox.start`),
so if the engine dies between `createContainerCmd` and `startContainerCmd` --
say, the engine container itself is recreated -- the orphan is left behind in
Docker state `Created` and a job retry's `createContainerCmd` against the same
name 409s (`Conflict. The container name ... is already in use`, seen live
2026-09-25 on chore #99). `DockerSandbox.start` now looks up that name before
creating anything (`reclaim`): a **non-running** match (`Created`, `Exited`)
is that orphan -- logged and removed, then create/start proceeds as normal. A
**running** match is adopted (its id returned, no second container created)
rather than failed: the name is unique to this run, so a running container of
that name can only be this run's own sandbox from an attempt that actually
started before the engine died mid-job, and creating a second one under the
same name would just 409 again. `task-start-smith` also carries
`operaton:failedJobRetryTimeCycle` (`R3/PT30S`, `smith-run.bpmn`) instead of
Operaton's default of three immediate retries, which burned all three in under
a second in that same incident and left it as an unretried incident instead of
giving the orphan check above a chance to run.

**Cancel and the sweep.** `DELETE /intake/{id}` (the desktop app's Cancel in
the issue detail) cancels a root flow of any kind. The engine's side comes
first, so a failed GitHub write never leaves a flow running. Deleting the
instance deletes the collect/remove steps that would clean up its sandbox, so
before it deletes it stops (10 s grace) and removes the sandbox of every active
`smith-run` under that instance (`sandboxHandle`), logging `run <id>: sandbox
<id> stopped on cancel`. Its questions still unanswered are withdrawn (answered
`cancelled: <reason>` by `by`), so they leave "needs you" and `GET /questions`. A flow seconds old may
still be writing the rows the cancel deletes: each flow's engine-side step is
retried on an optimistic-locking conflict, 4 attempts 500 ms apart
(`FlowCanceller.STOP_ATTEMPTS`), and GitHub is written only after it succeeds;
when every attempt conflicts, the cancel fails as before with nothing written.
A flow whose ticket is already closed (a stray second flow on a merged
ticket, say, parked on an incident) is cancelled the same way, but its issue
keeps its state, state reason and labels: the cancel only comments, noting
that the ticket was already closed and is left as it is. That is how to clear
such a stray incident: `act_on_incident` `cancel` by ticket or incident id, or
`DELETE /intake/{id}` with the stray flow's instance id. A split feature's
retried child filing that reuses a child it already filed never starts such a
second flow: it parks on the child's running flow, or, when the child is
already closed, on the closed child, which the scan hands to the child-closed
path.

An intake cancelled after it filed (its `ticket` set, say parked at `start
chore/bug/feature if authorized`) is stopped, its questions withdrawn and the
reason recorded, but its issue is not closed: the intake rewrote it into the
ticket (one issue per ask), and the comment says the
ticket stays open and that the kind flow is cancelled by its own id. An intake
cancelled before filing closes its issue as not planned, as any flow does.

A split parent's cancel takes its open children with it: each filed child not
yet merged or satisfied has its running flow stopped the same way, its
`deferred_issue` row dropped, a comment naming the parent, and is closed as
not planned; the token's login must be trusted first (403, before
anything is stopped). The parent's own `deferred_issue` row goes too. Finished
children and their merged PRs stay; drafts not yet filed are dropped. Cancelling
a child alone leaves the parent parked, as a child closed without a merge
does ("Split features").

Then GitHub: an open PR the flow opened itself is commented and closed
unmerged (a split parent's `pr` is a child's, never closed here). The issue
gets one comment, `@by cancelled this <kind> (<id>): <reason>`, saying what
was stopped and closed and what stays, and is closed with `state_reason`
`not_planned` (a bare `smith-run` gets the comment only). Nothing else is
undone: the branch `smith/issue-<n>` and its commits stay, a merged PR stays
merged, and comments, labels and filed tickets stay. Each failed write is
logged (`cancel: <write> failed`) and named in the response's `failedWrites`.

Nothing starts again on its own: the scan reads only open issues, and the
approval it acted on is handled. Reopening the issue and adding `authorized`
again starts a new run, unless the cancelled one had opened a PR ("one run at
a time, and none after a PR"); file a new issue for that. For anything that still slips through (a race, a bug,
a container started outside a tracked instance) `SmithSweep` runs every 10
minutes: each `smith-<issue>-<runId prefix>` container whose 8-character run id
prefix matches no active `smith-run` instance is removed if exited, or stopped
and removed if it has run more than 5 minutes; each is logged with its name and
reason. Other `smith-*` names are left alone.

**The sandbox** (`smith/`): Node, Claude Code, git, gh, python3, user `smith`,
no Docker socket, no credential but its ticket's repo's token and its AI account's. It carries the
desktop's `node_modules` in `/opt/desktop-deps`, installed at image build by `npm ci`
(`ELECTRON_SKIP_BINARY_DOWNLOAD=1`, no Electron binary) from `observability/desktop/package-lock.json`
through the build context `desktop`; `run-smith` copies them into the clone's `observability/desktop/`,
so `npm test` there runs with the network off (when the clone's lock file differs from the image's it
still copies them and logs one line saying so). A change to the desktop's `package.json` or
`package-lock.json` on `main` rebuilds the image ("Images"). It also carries JDK 21 and Gradle (same tag as
`engine/Dockerfile`'s build stage), the engine's dependency cache in `/home/smith/.gradle` (the image build
copies only the Gradle build files from the build context `engine` and runs `gradle resolveDependencies`;
no sources), and `smith/gradle.properties` as that home's `gradle.properties`, which overrides
`engine/gradle.properties` in the sandbox (a 1 GiB Gradle heap, the Kotlin compiler in-process, no parallel,
one worker, one test fork). The prompt tells a frakworks Smith to run the test class(es) it changes before
each round, `cd engine && gradle :engine-<module>:test --tests <Class> --no-daemon --offline`, and never the
full `gradle build` or `gradle test`: the engine check builds and tests the pushed branch in full on the
build runners ("Chore", `wait for the engine check`), and a failure comes back to a work run as a `ci`
finding. In a runner outage, `engine.ci-check.enabled=false` (`ENGINE_CI_CHECK_ENABLED`) turns the check
off so no flow waits on one that cannot run; Smiths keep running their test classes. Tool permissions come from `smith/settings.json`, the fleet runner's shape:
Bash and the edit tools allowed, `git push`, `gh pr create` and `gh pr merge`
denied (the engine pushes), nothing waiting on a human. Its entrypoint
`run-smith` clones the repo, checks out `smith/issue-<n>` (the PR's branch
from round 2 on), and runs one `claude -p` on the issue; what comes back is the
exit code, the log, and the files in `/work/out` (below). `SMITH_DRY_RUN=1` prints the
plan and exits 0, which is what the end-to-end check below uses.

**Framework docs come from the image, not the clone.** `smith/Dockerfile` copies frakworks' `bots/smith.md` and `bots/model-choice.md`
to `/etc/smith/framework/`, from a second named build
context, `framework` (`bots/`: `engine/compose.local.yaml`'s `additional_contexts`,
and `--build-context framework=bots` in `.github/workflows/images.yml`), and every
prompt reads them there, so a Smith in any target repo has them and one in
frakworks reads the same copy as everyone else. The image is rebuilt from `main`
when any of the three changes. Every prompt also says: read the repo's
`AGENTS.md` if it has one; the repo's `CLAUDE.md`/`AGENTS.md` bind on how to work
in the code, and the prompt binds on everything that reaches GitHub. Two lines
are frakworks-only (`SMITH_REPO` = `codefrak-oss/frakworks`): the Gradle/`engine/`
build rule above (another repo is told what the sandbox has, and that its CI is the
test), and the fix and diagnose prompts' "the engine's source is in this clone"
(another repo's are told to clone frakworks to `/tmp/frakworks`). The prompts'
tests render them for both: `python3 -m unittest discover -s engine/smith/tests`.
Every prompt says to draw a visual the user would want (a mock-up, a layout, a
diagram) as inline SVG, never to report that a screenshot cannot be produced nor
to give a text mock-up instead: #724's design Smith did the latter.

Per run the engine hands it: `SMITH_REPO`, `SMITH_ISSUE` (or, for a mode `job`
run, `SMITH_JOB`, the job id: "Custom jobs and the SDK"), `SMITH_ID` (the
process instance id), `SMITH_MODEL`, `SMITH_EFFORT`, `GH_TOKEN` (the ticket's
repo's token and no other; a repo without one starts no sandbox, and the start fails into an incident;
"Each repo's token", below), `SMITH_GIT_NAME` and `SMITH_GIT_EMAIL` (that token's account, who the
Smith's commits are by; not on a dry run), for a refine or a work run `SMITH_GH_SCOPES` (that token's scopes, never the
token: "The token's scopes", below), the picked AI account's credential as
`CLAUDE_CODE_OAUTH_TOKEN` with `SMITH_ACCOUNT` ("AI accounts"; a dry run may
have none). Nothing is mounted on `/home/smith/.claude`: the login is the
token, and Claude Code keeps its session state there, including the summary
it writes when it compacts a long conversation. Until 2026-09-25 a shared,
root-owned `smith-claude-home` volume sat on that path; the Smith could not
write to it, every auto-compaction failed with `EACCES`, and long work runs
died with `Prompt is too long` (#243, and the overflows listed under "Overflow
re-entry"). The directory is now the container's own and goes with it.
The sandbox meters its own Anthropic calls ("The usage meter"). Memory is
capped at 3 GiB (`engine.smith.memory-bytes`), all capabilities dropped, `no-new-privileges`. Settings:
`engine.smith.*` in `application.properties`, overridable as
`ENGINE_SMITH_*` environment variables (see `compose.local.yaml`).

**Memory and CPU safety for the one-VM stack** (decided 2026-09-25: no engine-wide
Smith slot limit). The per-sandbox cap (`engine.smith.memory-bytes`, above) is
3 GiB so a Smith could run the engine suite (`gradle test`) in its sandbox; at
2 GiB the OOM killer took the Gradle daemon (2026-09-25). Since #353 the suite
runs on the build runners instead (the engine check), and a sandbox runs one
test class at a time ("Smith runs", above). The local stack's one
VM has 4 CPUs / 5.8 GiB on Docker Desktop; `engine` and `engine-db` in `compose.local.yaml` carry
their own `mem_reservation`/`mem_limit` (1g/2g and 256m/1g) so they aren't
starved by sandbox memory pressure. Under an actual squeeze the kernel should
kill a Smith first: `DockerSandbox` sets each sandbox's `oomScoreAdj` to `+500`
while `engine` and `engine-db` set `oom_score_adj: -500`, moving both ends of
the -1000..1000 range away from the default 0. With `engine` at 2 GiB and
`engine-db` at 1 GiB, 2.8 GiB is left for sandboxes, so two concurrent Smiths
at 3 GiB do not fit the VM if both use their cap; that OOM preference, not the
arithmetic, is what protects `engine` and `engine-db`. Concurrent-Smith count
is unbounded; the engine enforces no slot count.

CPU is capped the same way. On 2026-09-26 five concurrent Smiths on the 4-CPU
VM, one running `gradle test` at ~270% CPU, stalled the engine 81 s (Hikari
thread starvation, `/health` timed out, a wake poll failed). Each sandbox is now
limited to `engine.smith.cpus` CPUs (default 2, set as Docker `NanoCpus` by
`DockerSandbox`); `engine` and `engine-db` set `cpu_shares: 4096` against the
default 1024 a sandbox gets, the CPU counterpart of their `oom_score_adj`; and
the Smith image runs Gradle with `org.gradle.workers.max=2` (`GRADLE_OPTS`).
Still no limit on the number of concurrent Smiths.

There is no endpoint that starts a bare Smith run (`POST /smith-runs` was
removed 2026-09-23: it would run a Smith on any repo and
issue, unauthenticated, and nothing called it). A run happens inside an
intake; to see one, `/test-intake` from a Claude session, or, against the
local Compose stack (on the cluster, the same calls go to the engine's Ingress):

```bash
docker compose pull engine
docker compose up -d engine                       # http://localhost:8090
curl -H "Authorization: Bearer $FRAKWORKS_TOKEN" -s -X POST localhost:8090/intake -H 'content-type: application/json' \
  -d '{"ask":"...","dryRun":true}'                # files real GitHub issues
curl -H "Authorization: Bearer $FRAKWORKS_TOKEN" -s localhost:8090/intake/<intakeId>
```

## The ride-out proxy

Only the lease holder takes traffic ("The engine in the cluster"), so for a
few seconds of a handover the engine has no ready endpoint: a
connection is refused or answered 503. Every client rides that out instead of
failing:

- **A Smith**: `run-smith.sh` starts `engine/smith/engine-proxy.py` on
  `127.0.0.1:8789` when the engine gave an `ENGINE_URL`, and hands the proxy's
  URL to `claude` as `$ENGINE_URL` and to the MCP config
  (`frakworks-mcp-config.sh`). The proxy forwards every request verbatim and,
  while the engine refuses the connection or answers 503, retries it with a
  0.5, 1, 2, 4 s (then 4 s) backoff for up to 30 s; any other answer, an
  event stream included, streams straight back. The connection is still the
  sandbox's own, so the engine knows the run by its address as before.
- **The desktop app and the web version**: `getJson`/`sendJson`
  (`observability/desktop/src/engine-data.js`) retry once after 500 ms on
  502/503 or a refused connection; the event stream reconnects after 1, 2,
  then 4 s and takes a fresh snapshot on every reconnect.
- **The iPhone app**: `EngineClient` retries once on 502/503 or
  `cannotConnectToHost`; a message post carries a `clientId`, so a retry the
  engine already stored is not stored twice.
- **The ops broker** (or any other HITLP client): treat a refused
  connection and a 503 as retryable, with a short backoff for up to about
  30 s, never as a failure of the request. `human.ask` and `human.approve` are
  idempotent by `idempotencyKey` (the same key returns the same task), and
  `tasks/get` is a read, so a retry of either is always safe.

## Hosting models

A Smith runs in one of two places, chosen engine-wide by the `smith.hosting`
row of `engine.engine_setting` (V51 seeds `docker`, for the Compose stack; the
engine on Kubernetes runs `kubernetes` only):

| | `docker` (default) | `kubernetes` |
|---|---|---|
| sandbox | a sibling container over the Docker socket (`DockerSandbox`) | a `batch/v1` Job on the k3s cluster (`KubernetesSandbox`) |
| handle | the container id | `k8s:<namespace>/<job>` |
| inputs | copied into the created container | untarred over `exec` into an init container's emptyDir at `/work/in` |
| `/work/out` | read from the exited container | an emptyDir a `keeper` sidecar holds, read over `exec` until the Job is deleted |
| exit | Docker `die` events | a pod watch: the `smith` container terminated |
| logs, OOM | `docker logs`, `State.OOMKilled` | the pod log API, `terminated.reason = OOMKilled` |
| stop, remove, sweep | stop and remove the container | delete the Job, its pod and its env Secret |
| `runAt` (who calls) | the container's IP on the Smith network | the running pod's IP, from its annotations |

`SmithSandboxes` (the `SmithSandbox` bean) starts a run where the setting says
at that moment and routes every later call by the handle, so a run keeps its
model when the setting moves. The exit backstop, the sweep (`SmithSweep`) and
`runAt` cover both. A Kubernetes run is the same sandbox as a Docker one: the
same image and env (`sandboxEnv`), non-root as uid 65533, every capability
dropped, no privilege escalation, the runtime's default seccomp profile, no
service-account token, no service links, memory and CPU capped at
`engine.smith.memory-bytes` and `engine.smith.cpus`. The env, with the repo's
token and the AI credential, is a Secret of the run's own (`<job>-env`), never
in the Job's spec. The Job never retries (`backoffLimit: 0`) and is killed past
`engine.smith.k8s.active-deadline` (24 h).

**Burstable pods and eviction** (#1428). Those caps are the pod's limits; its
requests are smaller, `memoryRequestBytes` and `cpuRequest` of the settings
below (1 GiB and 0.5 CPU, seeded by V56), so the scheduler packs Smiths by what
they usually use (median peak 225 MiB, p95 about 1 GiB) and the namespace's
ResourceQuota admits many more at once (`deploy/k8s/README.md`). Every Smith
pod is in PriorityClass `frakworks-smith`, the lowest: under node pressure the
kubelet evicts Smiths before the engine and Postgres (`frakworks-core`, memory
requested at its limit). `KubernetesSandbox.evicted` reads an evicted pod (status
reason `Evicted` or `Preempting`, or a true `DisruptionTarget` condition with
reason `EvictionByEvictionAPI`, `PreemptionByScheduler` or `TerminationByKubelet`;
an OOM kill is never one); `collect result` writes it to
`engine.smith_run_stats.evicted` and reruns the step (see "Incidents"). Docker
never evicts and ignores the requests.

**Settings**, all in `engine.engine_setting`, through the API:

    curl -H "Authorization: Bearer $FRAKWORKS_TOKEN" -X PUT $ENGINE/settings/smith-hosting -H 'Content-Type: application/json' -d '{
      "namespace": "frakworks-smith",
      "image": "registry.example:5000/frakworks-smith:main",
      "engineUrl": "http://192.0.2.10:8090"}'
    curl -H "Authorization: Bearer $FRAKWORKS_TOKEN" -X PUT $ENGINE/settings/smith-hosting -H 'Content-Type: application/json' -d '{"hosting": "kubernetes"}'

Where the desktop's First mate runs is a setting of its own,
`firstmate.hosting` (`GET`/`PUT /settings/first-mate-hosting`, "First-mate
sessions"): its `kubernetes` is refused until `smith.hosting` is `kubernetes`
with `namespace`, `image` and `engineUrl` set, since a First mate session pod
runs on those settings.

`GET /settings/smith-hosting` answers all six: the four strings, and
`memoryRequestBytes` and `cpuRequest`, numbers a PUT sets the same way (rows
`smith.k8s.memory-request-bytes` and `smith.k8s.cpu-request`, no property). A
request that is not positive, or above its limit (`engine.smith.memory-bytes`,
`engine.smith.cpus`), is a 400 naming the field. `kubernetes` is refused while
`namespace`, `image` or `engineUrl` is blank; `{"hosting": "docker"}` switches
back. `image` is the Smith image as the cluster's nodes pull it (the LAN
registry by an address they resolve, not `localhost:5000`; a `:main` tag is
pulled `Always`, a pinned one `IfNotPresent`). `engineUrl` is the engine as a
pod reaches it, the Smith's `$ENGINE_URL`: the engine host's LAN address and
its published port, which is also how the Smith reaches its MCP servers (the
frakworks server and the engine-served ones).

The desktop app's Config > frakworks > **Smith hosting** shows the current
model and switches it (`PUT` with `hosting` alone; observability/desktop/README.md,
"Config > frakworks"). The Kubernetes settings are set with the API above.

**The engine in the cluster** (`deploy/k8s/base/`):
the fleet's engine runs as a pod in namespace `frakworks`; Compose stays the
system-in-a-box model for local work. It knows it is in a pod by the
ServiceAccount token the kubelet mounts at
`/var/run/secrets/kubernetes.io/serviceaccount/token` (`InCluster`; no
property). Then:

- **the client** is built from that ServiceAccount (`frakworks:frakworks-engine`,
  fabric8's in-cluster config), and `engine.smith.k8s.kubeconfig` is not read
  and is never in the missing list. Outside a pod the kubeconfig is read as
  below.
- **Docker hosting is refused**: there is no Docker socket in a pod, so `PUT
  /settings/smith-hosting {"hosting": "docker"}` is a 400 saying Docker
  hosting is not available in a cluster. A `docker` row at startup (a restored
  Compose database) logs one WARN line that no Docker run can start; the engine
  starts, and a run started meanwhile fails naming the setting, not a socket.
- **`engineUrl` is the Service URL**, `http://engine.frakworks.svc:8080`: a
  Smith pod reaches the engine by name, and the NetworkPolicy selects it by
  namespace, not by address. A pod's source IP is its own, so `runAt` needs no
  route or masquerade rule.

**Setting up k3s** (once):

1. Apply the base, through an overlay with the site's values
   (`deploy/k8s/README.md`): the two namespaces, the engine's ServiceAccount,
   Role and RoleBinding, the Smith quota and the NetworkPolicies.
2. Let the nodes pull the Smith image: k3s reads `/etc/rancher/k3s/registries.yaml`
   on each node; a plain-HTTP LAN registry needs a `mirrors` entry for it there.
3. *Only for an engine outside the cluster* (Compose plus Kubernetes runs):
   write its kubeconfig from a token for the ServiceAccount, created with
   `kubectl` (`deploy/k8s/README.md`, "An engine outside the cluster"):

       TOKEN=$(kubectl -n frakworks get secret frakworks-engine-token -o jsonpath='{.data.token}' | base64 -d)
       kubectl config --kubeconfig engine/kube/config set-cluster k3s --server https://<k3s-host>:6443 \
         --certificate-authority <k3s ca.crt> --embed-certs
       kubectl config --kubeconfig engine/kube/config set-credentials frakworks-engine --token "$TOKEN"
       kubectl config --kubeconfig engine/kube/config set-context smith --cluster k3s --user frakworks-engine --namespace frakworks-smith
       kubectl config --kubeconfig engine/kube/config use-context smith

   `engine/kube/` is gitignored; `compose.local.yaml` mounts it read-only at
   `/etc/frakworks/kube` (`engine.smith.k8s.kubeconfig`). The kubeconfig, like
   every credential, never goes in git. Without it no Kubernetes run starts,
   and Docker runs go on.
4. *Only for an engine outside the cluster*: let the engine see a pod's own
   address: the frakworks server, `POST /intake` and the other Smith-only
   calls know a Smith by its source IP (`runAt`). Traffic from a pod to the
   engine host must not be masqueraded to the node's address: exempt the
   engine host from k3s' egress masquerade and route the cluster's pod CIDR
   (`10.42.0.0/16` by default) to the nodes on the engine host. Masqueraded,
   every call is from a node, matches no pod, and is refused as "not a Smith",
   never taken for another run. The base's Smith NetworkPolicy allows the
   engine Service only; the overlay adds the outside engine's address.
5. Set the settings above, then `{"hosting": "kubernetes"}`.

## The check mode

A post-deployment check (#100007, part B in #100009; part C wires it into the
flows): a work Smith whose change needs looking at once it is in production
writes a `## POST-DEPLOYMENT-CHECK` section into its summary, the PR body
(`bots/smith.md`, "The post-deployment check"). `PostDeploySection.parse`
(`postdeploy/` in engine-flow-delivery) reads it: the heading at level 2 or 3
in any case, to the next heading of the same level or the end; a `Re-check:`
or `Deadline:` line at line start is a key (`30m`, `4h`, `2d`, `1w`, or for the
deadline an ISO date or instant), everything else is the prose `what`. A section
with no prose is a `PostDeploySectionError` naming the PR; a body with no
section is null. The flow keeps it as `postDeployCheck` (JSON bytes), with the
merge's `mergeSha` and the `deployEvent` that said the change is live.

A `check` run is a cheap Smith in mode `check`: `start smith` resolves its tier
from `engine.engine_setting`'s `check.tier` (`TierTable.checkTier()`, seeded 4
by V95; `GET`/`PUT /check/tier`, the desktop's Config > frakworks > Tiers), as
`security` runs at `security.tier`, whatever the checked ticket's own tier. Its
brief carries `postDeployCheck`: `what`, `recheck`, `deadlineAfter`,
`deadlineAt`, `pr`, `mergeSha`, `deployEvent` and `history`, every earlier
run's outcome and summary. It reads the engine at `$ENGINE_URL`, the frakworks
server and the repo's own MCP servers, changes nothing, and answers
`smith/schemas/check.schema.json`: `outcome` `passed`, `failed` (with a `bug`
`{title, body}`) or `not_yet` (with an optional `wake` hint, `{"at": instant}`
or `{"event": "<type>[:<field>=<value>]"}` over the bus frame types of
"Events"). `read result` hands it to `CheckResultHandler`, which sets
`checkOutcome`, `checkSummary`, `checkWakeAt` (a Date) and `checkWakeEvent`,
appends the run to `checkHistory` and writes one conversation entry on the
ticket's work item; a `failed` with no bug, a wake with both or neither key or
an unparsable `at` is refused as a broken rule. `${fileCheckBug}`
(`FileCheckBugDelegate`) files the bug in the ticket's repo through
`TicketFiler` at the original's tier, `p2`, asked by the original's asker, its
Context naming the original ticket, its PR and `mergeSha`, and sets
`checkBugTicket`; the original is never reopened. `${nextCheck}`
(`NextCheckDelegate`) sets `checkWakeAt` to the hint's time when given, else
now plus the section's `Re-check` for the first wait, else the ladder by the
runs so far: 1h, 4h, 1d, then daily. The run has no retro and no bundle. The
flow around it, the deploy wait and the deadline: [post-deploy-check.md](post-deploy-check.md).

## Turn budget

`run-smith.sh` runs `claude -p --max-turns $SMITH_TURNS`, and the script's
default is 200: every run whose env sets no `SMITH_TURNS` gets it (`work`,
`review`, `design`, `refine`, …). The engine sets it for one mode: `start
smith` (`StartSmithDelegate`) puts `engine.smith.parent-turns` (default 1000)
on a `parent` run's `SmithSpec.turns`, which `sandboxEnv` writes as
`SMITH_TURNS`; every other mode's spec has none, and the key is absent from
its env. A live parent is one session for a whole split (#100042;
[feature.md](feature.md), "Live parent"): every `await_children` answer and
every look at a child costs turns, so it needs far more than a work run,
while its wall-clock bound stays the Job's `engine.smith.k8s.active-deadline`
(24 h), unchanged. Raise `engine.smith.parent-turns` when parents end `ended`
with the budget spent; never the deadline.

## The retro

After a `work`, `review` or `security` run (#876), `run-smith.sh` asks one more
question on the same Claude session: what got in the way. The main `claude -p`
gets a pinned `--session-id` (a fresh UUID); once it has exited and the commits are
bundled, a second call `--resume`s that session with `--tools ""` and
`--strict-mcp-config` (no tools, no MCP server), its own `--max-turns`
(`SMITH_RETRO_TURNS`, 3) and `--max-budget-usd` (`SMITH_RETRO_BUDGET`, 1.00), under
`timeout` (`SMITH_RETRO_TIMEOUT`, 300 s), and `--output-format json --json-schema`
`smith/schemas/retro.schema.json`. Its `structured_output.notes` becomes
`/work/out/retro.json`, a JSON array of `{kind, summary, evidence?, severity?}`, and the
log says `smith: retro: <n> note(s)`, or `smith: retro dropped: <why>` when the call
failed or gave no notes (then there is no file).

The retro is best effort and changes nothing about the run: the script's exit status
stays the main session's, `result.json` is put back byte for byte (or removed, if the
main session left none), and `commits.bundle` is written before it. It runs before
the meter proxy stops, so its tokens are metered with the run's. `SMITH_RETRO=0` turns
it off; other modes never get one.

`collect result` (`CollectSmithDelegate.recordRetro`) reads `retro.json` for those
three modes and stores each valid item (at most 20) as an `engine.friction_note` row
with `source` `retro`, stamped with the run's repo, ticket, run id and root flow, as
`note_friction` stamps its own ("The frakworks server in a Smith"). A file that is no
JSON array is logged and dropped; an item that breaks `note_friction`'s rules is
logged and skipped. None of it ever fails the collect or sets a variable.

Probe, after a real work run: its log has the `smith: retro:` line, and while the
sandbox is still there `docker exec <smith-container> jq . /work/out/retro.json`; then
`read_friction_notes` with `{"source":"retro","ticket":<n>}` ("The diagnostics server").

## Each repo's token

Every GitHub call the engine makes is about one repo, and it makes that call as **that
repo's token** (`github/RepoTokens.kt`; decided with the user, 2026-09-27): the token sealed
on the repo's `engine.target_repo` row. There is no other token: no fleet-wide token, no
`.env` value, no property, nothing that stands in for a repo that has none (the fleet token,
`FLEET_TOKEN`, was removed on 2026-10-02 at the user's word). A repo without a token cannot
be worked: every GitHub call for it fails with `no GitHub token for <repo>: PUT
/repos/<repo>/token`, its Smith run does not start, and the startup log warns about it. So a
target repo runs on a fine-grained PAT that reaches that one repo and nothing else, a Smith
working it is handed that token as `GH_TOKEN` and can reach no other repo, and a repo in
another organisation (a fine-grained PAT is bound to one resource owner) is just a row with
its token.
The git proxy (`GET /repos/{owner}/{repo}/git/info/refs` and `POST .../git/git-upload-pack`,
above) authenticates to `github.com` as `x-access-token:<the repo's token>` over basic auth, so
a desktop clones and fetches through it with no credential of its own and reaches exactly what
the token reaches, read-only: a push through it is refused before GitHub is asked.

```
curl -H "Authorization: Bearer $FRAKWORKS_TOKEN" -XPUT localhost:8090/repos/acme/widgets/token -H 'content-type: application/json' -d '{"token":"github_pat_..."}'
curl -H "Authorization: Bearer $FRAKWORKS_TOKEN" localhost:8090/repos                                  # hasToken, tokenSetAt; never the token
curl -H "Authorization: Bearer $FRAKWORKS_TOKEN" -XDELETE localhost:8090/repos/acme/widgets/token      # no token: nothing runs on it
```

The token is stored the way an AI account's credential is ("AI accounts"): AES-256-GCM
under `ENGINE_SECRETS_KEY`, the repo's full name as associated data, `key_id` for rotation;
a backup or `psql` sees ciphertext, and the API never returns it (`GET /repos` says
`hasToken`). Before it is stored the engine reads `GET /repos/{o}/{n}` as it: a token that
cannot read the repo, or can read but not push, is refused (400) and nothing is saved;
`"verify": false` skips that. The desktop's Config > Repos sets it (**Set token** on the
tile, or with **+ Add repo**). Removing the repo removes its token; GitHub-side revocation is
yours. The setup check says whether the repo has one (`hasToken`).

**A Smith gets a second token, the repo's Smith token**, never the repo token (#2613, part of
#2567: Smiths never touch GitHub issues). It is a fine-grained PAT for the same repo with
Contents and Pull requests read/write and **no Issues permission**, sealed on the row's
`smith_token` (V93; `"<repo>#smith"` as associated data, so it never opens as the repo token)
and handed to the sandbox as `GH_TOKEN` (`RepoTokens.forSmith`, `SmithTokens`, used by
`DockerSandbox` and `KubernetesSandboxConfig`). There is no fallback: a repo without one
starts no Smith (`no Smith token for <repo>: PUT /repos/<repo>/smith-token`). The engine
keeps the repo token for its own calls (PRs, merges, publishing). `PUT
/repos/{o}/{n}/smith-token` (body `{"token"}`) always tries it first: it must read and push
to the repo (`GET /repos/{o}/{n}` as the token: readable, and `permissions.push`), and nothing
else is asked of it: no Issues probe, whatever the token's Issues permission (the user's call on
#100020: check only what a Smith needs). A token that cannot read or push is refused with a 400,
nothing saved; GitHub unreachable is a 502. The same check runs before each sandbox starts,
so a token that lost read or push since it was set starts no Smith. `DELETE` drops it
(204, 404 for a repo that is not a target repo); `GET /repos` says only `hasSmithToken` and
`smithTokenSetAt`.

```
curl -H "Authorization: Bearer $FRAKWORKS_TOKEN" -XPUT $ENGINE_URL/repos/acme/widgets/smith-token -H 'content-type: application/json' -d '{"token":"github_pat_..."}'
curl -H "Authorization: Bearer $FRAKWORKS_TOKEN" -XDELETE $ENGINE_URL/repos/acme/widgets/smith-token   # no Smith runs on it
```

Who a token is, and what it may do, is read per token (`TokenIdentity`: one `GET /user`
per distinct token, remembered by a digest of it, never the token) and logged at startup
for every target repo: `acme/widgets's token: login acme-bot, scopes unknown: fine-grained
token or no header`, or a WARN for a repo that has none.

**Commits are by the token's account too** (decided with the user, 2026-10-04), so a commit, its
push, its PR and its merge are one identity on GitHub. `TokenIdentity.commitIdentity` makes it from
the same `GET /user`: the account's name (its login when it has set none) and GitHub's noreply
address, `<id>+<login>@users.noreply.github.com`, which links the commit to the account whether or
not its own address is public. A Smith gets it as `SMITH_GIT_NAME` and `SMITH_GIT_EMAIL` and
`run-smith.sh` sets git's `user.name` and `user.email` from them; the engine's own commits
(`GitPush.mergeDefault`, `GitPush.commitEdit`) use it as author and committer. Nothing stands in
for it: when GitHub will not say who the token is, the Smith run does not start and the engine's
commit is not made. A dry run commits nothing and is not asked. The commits are unsigned, so
GitHub shows them as Unverified.

A per-repo token is the shape a GitHub App installation token has too
(a token per repo, short-lived), so an App can later be one more `RepoTokens` source.

## The token's scopes

A repo's token ("Each repo's token") does everything the engine
does on GitHub for that repo: it reads and files issues, and it pushes every Smith's bundle.
A classic PAT needs:

- `repo`, always (it also re-runs a failed engine check's Actions job, "Chore");
- `workflow` as well whenever a ticket's Allowed scope may include `.github/workflows/**`:
  GitHub refuses any push that touches `.github/workflows/` without it. On 2026-09-26 the
  token had only `repo`, and #469's bundle, which edited `.github/workflows/images.yml`,
  could not be pushed.

A fine-grained PAT needs the matching permissions on the repos: Contents, Issues and Pull
requests read and write, Actions read and write (the engine check's job log, and its
re-run on Jev's triage), and Workflows read and write for the same tickets.

**Per token** `TokenIdentity` reads the scopes from the `X-OAuth-Scopes` header of the
`GET /user` call it makes for the token's login (at startup for every
repo's token; on first use for a token set later), keeps them in memory, and logs them at
INFO (`acme/widgets's token: login fleet-bot, scopes [repo, workflow]`; never the token). When the
header is present and lacks `workflow` it logs a WARN naming `workflow`. A fine-grained
token sends no such header, so when it is absent, or `GET /user` fails, the scopes are
*unknown*: the engine logs `scopes unknown: fine-grained token or no header` and refuses
nothing on them; the push stays the only check. An empty header is a classic token with no
scopes. A failed lookup is not remembered, so the next ask tries GitHub again.

**At refine time** a ticket that could only fail at the push is never filed. A refine run
gets its repo's token's scopes as `SMITH_GH_SCOPES` (comma-separated, `none`, or `unknown`), and its
brief (`smith/run-smith.sh`) states the rule: while the scopes are known and lack
`workflow`, a ticket whose Allowed scope names a `.github/workflows/` path (or
`.github/**`) is not `file` but `insufficient-permissions`, with a `blocker` naming `workflow`. The
engine enforces it whatever the brief did: `RefineResult.problems` refuses such a `file`
with a message naming the missing `workflow` scope, which `readResult` raises as an
incident before anything is filed, so a retry reruns the refiner. Unknown scopes, or
scopes that include `workflow`, refuse nothing. Only a line that grants counts: one that
keeps `.github/` out ("May not touch: `.github/**`") is no reason to refuse ("The Allowed
scope is guidance", below, on which lines grant).

**At work time** nothing is checked against the scopes: no other run gets `SMITH_GH_SCOPES`,
and a work bundle that touches `.github/workflows/` without the `workflow` scope fails at
the push, as the push incident names ("Incidents").

## GitHub calls and the rate limit

GitHub allows a token's account 5,000 REST calls an hour, and every token of one
account (the engine's, each Smith's) spends the same budget; past it every call
answers 403 "API rate limit exceeded" until the window resets, which on
2026-10-06 put tickets on incidents twice. Every REST call the engine makes goes
through `GitHubCallMeter`, a request interceptor on `RestGitHub`, `RestGitHubPulls`,
`RestCheckRuns` and `RestRepoSetupGitHub`, and shows on `GET /metrics`:

- `frak_github_requests_total{caller,endpoint,status}`: a counter per engine
  class that made the call (the first engine frame above the HTTP clients, e.g.
  `IssueScanner`, `CiCheckWaits`, `IssueBoard`, `RebasePrDelegate`), endpoint
  (`GET /issues/:n`: the repo cut, numbers as `:n`, commit SHAs as `:sha`) and
  HTTP status (`error` when no answer came). Since the engine started.
- `frak_github_ratelimit_remaining`, `_limit` and `_reset_seconds`
  `{repo,resource}`: GitHub's `X-RateLimit-*` headers from its last answer for
  that repo's token.

Smiths' own `gh` calls spend the same budget but are not counted here; their
transcripts (VictoriaLogs) hold each command. Git fetches and pushes are not REST
calls and do not count against the limit.

## The frakworks server

`frakworks` (`mcp/FrakworksMcp.kt`, issue #541), at `POST $ENGINE_URL/mcp/frakworks`,
one of the engine's own MCP servers ("Engine-served MCP servers", below). Both of
its clients connect to it over HTTP: First mate (the desktop app) and every Smith.
Neither spawns a server process any more; the desktop's old stdio server,
`observability/desktop/src/frakworks-mcp.js`, is gone.

- **Who is calling.** The role is the caller's address, as for `POST /intake`
  (the controller's `McpCaller`): a running Smith is that Smith and is listed only
  its `submit_ask`, `note_friction`, `read_ci_log`, `read_ci_jobs`, `read_ci_job`, `search_ci_log`, `rerun_ci_jobs` (on its run's repo only: another `repo` is refused, a missing one is its run's), `read_run_log`, `read_version` and `link_friction_fix_intake` ("The frakworks server in a Smith"); any other caller is First
  mate. First mate proves who it acts for with a bearer token the engine issued
  (`Authorization: Bearer frk_...`, "Tokens"): the token's login is the caller's
  login, where `McpCaller.Nobody.login` and `Actor.Login` are built, and reaches
  `POST /intake` as the submitter. The desktop sends one (#2206: signed in on Config >
  frakworks, the token is in each engine server's `Authorization` header of its
  `--mcp-config`) and nothing else names a login. A request to `POST /mcp/{server}`
  without a valid token is refused with JSON-RPC error `-32001`, for every method
  (`initialize`, `ping` and `tools/list` too), with the request's id and the
  filter's texts. A Smith is known by its address, needs no token, and its
  `Authorization` header is ignored, so it is still that Smith, with no submitter. If Docker cannot be
  asked, the caller is listed a Smith's tools and every call is refused, never
  taken as First mate.
- **First mate's tools.** `submit_ask`, `read_transcript`,
  `read_needs` (its rows carry `question`, the pending question's full text,
  the preceding run's for a flow with none recorded, and `for`, the login the
  row waits on, null when it is everyone's, "What needs you"), `read_ticket_pr`, `approve`, `reauthorize`, `note_friction`, `read_version` (the engine's build version and commit, as `GET /version` answers),
  `read_repos`, `add_repo`, `edit_repo`, `remove_repo`, `read_ci_runs` (#1629, below; refused to a Smith), `read_ci_jobs`, `read_ci_job`, `search_ci_log` (#2188) and `rerun_ci_jobs` (#2249) (below; a Smith's on its run's repo only), `read_ticket_runs`, `search_tickets` (below), `read_access`, `add_access`, `remove_access`, `read_tokens`, `mint_token`, `revoke_token` (below; refused to a Smith), `read_ticket`, `list_private_docs` and `read_private_doc` (#1735, below; `read_ticket` refused to a Smith), `read_repo_mcp_servers`, `add_repo_mcp_server`, `edit_repo_mcp_server`, `remove_repo_mcp_server` and `test_repo_mcp_server` (#1801, "First mate's MCP servers"; each takes a required `repo`, refused naming the target repos without one, and refused to a Smith), `link_friction_fix`
  (#1200, "Friction notes", "Fixed by"; refused to a Smith), `link_friction_fix_intake` (#1337, "Fixed by"; a Smith's
  too), `cancel_intake`, `amend_intake`, `amend_ticket`, `close_ticket` and `comment_ticket` (below), the first four with the descriptions, schemas and error texts the desktop's server
  had (observability/desktop/README.md, "First mate"): an unknown `tier` is refused,
  and a passed one becomes the ask's first line, `Tier: <n> (<model> <effort>) —
  prescribed by the user; do not re-judge`. Each calls the engine in-process
  (`mcp/EngineCalls.kt`: `IntakeController`, `Needs`), with the same checks as
  the HTTP API; an engine refusal comes back as the tool's error text.
- **read_ci_runs.** `read_ci_runs({repo, workflow?, branch?, limit})` (`mcp/CiRunsTool.kt`, #1629) answers how long CI
  builds take lately: the target repo's newest GitHub Actions workflow runs, newest first, from GitHub's workflow-runs
  API with the repo's token (`/actions/runs`, or `/actions/workflows/<workflow>/runs` when `workflow`, a file name such
  as `engine.yml` or an id, is given; `branch` filters). `repo` is required and must be a target repo: a missing or
  non-target one is refused naming the target repos. `limit` (1–100) is required, and above 100 is refused, not capped.
  It answers `runs`, each `{id, workflow, branch, event, status, conclusion, createdAt, startedAt, completedAt,
  durationSeconds, queuedSeconds, url, headSha, runNumber}` (`startedAt` is GitHub's `run_started_at`, `completedAt` its
  `updated_at` once the run is completed, else null; duration = completedAt − startedAt, queued = startedAt − createdAt,
  null when not known; `headSha` is the run's head commit SHA and `runNumber` its run number, from GitHub's `head_sha`
  and `run_number`, so a desktop release can be matched to a commit without comparing timestamps), and `summary`
  `{count, medianDurationSeconds, medianQueuedSeconds}`: medians over the runs with a known value
  (an even count averages the middle two), null when none. First mate's only; a Smith is not listed it and its call is
  refused. It is apart from a Smith's `read_ci_log`.
- **read_ci_jobs, read_ci_job, search_ci_log.** First mate's bounded reads of a target repo's CI jobs and their logs
  (`mcp/CiJobsTool.kt`, `ci/CiLogSearch.kt`, `ci/CiLogCache.kt`, #2188). Each takes a required `repo` that must be a target repo (a missing or
  non-target one is refused naming the target repos, as `read_ci_runs` does), refuses any other argument and an id that
  is not a positive integer, and is refused to a Smith (`a Smith sandbox (run …) may not call …`); a Smith reads its own
  failed check's whole log with `read_ci_log`, paged, which First mate does not get. No answer is ever a whole log: each
  is at most `FrakworksMcp.PR_CHAR_BUDGET` (24,000) characters, and one that would be longer drops its last steps,
  failures or matches (then the tail's first lines) and says how many in `cut`; a line is cut at 400 characters.
  - `read_ci_jobs({repo, run_id})`: the run's jobs (its latest attempt, GitHub's `/actions/runs/<id>/jobs`, at most
    100), each `{id, runId, name, status, conclusion, startedAt, completedAt, elapsedSeconds, url, slowestStep, steps}`,
    each step `{number, name, status, conclusion, startedAt, completedAt, elapsedSeconds}` (a running one's to now), the
    slowest marked `slowest`, and one still `in_progress` past 600 s and past 3× its job's median finished step marked
    `possiblyStuck`. It reads no log.
  - `read_ci_job({repo, job_id, tail?})`: one job (`/actions/jobs/<id>`) as above; for a job that ended `failure`,
    `timed_out` or `cancelled`, its `failures` (`CiLog.failures`: Gradle's `… FAILED` tests and Kotlin's `e:` errors,
    at most 30) and `tail` (`CiLog.tail`: the last 80 lines). `tail` (1–200, above refused) reads the last N lines of
    any job, running or finished; a running job's answer adds `lastOutputAt` (the last Actions timestamp in its log)
    and `silentSeconds`, for "is it stuck".
  - `search_ci_log({repo, job_id, pattern?, fixed?, context?, max?})`: the job's cleaned log searched line by line for
    `pattern` (a Java regex of at most 200 characters, or a literal with `fixed: true`), or with no pattern for lines
    often worth a look in a passed job (warnings, deprecations, retries, flaky, cache misses, timeouts, exceptions,
    errors). It answers `total` (every matching line) and the first `max` `matches` (default 20, at most 50), each
    `{line, text, before, after}` with `context` lines each side (default 2, at most 5). A search stops after 2 s
    (`timedOut`), so a pattern that backtracks cannot hold the engine.
  - `rerun_ci_jobs({repo, run_id | pr})` (#2249): re-runs a run's failed jobs, and the jobs they need, with GitHub's
    `POST /actions/runs/<id>/rerun-failed-jobs` (the fleet token's `repo` scope, as the check's own job re-run uses),
    so a flaky failure on an unchanged commit is cleared without a push. Exactly one of `run_id` or `pr`; with `pr`,
    the latest run of each workflow on the PR's head commit (`/actions/runs?head_sha=`). It answers `rerun`, each
    `{runId, workflow, jobs}` (the failed jobs' names), and `skipped`, each `{runId, workflow, why}`: a run still in
    progress, or one with no failed job, is left alone; a PR head with no run says so in `why`. The incident side of
    the same need is `act_on_incident`'s `rerun_ci` ("A ci finding the work declines").

  Performance: a completed job's log never changes, so its cleaned lines are kept in memory per (repo, job id) for 6 h,
  least recently used first out once the cache holds 32 M characters: a `read_ci_job` and any number of searches of
  one job fetch its log from GitHub once. A running job's log is fetched on each call that needs it and not kept. The
  engine reads at most 8 MB of a log (the signed URL's `Content-Length`, else the bytes read): above it `read_ci_job`
  answers the steps and `logTooLarge` with the size, and `search_ci_log` refuses with the size and the steps.
  `act_on_incident` is not here: it is the diagnostics server's (#539).
  `read_doctor_history` (#655) is not here either: it moved to the diagnostics server in #658.
- **read_ticket_pr** (#742). `read_ticket_pr({ticket, repo?, path?, diff?, offset?})`
  reads a ticket's PR(s), the PRs that close it on GitHub (`IssueDetails.ticketPulls`),
  in `repo`, which is required (there is no default repo) and must be
  a target repo (`TargetRepos`; any other is refused, `<repo> is not a target
  repo`, before a GitHub call: no other repo has a token). Without `path`
  or `diff` it returns each PR's number, url, state, head branch and files (path
  and `changeType`), GitHub's `mergeable` (`MERGEABLE`, `CONFLICTING`, `UNKNOWN`)
  and `mergeStateStatus` (`CLEAN`, `BLOCKED`, `BEHIND`, …) as the PR view gives
  them, and `checks` (#1628): every check run and commit status on the PR's head
  commit (`statusCheckRollup`, in the same GraphQL query), each `{name, status,
  conclusion, startedAt, completedAt, url}` with `status` `queued`, `in_progress`
  or `completed`; a commit status maps `pending` to `in_progress` and any other
  state to `completed` with that state (lower-cased) as `conclusion`; with `path`, that changed file's text at the PR's head branch
  (`fileText`); with `diff: true`, the PR's unified diff (`pullDiff`, `GET
  /repos/{r}/pulls/{n}` with the `application/vnd.github.diff` media type). The
  token's repo read is all it needs. A file text or diff comes in pages of
  `PR_CHAR_BUDGET` (24,000) characters from `offset` (default 0, with `path` or
  `diff` only): a page with text after it ends with `… 9,412 more characters;
  pass offset: 24000 for the next page.`, and a page past 0 starts with
  `(characters 24000–33412 of 33412)`. An `offset` past the end is a tool error
  naming the text's length. No PR, a `path` the PR does not change (the error names the ones it
  does) and an unknown repo or issue are tool errors. A proposal ticket with no
  PR yet (drafting, its draft under review, or parked on `post the smith's
  question`) answers from the engine (#1158): its latest `proposal` flow's
  instance, the step(s) it waits at (`waitsAt`), the stored propose result's
  `path`, `pitch`, `summary` and `questions`, and the open `engine.intake_question`
  rows on the flow (`openQuestions`, question and asked-at), under `draft`. With
  `path` (the draft's doc or pitch) it returns that file at the unpushed
  `bundle`'s head, read through `GitPush.proposalChanges` with nothing pushed,
  paged with `offset` as a PR's file is; a path the draft does not change names
  the ones it does. `diff` is refused for it (no PR, no diff; the error names
  `path`). A proposal whose flow has ended, or has stored no draft yet, is a tool
  error saying the draft is not there. Not a Smith tool.
- **cancel_intake** (#1173). `cancel_intake({intake_id, reason})` is `DELETE
  /intake/<id>` as First mate's login (`FlowCanceller.cancel`, "Cancel and the
  sweep"): Smiths stopped, a pending question withdrawn, the ask item closed
  as not planned (left open once the intake filed: it is the ticket) with a comment naming `@<login>` and the reason. Returns `{ok:
  true, intakeId, failedWrites}`. Refused, before anything is read: a `reason`
  that is blank (`a cancel needs a reason`), an `intake_id` that is no instance id
  (`not an intake id: "<id>"`), no token login (`no GitHub login to cancel
  as …`). The endpoint's own refusals come back as the tool's error: `<id> is not
  running` (404), `@<login> is not a trusted login` (403).
- **amend_intake** (#1173). `amend_intake({intake_id, text})` adds the user's
  follow-up, verbatim, to an intake that has not filed its ticket
  (`IntakeAmender`; what it does in each state: "Intake", "A follow-up to an
  intake"). Returns `{ok: true, intakeId, refining}`, `refining` one of
  `restarted`, `waits on a question` or `recorded`. Refused, recording nothing:
  blank `text` (`a follow-up needs some text`) or one over `MAX_ANSWER_CHARS`
  (`a follow-up is at most 20000 characters`), a bad `intake_id`, no login
  (`no GitHub login to amend as …`), `@<login> is not a
  trusted login`, `intake <id> has already filed #<n>; use
  amend_ticket on #<n>`, `intake <id> is filing its ticket; try amend_ticket on
  the ticket in a moment`, and `intake <id> is not running`.
  Neither tool is a Smith's: `SMITH_TOOLS` does not list them, and a Smith's call
  is refused `a Smith sandbox (run <id>) may not call <tool>: intakes are the
  user's to cancel or amend` (over HTTP, the controller's `unknown tool`, -32602).
- **amend_ticket** (#1362). `amend_ticket({ticket, repo, text})` adds the
  user's late information, verbatim, to a ticket already filed whose flow runs,
  and loops the flow back to the work (`TicketAmender`, as `POST
  /ticket/{owner}/{repo}/{n}/amend`; what it does at each stage: "Intake", "A
  follow-up to a ticket"). Returns `{ok: true, repo, ticket, work, lost,
  failedWrites}`. Refused before the engine is asked: blank `text` (`an
  amendment needs some text`), one over `MAX_ANSWER_CHARS`, a bad `ticket` or
  `repo`, no login; the engine's refusals come back as the tool's error. Not a
  Smith's: a Smith's call is refused `a Smith sandbox (run <id>) may not call
  amend_ticket: tickets are the user's to amend`.
- **approve** (#799). `approve({ticket, repo?})` authorizes ask item
  `ticket` in `repo` (required; not a target repo is `<repo> is
  not a target repo`) as First mate's login, when the user says "approve #N".
  First mate only: a Smith is not listed it and its call is refused, since a Smith
  never authorizes an intake ("The frakworks server in a Smith"), and a call with
  no token login is refused as for `submit_ask`. The login must be
  a trusted login; without it the error is `POST
  /intake`'s for `authorize: true`, `authorize is true but @<login> is not a trusted login`,
  and nothing changes. It authorizes in-process, with no label:
  `IssueApprovals.approveIntake` hands the intake waiting at `intake authorized` on that
  ask item its authorization with `authorizedBy` the login (what
  `POST /work-items/{id}/start` on the item does too), and records `Authorized by @<login>`
  on the item. It returns `{ok, ticket, repo, intake_id, authorized_by}`, with `notes` when the
  entry failed after the authorization. A closed item, or one with no
  intake waiting on its authorization (already authorized, refined, or not an
  ask item), is a tool error and nothing is written.

  A filed ticket (#1316): an open issue with a kind label other than `intake`
  (research, chore, bug, feature, proposal) and no `intake` label, which is
  every other `authorize` row in What needs you, goes to
  `IssueScanner.approveTicket` instead, and needs no intake-specific check. Under the
  scan's lock it refuses, with nothing written, an issue that is closed, already
  carries `authorized`, has no single kind label, or when the login is not trusted (`Access.trusted`, as the scan checks a label's actor:
  `@<login> is not a trusted login`). Otherwise it adds
  `authorized` with the repo's token, reads that label event back and hands it to
  the scan's own `consider` with the login as its actor: the flow starts, waits
  on an open blocker or is refused exactly as for a label from the login, and the
  event is claimed in `engine.handled_issue`, so no later scan reads it as the
  token's. An issue that already carries `authorized` whose approval the scan
  refused for an untrusted labeller (`… who is not a trusted login` in
  `engine.handled_issue`) is not refused: that refused claim is given back and
  the same label event goes to `consider` with the login as its actor, writing
  no label (#2256); one whose approval took effect is still refused. An intake's
  `approve` on such a label turns its refused row into the `started` one. It
  comments `Authorized by @<login> through First mate: …` and returns
  `{ok, ticket, repo, flow, outcome, detail, authorized_by}`, with `notes` when
  the comment failed.
- **reauthorize** (#850). `reauthorize({ticket, repo?})` re-authorizes a
  `feature`, `chore` or `bug` waiting on a blocker that closed without a merge
  (a `reauthorize` row of "What needs you") as First mate's login, when the user
  says "re-authorize #N": what re-labelling it `authorized` would do, which First
  mate cannot (the issue already carries the label, and First mate runs
  non-interactively, with no `gh`). `repo` defaults, and a repo that is not a
  target repo is refused, as for `approve`. First mate only, refused for a Smith
  and without a token login as `approve` is. `IssueScanner.reauthorize`,
  under the scan's own lock, reads the issue's `engine.deferred_issue` row and its
  blockers now, and refuses, with nothing written (no flow signal, no row change,
  no comment), each with its own text ending `; nothing was changed`: a closed
  issue; no deferred row (an ask item says so, and that `approve` is the way);
  a blocker still open; a blocker merged with its flow still at its acceptance
  check, or every blocker closed by a merge (the scan goes on by itself); a fresh
  `authorized` label the scan has not read yet; a login that is not trusted
  (`@<login> is not a trusted login`); and, for a deferred start, a stored approval that no longer stands
  (its label removed or its kind changed). Otherwise it does what a fresh label by
  the login does in the scan:
  - a split feature parked on its child (`waitingInstance` set) is woken with
    `child closed` and no PR, the child treated as satisfied ("Split features"),
    recorded in `engine.handled_issue` as `resumed` (`re-authorized by @<login>
    through First mate: child #n, closed without a merge, treated as satisfied`)
    under a negative label event, no GitHub id: the parent's own label event is
    claimed by its start and no label is written, so there is no event left for a
    scan to act on; it comments `Resumed (re-authorized by @<login> through First
    mate): …` and returns `outcome: "resumed"` with the parked instance;
  - a deferred start starts from its stored snapshot, tier and feature mode with
    `authorizedBy` the login, claiming the stored label event as `started`
    (`re-authorized by @<login> through First mate: #n closed without a merge`),
    so a later scan finds it handled; it comments `Started: re-authorized by
    @<login> through First mate; …` and returns `outcome: "started"` with the new
    instance.

  Either way the deferred row is removed, so the `reauthorize` need is gone, and
  the tool returns `{ok, ticket, repo, instance, authorized_by, outcome}`, with
  `notes` when the comment failed after the change.
- **note_friction** (#968). First mate's `note_friction({kind, summary, evidence?,
  severity?, repo?})` notes something it lacked that shaped an answer (a tool, access,
  data; observability/desktop/README.md, "First mate notes friction"). The four
  arguments, their validation and their error texts are a Smith's ("The frakworks
  server in a Smith"); `repo` (owner/name) is the repo the friction was met in, the
  desktop's selected repo, and a repo that is not a target repo is refused, `<repo> is
  not a target repo`, as `read_ticket_pr` refuses (the row keeps the target repo's
  spelling). First mate has no run, ticket or container, so the engine stamps the row
  with who it was instead: `source` `first-mate`, `login` the caller's
  token login (no login, or one that is no GitHub login, is refused with
  `no GitHub login to note as ...` and nothing is written), `repo` as passed or null,
  and `issue`, `run_id` and `process_instance_id` null (`FrictionLog.recordFirstMate`).
  The row gets its exact key in the same insert and is grouped afterwards by the
  friction grouper, as a Smith's note is ("Friction notes"). It answers `{ok: true, id}`;
  it writes nothing to GitHub and opens no need. Read the notes back with
  `read_friction_notes({source: "first-mate"})` ("The diagnostics server").
- **read_repos, add_repo, edit_repo, remove_repo** (#1021). The engine's target
  repos, read and managed from the chat: each wraps a `/repos` call ("Its own
  tables", `engine.target_repo`), made in-process through `FrakworksEngine` on the
  same `TargetRepoController`, so it checks what the HTTP API checks, refuses with
  its text (each refusal is the tool's error) and publishes the same `repo` event
  ("Events"), so the desktop's Config > Repos refreshes. `repo` is owner/name
  (else `repo is owner/name`), required on all but `read_repos`; a repo given in
  another case is the row's.
  - `read_repos({repo?})` is `GET /repos`: every target repo, or only `repo` (not
    one is `<repo> is not a target repo`), each with `fullName`, `addedAt`,
    `ciCheck`, `ciPaths`, `securityReview`, `scanIntervalMinutes`, `doctorBugs`,
    `engineDefects`, `hasToken`, `tokenSetAt`, `hasWebhookSecret`,
    `webhookSecretSetAt`, `scanCalls`, `scanCallsPerScan`, `tokenPermission` and
    `callerPermission` (as `GET /repos` reports them, the caller's for First mate's
    login; null without one), as `{count, repos}`. Read-only, and it needs no login.
  - `add_repo({repo, ciCheck?, ciPaths?, securityReview?, scanIntervalMinutes?})`
    is `POST /repos`: `{ok, repo, outcome: "added"}`, the `added` event, or for a
    repo that is already one `outcome: "already there"`, with nothing changed.
    Any other key is refused (`add_repo takes only ...`), where `POST /repos`
    would ignore it: `doctorBugs` and `engineDefects` are not taken (turn them on
    with `edit_repo`), nor `token` or `webhookSecret`.
  - `edit_repo({repo, ciCheck?, ciPaths?, securityReview?, scanIntervalMinutes?,
    doctorBugs?, engineDefects?, frictionBugs?, docsFirst?, childMode?})` is `PATCH /repos/{owner}/{name}` with every key
    but `repo` as its body: a key present sets its column (null clears `ciCheck`
    or `ciPaths`), a key absent leaves it, at least one is needed. Its refusals
    are the PATCH's: an unknown field (`token` and `webhookSecret` among them),
    `ciCheck` blank, `ciPaths` with a comma, `scanIntervalMinutes` outside 1 to
    1440 or not a whole number, `engineDefects` without `doctorBugs` (400) or
    while another repo has it (409, naming it), `<repo> is not a target repo`. It
    returns the row as `read_repos` shows it, after the `changed` event.
  - `remove_repo({repo})` is `DELETE /repos/{owner}/{name}`: `{ok, repo,
    outcome: "removed"}` and the `removed` event; refused while a flow runs on the
    repo with the API's 409 text naming each flow (`<repo> has running flows:
    smith-run #42; cancel or finish them first`), and `<repo> is not a target repo`
    for its 404.

  First mate only: a Smith is not listed them, and a call from one that reaches
  `FrakworksMcp` is refused naming its run (`a Smith sandbox (run <id>) may not
  call <tool>: ...`); a caller Docker cannot place is refused as for every tool.
  The three writes need the token's login (`a GitHub login is needed to add
  a target repo ...`), as `approve` does, though the API takes none and nothing
  checks it against the trusted list (nothing gates managing repos on it). A repo's
  token and webhook secret (`PUT`/`DELETE /repos/{owner}/{name}/token` and
  `.../webhook-secret`) have no tool, on purpose: a secret passed through a tool
  call lands in First mate's Claude context. Set or clear them in the desktop's
  Config > Repos; `read_repos` says only whether one is set, and since when.
- **read_access, add_access, remove_access** (#1636). The engine's trusted logins
  ("Starting a flow from an issue"), in-process, for First mate only: a Smith's call
  is refused naming its run (`... may not call <tool>: the trusted logins are First
  mate's to manage`). Unknown keys are refused (`<tool> takes only "login", not ...`;
  `read_access takes no arguments, ...`).
  - `read_access()` is `GET /access`: `logins`, and `history`, every grant and
    revoke oldest first (`changedAt`, `change`, `login`, `changedBy`).
  - `add_access({login})` is `POST /access`, the token's login the
    actor: `added`, or `already there` with nothing changed.
  - `remove_access({login})` is `DELETE /access/{login}`, the token's login the
    actor: `removed`, or an error when the login is not on the list. It refuses
    the caller's own login, compared case-insensitively, and changes nothing, so
    First mate cannot lock the user out.
  The writes act as the bearer token's login
  (`a GitHub login is needed to ...`), and `login` is a GitHub login (letters,
  digits and dashes, at most 39).
- **read_tokens, mint_token, revoke_token**. The engine's bearer tokens
  ("Tokens"), in-process, for First mate only: a Smith's call is refused naming
  its run (`... may not call <tool>: engine tokens are First mate's to manage`).
  - `read_tokens()` is `GET /tokens`: `tokens`, every row newest first, never a
    token or its hash.
  - `mint_token({login, name, expiresInDays?})` is `POST /tokens`, recorded as
    created by the caller's login: the row, `token` (shown this once: the tool
    tells the user to store it in 1Password) and a `note`. `login` must be trusted
    (`trust it first (add_access), then mint`).
  - `revoke_token({id})` is `DELETE /tokens/{id}`: `revoked`, `already revoked`,
    or an error for an unknown id. It refuses the token the call itself carries
    (`revoke_token refuses the token this call carries (<prefix>)`), so First mate
    cannot lock the user out.
  Once a token exists, `mint_token` and `revoke_token` need the call to carry a
  bearer token (`a bearer token is needed to ...`), as the endpoints do; the first
  token is minted through the bootstrap door.
- **close_ticket** (#1170). `close_ticket({ticket, repo, reason, outcome})` closes a
  ticket's engine work item that has no running flow, as First mate's login, with the
  `outcome` the caller names: `completed` for a ticket whose work is done, `not_planned`
  when the user says "close #N" or "trash #N". All four are required (`ticket` an integer
  from 1, `repo` `owner/name` with no default, `reason` a non-blank string, `outcome`
  exactly `completed` or `not_planned`, with no default); a missing, wrong-typed or extra
  argument, a blank `reason`, an `outcome` that is missing or not one of the two
  (`"outcome" is required: "completed" or "not_planned", not <value>; nothing was
  changed`), or a repo that is not a target repo
  (`<repo> is not a target repo`) is a tool error. First mate only: a Smith is not
  listed it and its call is refused, and a call with no token login is refused as for
  `submit_ask` (`no GitHub login to close as …`). It reads the work item from the store
  (`WorkItemStore.forTicket`), never a GitHub issue: a number the engine holds no work
  item for is `<repo>#<n> is not a ticket: the engine holds no work item with that
  number; nothing was changed`; a closed one (`done` or `cancelled`) is `<repo>#<n> is
  already closed; nothing was changed`. The login must be trusted
  (`FrakworksEngine.trusted`), else `@<login> is not a trusted login`. A ticket with a
  running root flow (`FlowCanceller.running`, which covers an open incident) is refused
  naming each `instanceId` and pointing at `act_on_incident`'s `cancel`
  (frakworks-engine-diagnostics), which stops the flow and closes the ticket itself. No
  refusal writes anything. Then it appends a `conversation` entry by the login,
  `Closed as completed by @<login> through First mate: <reason>` or `Closed as not
  planned by @<login> through First mate: <reason>`, and closes the item as `done` or
  `cancelled` (`Tickets.end` with done or cancelled, `TicketState.ofReason` of `completed` or `not_planned`, which tells each
  `TicketStateListener`; nothing else a flow's close runs, no merge or PR check). A start deferred behind a blocker (`engine.deferred_issue`) is
  dropped once the close succeeds, and the entry says so. It returns `{ok, ticket, repo,
  closed_by, state_reason}`, `state_reason` the outcome named; an entry, close or row removal that fails is
  reported in `notes` (`ok` is false when the close failed). It never stops a flow
  (`TicketWriteToolsWorkItemTest`).
- **comment_ticket** (#1627). `comment_ticket({repo, ticket, text, closed?})` appends
  the user's comment, `text` trimmed, to a ticket's work item as a `conversation` entry
  authored by the login from the token; never a GitHub issue comment. First mate only,
  as `close_ticket`: a Smith is not listed it and its call is `unknown tool`; no login
  is `no GitHub login to comment as …`. Refused, writing nothing: a blank `text`; a repo
  that is not a target repo (the error lists the target repos); a number the engine
  holds no work item for; a closed ticket unless `closed: true`. It answers `{ok,
  ticket, repo, entry, posted_for}`, `entry` the new entry's id; a failed write is a
  tool error.
- **search_tickets.** `search_tickets({repo, text, state?, limit?})` (#1625): a
  target repo's tickets found by their words, from the engine's work items
  (`WorkItemStore.list`), never GitHub's issue search: an item matches when its
  description holds every word of `text`, ignoring case. Most recently updated
  first. `repo` and `text` are required: a missing or non-target `repo` is refused
  naming the target repos. `state` is `open` (default), `closed` (`done` or
  `cancelled`) or `all`; `limit` 1 to 50 (default 20). Each hit: `{number, title,
  state, labels, updatedAt}`. First mate's only: a Smith's call is refused naming
  its run, as for `read_repos`.
- **read_ticket_runs.** `read_ticket_runs({ticket, repo})` (#1162): a ticket's
  Smith runs, oldest first, one row per `smith-run` its flows ran, every mode.
  `repo` (owner/name) is required (refused with the no-default-repo text when
  missing), `ticket` a positive integer, any other argument refused by name; a
  repo that is not a target repo gets `<repo> is not a target repo`, a ticket
  with no process instance the text `No process instance is tied to <repo>#<n>:
  the engine has run nothing on it.` The steps are read as the ticket timeline
  reads them (`TicketTimelineController.runs`, the same `step`), joined by run
  id with `engine.smith_run_stats`. Each row: `runId` (the `smith-run` process
  instance id), `mode`, `round` (the timeline's), `account`, `model`, `effort`,
  `tier`, `started`, `ended` and `exitCode` (null while running), `outcome` (the
  result.json's outcome, else the run's `result`), `cost` (USD, null until
  collected) and `turns`. `account`, `tier` and `exitCode` come from the stats
  row, so they are null until the run is collected. A `review` or `security`
  row also has `findings`: each stamped finding the run returned (`id`, e.g.
  `review-2-1`, `review`, `file` when given, `ask`), from the
  `engine.review_finding` rows `read result` stamped with the run's
  `review_no`, so every round's list survives the flow's `findings` variable
  being overwritten; empty when it approved or passed. Not a Smith tool.

Probe (on the host use `localhost:8090`; in a sandbox, `$ENGINE_URL`):

```bash
curl -H "Authorization: Bearer $FRAKWORKS_TOKEN" -s "$ENGINE_URL/mcp/frakworks" -H 'Content-Type: application/json' \
  -d '{"jsonrpc":"2.0","id":1,"method":"tools/list"}' | jq '.result.tools[].name'
```

## The frakworks server in a Smith

Every Smith gets the engine's frakworks server ("The frakworks server", above) over
HTTP, so a Smith can file an ask itself instead of asking the user to (#382). Since
#539 the same `--mcp-config` also gives it the engine's `frakworks-engine-diagnostics`
("The diagnostics server", below); those two are
the engine's, and since #2449 the repo's own Smith-audience servers ride beside them
("A Smith's repo MCP servers", below).

- **Reach.** The sandbox is on `engine.smith.network` (compose's default network),
  where the engine is `engine:8080`. `engine.smith.engine-url`
  (`ENGINE_SMITH_ENGINE_URL`, compose sets `http://engine:8080`) becomes `ENGINE_URL` in
  the sandbox. With it set, `run-smith.sh` writes `$HOME/frakworks-mcp.json`
  (`smith/frakworks-mcp-config.sh`: two `http` servers, `$ENGINE_URL/mcp/frakworks`
  and `$ENGINE_URL/mcp/frakworks-engine-diagnostics`, with no header and no env) and
  passes `--mcp-config … --strict-mcp-config`. Without it, a run has no MCP server.
  The image carries no server code, and compose has no `desktop-src` build context.
  `settings.json` allows `mcp__frakworks` and `mcp__frakworks-engine-diagnostics`
  (no `mcp__hitlp`: the engine serves no HITLP, #2484); a repo server's tools are allowed per run (below).
  The tier map is not needed: the Smith role never resolves a tier.
- **MCP tool timeout (#2451).** The Smith image sets `MCP_TOOL_TIMEOUT=2100000`
  (35 minutes, in ms; `smith/Dockerfile`) for every MCP server of a run. Claude
  Code's default gives up on a tool call after about 60 s, and a human-gated call
  such as the ops server's `run` blocks until the user decides an
  escalated command on their phone (up to its `approvalDeadlineSeconds`, about
  1800 s, plus 30 s grace). Nothing else ends a run blocked that long:
  `run-smith.sh` puts no timeout on the work session (only the retro has
  `SMITH_RETRO_TIMEOUT`), and the Kubernetes Job's `activeDeadlineSeconds`
  (`engine.smith.k8s.active-deadline`) is 24 h.
- **Run identity tokens (#2453).** Beside `X-Frakworks-Run`, every `http`/`sse`
  entry a Smith gets carries `X-Frakworks-Run-Token: <jwt>`, a token the server can
  verify instead of trusting the claimed run id: an ES256 JWT (`RunTokens`,
  engine/modules/smith) naming the run (`jti`, `sub run:<id>`), its `repo`, `issue`
  and `mode`, with `aud` that one server's URL (so another server, or another
  repo's, refuses it), `scope frakworks.run.attest` and `exp` the run's longest life
  (`engine.smith.k8s.active-deadline`). It travels only inside `SMITH_MCP_SERVERS`
  (a row header of that name is dropped), never the brief, prompt or Job spec. The
  signing keys are rows of `engine.run_token_key` (V82), the private half sealed with
  `ENGINE_SECRETS_KEY`; without it the run's servers go without the token and one
  WARN line says so. A server verifies with `GET /.well-known/jwks.json` (offline:
  signature, `aud`, `exp`) and `POST /run-tokens/introspect {token, audience}`
  (RFC 7662: `active` only while the run's `engine.smith_session` is open and the
  run is not revoked), both without an engine token. A login rotates the key with
  `POST /run-tokens/rotate` (the old key stays published until its tokens expire)
  and revokes a run with `POST /run-tokens/{runId}/revoke`. A run token offered as
  a bearer token is refused with 401, so it can never answer or approve HITLP.
  The contract and a reference verifier: `docs/run-identity-token.md`.
- **A Smith's repo MCP servers (#2449).** A run also gets its repo's MCP servers
  whose `audience` is `smith` or `both` ("First mate's MCP servers": the rows of
  `engine.repo_mcp_server`, set in the desktop's Configure First Mate modal, by
  `PUT /repos/{owner}/{name}/mcp-servers/{server}` or by `add_repo_mcp_server`).
  At start, `SmithMcpServers.forRun` (engine/modules/smith) reads them in-process
  with `RepoMcpServers.config(repo, audience = SMITH)`, secrets opened, and builds
  one JSON object, name to `--mcp-config` entry. A row is left off and logged at
  WARN (`smith <run> (<repo>): repo MCP server <name> left off: <reason>`) when its
  name is `frakworks`, `frakworks-engine-diagnostics` or `hitlp` (the engine's
  own server's name) or its `http`/`sse` URL is a loopback, `localhost` or `.local`
  mDNS address (a Smith pod cannot reach it; a `*.cluster.local` service name is
  not local, it is how a Smith pod reaches a cluster instance); a `first-mate` row
  is never read. Every
  `http`/`sse` entry a Smith gets carries the header `X-Frakworks-Run: <run id>`
  (the run's `SMITH_ID`), set by the engine per run and replacing any row header of
  that name, so a server such as the ops server can name the run in HITLP
  `attested.onBehalfOf {kind: run, id}`; a `stdio` row passes through as it is. The
  object travels as `SMITH_MCP_SERVERS` in `sandboxEnv`, which both sandboxes keep
  out of their specs: on Kubernetes it is a key of the run's env Secret (`envSecret`,
  `SmithJobSpec.kt`; the Job spec, the brief and the prompt hold no server and no
  header value), on Docker a line of the container's env (`withEnv`). One INFO line
  per run names the servers (`smith <run> (<repo>): <n> repo MCP server(s): <names>`),
  never a header value. Without `ENGINE_SECRETS_KEY` a sealed header cannot be
  opened: that run gets no repo servers, one WARN line says so, and it still starts.
  In the image, `frakworks-mcp-config` merges the object under the engine's three
  (`$repo + engine`, so an engine name wins even if one got through), `run-smith.sh`
  logs `smith: repo MCP servers: <names>`, adds `--allowedTools mcp__<name>` per
  repo server beside the unchanged `/etc/smith/settings.json`, keeps
  `--strict-mcp-config`, writes the config file mode 0600 and unsets
  `SMITH_MCP_SERVERS` before `claude` starts. A run of a repo with no Smith-audience
  server starts exactly as before: the three servers, no `--allowedTools`. The
  cluster instance's token is a secret header on the row, set once by the user
  (`kubectl -n ops-server get secret ops-server-host -o jsonpath='{.data.token}'
  | base64 -d` into the row's `Authorization` header as `Bearer <token>`, ticked
  secret): the engine never reads a cluster Secret and needs no Role for one.
  No default URL or server exists
  anywhere: a repo with no row gives its Smiths nothing beyond the engine's three.
- **Who is filing.** The engine finds the Smith by the request's source address
  among the running containers labelled `frakworks.smith.run`, by their IP on the
  Smith network (`SmithSandbox.runAt`, `callerAt` in DockerContainers.kt), and lists
  it the Smith tools. A Smith cannot pick its address, because the sandbox drops
  every capability, `NET_RAW` and `NET_ADMIN` included. Its `submit_ask` files
  through `POST /intake`'s own path with no `submittedBy` and `authorize: false`, so
  the intake records `requestedByRun`, `requestedByIssue` and `requestedByRepo` from
  the container's labels (`GET /intake/<id>` shows them), is filed in the Smith's
  repo when that is a target repo, adds "Filed by Smith run `<id>` working #<n> in
  <repo>" to the ask item's Context, and waits for a trusted login to start its ask item.
  A Smith's address needs no token, and its `Authorization` header is ignored. `POST /intake`
  still refuses (403) a Smith that sends a `submittedBy`, `authorize: true`, or a
  `fromRun` for another run, a `fromRun` from an address with no Smith, and
  `POST /intake/<id>/answer` or `DELETE /intake/<id>` from a Smith. If Docker cannot
  be asked, `POST /intake` answers 503 and the MCP tool refuses, never "not a Smith".
- **The published port is loopback-only.** `compose.local.yaml` publishes the
  engine as `127.0.0.1:8090:8080`. Published on every interface, it let a sandbox reach
  the engine at the host's or the gateway's address. Docker's proxy then delivered the
  request from the network's gateway, not from the Smith's IP, so the Smith check found
  no Smith, and a Smith could authorize an intake by naming a submitter, or answer or
  cancel — and would now be listed First mate's tools. The host's loopback is out of a
  container's reach. Every caller of the engine (the desktop app, a session's MCP client)
  is on the host and uses `localhost:8090`. Do not publish the port beyond loopback
  while the API is unauthenticated: that route reopens.
- **The tools.** A Smith sees `submit_ask({ask})`, `note_friction`, `read_ci_log`, `read_run_log`, `read_version` (the engine's build version and commit, as `GET /version` answers, read-only), `list_private_docs` and `read_private_doc` (below), and a `work` run on a ticket `push_chunk` (below). `submit_ask` refuses any other
  argument (no `tier`: its header says "prescribed by the user", and a Smith is not the
  user; no `authorize`; no `repo`) and answers any other tool with a JSON-RPC error
  (-32602). A Smith answers no question: an answer is the next refiner's
  instructions, decided on the HITLP server by someone with access. `read_transcript` is not a Smith tool: a
  Smith must not read another session's transcript.
- **note_friction** (#876). `note_friction({kind, summary, evidence?, severity?})`
  records something that slowed the Smith down or seemed buggy, badly designed or
  confusing but is not worth a ticket (`bots/smith.md`, "Friction notes"). `kind` is
  `bug`, `design`, `docs`, `tooling` or `other`; `summary` is non-blank and at most
  2000 characters; `evidence` (the exact error, path, command or workaround) at most
  8000; `severity` `low`, `medium` or `high`. Any other argument or value is a tool
  error. The engine stamps the row with what the Smith cannot choose: the repo,
  ticket and run id from its container's labels, and the run's root flow from
  Operaton's history, with `source` `as-you-go`, into `engine.friction_note`
  (`friction/FrictionNotes.kt`), with the note's exact key (`signature`) in the same
  insert; the group comes afterwards, from the friction grouper's worker thread, never
  inside the call ("Friction notes"). It answers `{ok: true, id}`. A failed insert is a
  tool error that says to carry on; it writes nothing to GitHub and opens no need.
  A Smith's row has no `login`, and `repo` is not a Smith's argument (refused as any
  other): it is First mate's ("The frakworks server", `note_friction`), whose rows
  are `source` `first-mate`. The prompt names it whenever `ENGINE_URL` is set.
- **read_ci_log.** `read_ci_log({check_run, offset?})` answers the whole job log behind
  an engine check run in the Smith's own repo (`CheckRuns.log`, the same read the
  `ci-<round>-1` finding is built from, "Chore"), each line without its Actions
  timestamp and BuildKit step prefix (`CiLog.clean`), in pages of `PR_CHAR_BUDGET`
  characters as `read_ticket_pr` pages a file: a cut page's last line names the next
  `offset`. The finding names the id (`read_ci_log({check_run: <id>})`), which is also
  the number after `/job/` in the check's link. For when the finding's excerpt (the
  failing tests `CiLog.failures` knows, Gradle's and Kotlin's, and the last 80 lines)
  does not show the cause: a node test's `✖`, or a failure early in a long log. The
  repo is the run's own from its labels, never an argument; a run with no repo (a job
  sandbox), an unreadable log (GitHub's error, in the text) or an empty one is a tool
  error. First mate is not listed it (an unknown tool to it): a person opens the
  check's page.
- **read_run_log.** `read_run_log({runId})` answers the last 20000 characters of a
  Smith run's transcript, read where `read_transcript` reads it (`TranscriptReader`,
  bounded by the run's own start); `GET /runs/{id}/log` answers the same, as plain
  text, to the calling Smith (told by its source address, as the MCP endpoint tells
  it; any other caller is a 403). A Smith reads a run on its own ticket, or, refining
  an ask the doctor filed, a run on a stuck ticket that ask names: the run's root flow
  is the intake, and the `engine.doctor_bug` the intake files (`intake_id`) has a
  `doctor_sighting` on the ticket. Any other run is refused (a tool error, a 403); an
  unknown run is a 404. So a refiner on a doctor's "see its log" reads the log
  instead of inferring the cause from an exit code. A run whose transcript shipped no
  events, or (once ended) none at all, answers the sandbox's own output tail instead
  (`logTail`, the last 3900 characters of the pod's stdout/stderr), so a run that died
  before its first turn is still diagnosable; a run with neither is still the 404 "no
  log of run … was shipped".
- **list_private_docs, read_private_doc** (#1711). The private documents of the
  Smith's own run's repo ("Private documents", below): the list (name, length,
  updated), and one doc's text by name, paged as `read_ci_log` pages a log. Neither
  takes a repo: it is the run's own from its labels, so a Smith cannot read another
  repo's (a `repo` argument is refused). First mate (#1735) is listed them with a
  required `repo`, `list_private_docs({repo})` and `read_private_doc({repo, name,
  offset?})`: any target repo's, as the user's REST read; a missing or non-target
  `repo` is refused naming the target repos.
- **read_ticket** (#1735, `mcp/TicketTool.kt`; First mate's only). `read_ticket({repo,
  ticket, offset?})` answers a ticket's engine work item (see work-items.md, "First
  mate reads"), paged as `read_ticket_pr` pages a file; never a GitHub issue. A number
  the engine holds no work item for is refused. A missing or non-target `repo` is
  refused naming the target repos.
- **push_chunk** (#100043, `mcp/PushChunkTool.kt`). `push_chunk({bundle, message?})`
  pushes a work run's commits so far to its `smith/issue-<n>` branch mid-run, so a
  live parent (`docs/feature.md`, "await_children") sees the code before the run
  ends. `bundle` is a git bundle of `<START>..HEAD` on the branch (`git bundle
  create`), base64; `message` (at most 500 characters) is for the engine's log. The
  engine decodes it (refused above 32 MiB, before decoding), pushes it with the
  `GitPush.push` that `open/update pr` uses (fast-forward only; a bundle that
  rewrites pushed commits is refused with `CliGitPush.refuseRewrite`'s reason),
  publishes a `push` event for `refs/heads/smith/issue-<n>` (`docs/events.md`), so
  the parent's `await_children` wakes as a `push` with `ref` and `after` whether or
  not GitHub's webhook delivers, and answers `{head, paths}`: the pushed head and
  every path the branch changes against the default branch. Listed to a Smith whose
  run is mode `work` on a ticket only (the run's mode as `read_ticket_runs` shows
  it); any other mode, a job run, or a run the engine does not know is refused
  naming the run. The push is outside every flow step: no engine check wait,
  security review, review run or PR open or update follows it; those run once, on
  the run's done push (`docs/chore.md`, "open/update pr"). The sandbox gets no
  GitHub token for it: the chunk rides the MCP request body. `run-smith.sh`'s work
  prompt gives the two commands; `bots/smith.md` §6 says when to push one.
- **list_attachments, read_attachment** (#1730). The files the user attached to an
  answer in the Smith's own flow ("Answer attachments", below): the list (name,
  type, size), and one file by name, text paged as `read_private_doc` pages a doc,
  an image as an MCP `image` content block. Neither takes a flow: it is the root
  flow of the calling run (from history, by the run id the source address gives), so
  a Smith of another flow is told it has none, and First mate (or any non-Smith
  caller) is not listed them and gets `unknown tool`.
- **Isolation holds.** The sandbox env gains `ENGINE_URL`, a URL, and nothing else. The
  MCP config carries no credential, and there is no new mount, socket or privilege
  (`SandboxIsolationTest`, `RunSmithPromptTest`; both entries are URLs at
  `ENGINE_URL` and add nothing to the env). The engine API and VictoriaLogs were
  already reachable on the Smith network. The engine recognises a Smith and refuses
  it authorization, answers and cancels.

Probe, after `docker compose --profile tools build smith` and a restarted engine: any
real Smith run's log has a stream-json `init` event listing `mcp__frakworks__submit_ask`,
`mcp__frakworks__note_friction`, `mcp__frakworks__read_ci_log` and `mcp__frakworks__read_run_log`. While one runs:

```bash
docker exec <smith-container> sh -c 'curl -s "$ENGINE_URL/mcp/frakworks" -H "Content-Type: application/json" \
  -d "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/call\",\"params\":{\"name\":\"submit_ask\",\"arguments\":{\"ask\":\"probe: ignore\"}}}"'
curl -H "Authorization: Bearer $FRAKWORKS_TOKEN" -s "$ENGINE_URL/intake/<intakeId>" | jq '.variables | {requestedByRun, requestedByIssue, authorizedBy}'
docker exec <smith-container> sh -c 'curl -s "$ENGINE_URL/mcp/frakworks" -H "Content-Type: application/json" \
  -d "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/call\",\"params\":{\"name\":\"note_friction\",\"arguments\":{\"kind\":\"other\",\"summary\":\"probe: ignore\"}}}"'
                                                     # {"ok":true,"id":"<uuid>"}, then read it back with read_friction_notes
```

## Engine-served MCP servers

The engine serves MCP servers of its own, on its own port: `frakworks` (#541, "The
frakworks server"), `frakworks-engine-diagnostics` (#539, "The diagnostics
server") and `judge` ("The judge server"). Each is a Spring bean implementing `EngineMcpServer` (`mcp/EngineMcpServer.kt`):
a `name` (`[a-z0-9-]+`, unique; the engine fails to start on a duplicate), a
`version`, its `tools()` (or `tools(caller)`, when it lists per role, as frakworks
does), and `call(tool, args, caller)`. `EngineMcpController`
serves each at `POST /mcp/<name>`: MCP's streamable HTTP transport, stateless, with
no SDK and no second web stack (it is a plain Spring MVC controller). One JSON-RPC
message per POST. `initialize` reports `serverInfo {name, version}` and
`capabilities {tools: {}}`, echoing a protocol version it knows (`2025-11-25`, Claude Code's,
`2025-06-18`, `2025-03-26`, `2024-11-05`; any other gets the newest). `capabilities.tasks`
is advertised on a task server whatever version is negotiated. Only a `TaskServer` also
knows, and defaults to, `2026-07-28`: a client like Claude Code that asks for a
version the engine does not list refuses that one. A server that
implements `TaskServer` (the judge server) also gets the MCP
Tasks extension: `capabilities.tasks`, its `tools/call` answered as a task, and
`tasks/get`, `tasks/list`, `tasks/cancel`; on any other server those three are `-32601`. `ping`, `tools/list` and
`tools/call` are answered as `application/json`; a notification gets `202` and no
body; any other method is `-32601`, an unknown tool `-32602`, a bad body
`-32700`/`-32600`. There is no SSE stream and no session, so `GET` and `DELETE` are
`405`; an unknown name is `404`. A request with an `Origin` header is `403`: no
browser page may drive the servers (Claude Code sends none).

The endpoint is unauthenticated, like `POST /intake`. `initialize` and `tools/list`
answer anyone, so a probe works. For a `tools/list` or `tools/call` the controller finds the caller
by the request's source address (`SmithSandbox.runAt`, as in "The frakworks server
in a Smith") and passes it as an `McpCaller`: `Smith` (the container's labels),
`Nobody`, or `Unknown` when Docker cannot be asked. `Nobody` carries the
bearer token's login (First mate's; read for no other caller),
and every caller carries the HTTP request, so a server's in-process engine call
checks the same address again. Each server decides what that caller may do;
`Unknown` is never taken as `Nobody`.

**Adding one.** Write one more `@Component` implementing `EngineMcpServer`. Its path
is `/mcp/<its name>`. `EngineMcpController` and the other servers do not change. If
Smiths get it, add its entry next to the others in `smith/frakworks-mcp-config.sh`
(`{type: "http", url: ($url + "/mcp/<name>")}`, never a credential) and
`mcp__<name>` to `smith/settings.json`'s allow list, and extend `RunSmithPromptTest`.

## The judge server

`judge` (`mcp/JudgeMcpServer.kt`, #2431) serves `POST /mcp/judge`, an engine MCP server like the others (same
transport; no or a bad bearer token is the controller's `-32001`, as on every engine MCP server; an `Unknown` caller is
refused). Its one tool, `judge.command`, asks Jev whether an SSH command may run under a rubric (the ops repo's
three-layer SSH model, #2428). Arguments, all required: `rubric` (markdown, sections `## Principles`, `## Allow`,
`## Escalate`, `## Never`, `## Decisions`), `command`, `why`, `ticketGoal`, `priorCommands` and `files` (string
arrays), `host`, `requester`; optional `repo` and `rubricPath` (the rubric's plain relative `.md` path in `repo`,
which "Teaching the judge" commits under).

It answers `{verdict, confidence, citedPassage, reasoning, callId}`: `verdict` is `allow`, `escalate` or `deny`,
`confidence` 0..1, `citedPassage` a passage of the rubric verbatim. Jev (`SshJudge`, use case **`ssh-judge`**) answers
choice questions only and writes no free text, so the judge asks two: `verdict`, and `passage`, whose options are the
rubric's lines (headings dropped, a bullet or number marker cut, each tagged with its section; at most 255). The
cited passage is the picked line and is checked to be a substring of the rubric. `reasoning` is composed by the
engine: the verdict, its confidence and probabilities, and the passage with its section.

**Fails closed.** No answer, a verdict or passage confidence below the threshold, an unknown option, or a passage
that is not in the rubric answers `{noAnswer: true, reason, callId}` with no verdict, never `allow`; treat it as not
allowed. The threshold is the `engine.engine_setting` row `jev.ssh-judge.threshold` (no row: 0.8), passed to Jev so
the call's row records it.

Each call writes one `engine.jev_call` row, use case `ssh-judge`, repo the calling Smith's run repo, else the call's
`repo`. There is no default repo: a call from no Smith that names no `repo` is refused, naming the target repos,
and Jev is not asked. Its `action` is the verdict or `no-answer`, and the judgment (verdict, citedPassage,
reasoning, or the no-answer reason) is merged into its `response` under `judge` (`JevCalls.annotate`; no migration).
`read_jev_calls use_case: ssh-judge` lists them.

**Escalations are the engine's to file** (#2478). On verdict `escalate`, `judge.command` files a `human.approve` on the
standalone HITLP server through `HitlpRequester`: `action` and `payload {command, host, why}` (with its digest and a
one-use `scope`), deadline 24 h, `defaultOnTimeout: reject`, `requiresHuman: false`, no `requester`;
`asserted.onBehalfOf` the caller (`{kind: person, id, login}` for a login, `{kind: run, id}` for a Smith);
`asserted.recommendation {verdict: escalate, confidence, by {judge: ssh-judge, model, rubric}, reasons [citedPassage,
reasoning]}` (`rubric` is the `rubricPath`, or `inline`); `context.data.judge {callId, rubricPath, repo}`. The handle
is stored in `engine.hitlp_request` (kind `JUDGE`, `process_instance` `judge:<callId>`, `user_task_key` the caller,
`login:<login>` or `run:<runId>`, `judge_call_id` the call; `hitlp/JudgeEscalation.kt`), and the answer adds
`{taskId, decisionUrl, pollInterval}` to the verdict, or `escalationError` when it could not be filed (no HITLP server
configured, a caller with no login). `/mcp/judge` answers `tasks/get` and `tasks/cancel` by proxy to the HITLP server
for an escalation its caller filed there; any other task id, or a caller with no login, is `-32001`. `tasks/list`
answers no tasks.

## SSH verdicts

`GET /jev/ssh-verdicts[?repo=owner/name][&limit=n]` (`hitlp/SshVerdictsController.kt`, #2439; `limit` 1 to 500,
default 100) answers `{verdicts}`: the `ssh-judge` jev calls newest first, each `{id, called_at, repo, host, command,
verdict, confidence, cited_passage, reasoning, decision}`. `host` and `command` are the call's request state;
`verdict` (or `no-answer`), `confidence`, `cited_passage` and `reasoning` (the no-answer reason when there was no
verdict) the `judge` the judge merged into its response. `decision` is null unless the call was escalated: its
`judge_call_id` row in `engine.hitlp_request` names the task, read with `tasks/get` on the HITLP server (the stored record when the server cannot answer, else `status: unknown`). Then it is `{task_id,
status, outcome, decided_by, decided_at, recommendation}`, `outcome` the decision record's (`approved`, `rejected`, …)
once decided, `recommendation {verdict, confidence}` the request's `asserted.recommendation`, falling back to
`context.data.judge`. Read-only.
The desktop's Analytics > SSH verdicts page draws it.

## Rubric consolidation

A system-owned prompt job, **`judge rubric consolidation`**, signal `{"kind": "every", "every": 604800}` (weekly),
registered by `jobs/RubricConsolidation.kt` (#2439) on every target repo the judge has been taught in: a teach that
commits under a rubric's `## Decisions` ("Teaching the judge") registers it on its repo when that repo has no job of that
name yet, so nothing is set by hand and nothing is registered twice; the job row is the record. `list_jobs repo: <owner/name>` lists it. Like a refactor job it is `owner: system`: `DELETE /jobs/{id}` is 409, and it goes with its repo. Its run (a
`callable-smith` job run on the repo) reads the rubric's `## Decisions` (the lines "Teaching the judge" appends). With
lines, it opens one PR on the repo moving each into `## Allow`, `## Escalate` or `## Never` (a `deny` is a hard rule
in Never) and leaving `## Decisions` empty, and ends `done`. With none it opens no PR and ends with outcome
`nothing`, which `JobRuns.statusOf` records as run status `nothing` (`read_job_runs`).

## The HITLP requester

The engine is a HITLP requester only: it serves no HITLP. Its own server
(`/mcp/hitlp`, the `engine.hitlp_task` store, standing-approval rules, the
timeouts, the `hitlp.*` settings and `POST /hitlp/tasks/{id}/decision`) was
removed in #2484; V84 raised each open task and each rule as a NOTICE in the
deploy log, then dropped the tables. A Smith and First mate get no `hitlp` MCP
server. Tasks, rules and timeouts live on the HITLP server the engine is
configured with (`PUT /settings/hitlp`, below), and the desktop decides there.

The engine files its own flow waits as tasks on that
HITLP server (`codefrak-oss/hitlp` `spec/hitlp.md` 0.2, §4 to §7; #2443) and
continues each flow from the task's decision record.

- **The settings.** Every value about the HITLP server is in the engine's
  database, never in a property, an environment variable or a config file
  (#2500, part 1 of #2498; CLAUDE.md "A setting that moves leaves nothing
  behind"): `engine.hitlp_server` (one row: the requester's MCP URL and its
  bearer token sealed under `ENGINE_SECRETS_KEY` with the key's id and when it
  was set), V85. `hitlp/HitlpServerSettings.kt` is its only reader and writer.
  `GET /settings/hitlp` answers `{mcpUrl, requesterToken: {set, setAt}}` and
  never a secret. `PUT /settings/hitlp` takes any of `mcpUrl` (an absolute
  http(s) URL, validated as the public URL is; null clears) and
  `requesterToken` (a string; null clears). A Smith sandbox and a caller whose
  bearer token proves no login are 403. Each change
  publishes one `setting` frame named `engine.hitlp` whose value is
  `{changed: [field names]}`, no values. A deployment carries its values over
  once with `PUT /settings/hitlp`; nothing falls back to the environment.
- **The client.** `hitlp/HitlpRequester.kt`: an MCP Streamable HTTP client to
  the row's MCP URL with `Authorization: Bearer <requester token>`, both read
  from `HitlpServerSettings` at each call (so `configured` is live and a change
  needs no restart; a session made against another URL is dropped), timeouts
  `engine.hitlp.timeout` (PT10S). `tools/call` of `human.ask` / `human.approve`
  with `task: {ttl}` (the deadline's remaining milliseconds), `tasks/get` (and
  `tasks/result` for a server that answers the record only there) and
  `tasks/cancel`.
- **The requests.** `hitlp/HitlpFiling.kt`, from the posting delegates. Every
  ask wait (a Smith's question, the intake's duplicate question, a proposal's
  or design's decision, a research ack) is a `human.ask`: options from the ask
  block (the proposal verbs, the research verbs, or the block's own options)
  with a `responseSchema` enumerating them for the proposal and research verb
  menus (the flows branch on those); every other ask's schema is a plain
  `{type: string}`, so the user may answer a choice in their own words
  (the desktop's **Something else**, #100096); deadline
  now + 7 days, `defaultOnTimeout: fail`. A hitlp change's merge acceptance
  and a legacy feature acceptance (an instance from before #583) are a
  `human.approve`: `action` "merge PR #n of owner/repo" / "accept the merged
  feature #n of owner/repo", `payload {repo, pr, head}` and its digest,
  `scope {maxUses: 1, notAfter: deadline}`, deadline now + 24 h,
  `requiresHuman: true`. Each carries `context` with `summary` (an ask's
  `owner/repo#n`, an approve's question) and `data: {repo, ticket}`: the
  ticket's repo as `owner/name` and its issue number as an integer, so a
  surface reading the server directly places the task (#100013; the schema
  closes `context`, so they sit in `data`). Each carries the `asserted` block
  (why, on whose behalf, the ticket's lineage: the ticket as its `frakworks://ticket/<owner>/<name>/<n>` link) and is checked against the
  vendored schemas before it is sent.
- **The row.** `engine.hitlp_request` (`hitlp/HitlpRequests.kt`): the task id,
  the idempotency key `<processInstance>:<userTaskKey>:<nth>` (`:r<n>` on the
  n-th re-file), the user task it stands for, its kind, the next poll and, once
  terminal, the record. The flow keeps the task id in `hitlpTaskId`.
- **The stream.** `hitlp/HitlpTaskStream.kt` (#2463): no timer polls the
  server. On the lease holder only, the requester keeps the session's
  standalone SSE stream open (a GET on the MCP endpoint with the session id,
  `Accept: text/event-stream`); a `notifications/tasks/status` with a terminal
  status has `hitlp/HitlpPoller.kt` read `tasks/result`. On every (re)connect,
  and so on taking the lease, it reconciles once with `tasks/get` per open row,
  answering a task that ended while the stream was down. A drop or error is
  logged and reconnected after 1 s, doubled per failure up to 1 min; losing the
  lease closes the stream. A `setting` frame named `engine.hitlp` on the
  instance's bus runs its `sync()`: the stream restarts when the MCP URL
  changed and stops when it was cleared. The one fallback: a server that answers the GET
  with 405 offers no stream, logged once (WARN, naming the server), and the
  holder then passes over the due rows every 5 s (the spec's default
  `pollInterval`, so a row whose server names none is read as often as the
    spec expects; one naming a longer one is still read only at its
  `pollInterval`). A terminal record finishes the row and goes to the
  answerer, `HitlpAnswers` (`engine-flow-intake`). An `answered` ask record
  answers the `engine.intake_question` row and completes the user task with
  `answers` and `hitlpRecord`; a `timed_out` ask is filed again as `:r<n>`
  with one line on the ticket. An approve record completes the wait with the
  answer text `approved` or `rejected` (plus `: <reason>`) and
  `mergeAccepted` or the legacy `outcome`; `task-read-merge-acceptance` and
  `task-read-acceptance` read `hitlpRecord` first and the answer text only
  without one. A `timed_out` or `cancelled` approve record is not accepted
  (for a merge, a `merge-<round>-1` finding with the record's `reason`), never
  re-filed. Cancelling a flow cancels its open tasks on the server.
- **The decider's name.** The "<decider> answered:" comment and the answer
  row name `decidedBy`: `@<id> (<channel>)` for a person, `rule <id>`,
  `model <id> (<confidence>)` or `policy <id>`.
- **The hint.** The comment that posts a wait ends with "Decide it on <url>"
  (the handle's `_meta` `io.hitlp/decisionUrl`, else the first URL in its
  `statusMessage`) or "HITLP task <id> on the engine's HITLP server".
- **The incident.** With no MCP URL set (the incident names
  `PUT /settings/hitlp`), or a request the schemas refuse, the posting job
  fails into a `failedJob` incident naming why (a `GET /needs` row); the
  question row rolls back with it, so a retry once the server is set files it
  cleanly.

Each wait is decided on the HITLP server only: the engine's own answer path
is gone (#2443), and `HitlpAnswers` continues the flow from the decision record.

### No surface in the engine

The engine is a HITLP requester only, never a surface (#100013, part 1 of
#100011). The desktop app, its web version and the phone read and decide
tasks on the HITLP server's surface API directly, with their own credentials;
the engine relays nothing. The relay of #2514 (`HitlpSurface*`, the
`hitlp-surface` and HITLP task rows of `GET /needs`, `POST
/hitlp/surface/tasks/{id}/decision`, the surface URL and per-login surface
credentials, the per-repo `hitlpSurface` switch) was removed; V97 dropped
`engine.hitlp_surface_credential`, `hitlp_server.surface_url` and
`target_repo.hitlp_surface`. A surface finds the engine's own tasks' ticket in
their `context.data.repo` and `context.data.ticket`.

### Teaching the judge

A judge escalation is the `human.approve` `/mcp/judge` filed on the HITLP server ("The judge server", #2478). Its
request carries `context.data.judge` and the verdict in `asserted.recommendation`:

```json
{"asserted": {"recommendation": {"verdict": "escalate", "confidence": 0.62,
  "by": {"judge": "ssh-judge", "rubric": "ops/rubric/ssh.md"}}},
 "context": {"data": {"judge": {"callId": "j-7", "rubricPath": "ops/rubric/ssh.md", "repo": "example-org/example-repo"}}}}
```

A request without `asserted.recommendation` (the shape before #2478) carries the verdict in `context.data.judge`
itself: `{callId, verdict, confidence, rubricPath, repo}`; `RubricTeacher.Judge.of` reads either.
`verdict` is the judge's answer, or `noAnswer: true` when it gave none;
`confidence` is optional. The request's `payload` names `command`, `host` and
`why` (`reason`, or `context.summary`, stands in for `why`). The desktop draws
**Allow and teach** / **Deny and teach** on that card alone; a click decides
the task on the HITLP server (as Approve or Reject does), then
calls `POST /judge/teach` `{taskId | callId, decision: allow|deny, note?}`
(`JudgeTeachController`). The engine finds the task through `engine.hitlp_request`
(by `judge_call_id` for a `callId`), reads it with `tasks/get` on the HITLP
server, and takes the request from its decision
record. It appends one line as the last item under
`## Decisions` of `rubricPath` (`RubricTeacher`):

    - YYYY-MM-DD allow|deny `<command>` on <host> — <why>; judge: <verdict> (<confidence>), call <callId>[; note]

and commits it to `repo`'s default branch through the contents API
(`RepoContents.putFile`, with the blob SHA it read, so a concurrent change is
GitHub's 409 rather than a lost line), as the repo's own GitHub token, the
commit authored by the login the caller's engine token proved. Only an engine
token of purpose `first-mate` may teach: a Smith sandbox is 403 by its address
(its header is ignored), and any other token, a sign-in or `first-mate-pod`
token included, is 403. 404 for a task the engine filed no escalation for; 422
for a task that is no judge escalation or whose record has no request or no
`context.data.judge` (or without its `repo` or `rubricPath`), an escalation
filed for a Smith rather than a login (a Smith may not choose where the engine
commits), a `rubricPath` that is not a plain relative `.md` path (no
empty, `.` or `..` segment), a repo the engine has no token for, a rubric file the
default branch does not have, or a rubric without a `## Decisions` heading (the
engine never creates either); 409 while the task is not decided with the
matching outcome (`allow` is `approved`, `deny` is `rejected`); 502 when the
HITLP server cannot answer. It answers `{repo, path, commit, line}`.

## The public URL

The address users reach the engine and its web version (`/web/`) at: for a
Kubernetes-hosted engine its Ingress host (e.g. `https://engine.example.com`),
which lives in the site overlay and which the engine cannot see (#2253). It is
row `engine.public-url` of `engine.engine_setting` (V72), absent until set; no
property, environment variable or config file holds it, and nothing guesses it.

- **Read and set.** `GET /settings/public-url` answers `{publicUrl}` (null while
  unset). `PUT /settings/public-url` with `{"publicUrl": "https://<host>"}` sets
  it (a trailing `/` is dropped) and `{"publicUrl": null}` clears it; a relative,
  hostless or non-http(s) URL, a non-string or another key is a 400 that
  changes nothing. Both need a bearer token, like the rest of the API ("Tokens").
  A change publishes one `setting` frame named `engine.public-url`.
- **The tool.** `read_public_url()` on the diagnostics server (below), for a
  Smith and First mate alike: `{publicUrl}`, or while unset `{publicUrl: null,
  note: "no public URL is set; set it with PUT /settings/public-url ..."}`.
  First mate's allow-list has it (`observability/desktop/src/claude-session.js`).
- **When to set it.** After deploying behind an Ingress (`deploy/k8s/README.md`,
  "The overlay"). A Compose engine on host port 8090 may set it too.

## The diagnostics server

`frakworks-engine-diagnostics` (`mcp/DiagnosticsMcpServer.kt`), at
`POST $ENGINE_URL/mcp/frakworks-engine-diagnostics`. Retrying or cancelling an
incident is engine-side work, so it lives here and not in First mate's desktop
frakworks server, which had it as `act_on_incident` (#536) and lost it in #539.
Cancelling a ticket's flow that has no incident is engine-side too, so the same tool
does it (#558). Reading the doctor's history, `read_doctor_history` (#655), is engine
diagnostics as well: it moved here from the frakworks server in #658.

- **Clients.** Smiths, through their `--mcp-config` ("The frakworks server in a
  Smith"), and the desktop's First mate (#663), whose `--mcp-config` lists this
  server next to `frakworks`, with the same `Authorization` header. Later the
  doctor, an engine-side diagnostic actor, which will need an identity of its own
  before this server acts for it.
- **`read_public_url()`** (#2253). No arguments. The engine's public URL
  ("The public URL"), read-only, for the same callers as `read_tier_accounts`.
- **`sync_friction_fixes()`** (#1207). No arguments. Runs one pass of the
  friction fix sync and answers its report as `POST /friction/fixes/sync` does
  ("Friction notes", "Fixed by"): for a token login only; a
  Smith gets `a Smith sandbox (run <id>) may not run the friction fix sync`, an
  unplaced caller the usual `cannot tell whether the caller is a Smith
  sandbox`, and a pass already running an error saying so.
- **`restart_live_parent({repo, ticket})`** (#2572). Restarts the live parent
  of a split feature that fell back to one child at a time, as
  `POST /ticket/{owner}/{repo}/{n}/live-parent` does (docs/feature.md, "Live
  parent"): for a trusted token login only, never a Smith; refused with the
  endpoint's 409 reason unless the split is `live`, has fallen back and waits
  at `child closed`.
- **The tool.** `act_on_incident({action, ticket?, repo?, incident_id?, reason?})`,
  the arguments and results the desktop's had. `action` is `retry` or `cancel`. The
  target is addressed by exactly one of `ticket` (in `repo`, default the Smith's own
  repo; a login has none, so without `repo` it is refused: `no repo to look for
  ticket #<n> in`) or `incident_id` (a `/needs` incident row's `actionId`). A bad `action`, any
  other argument, a wrong type, both or neither address, `repo` with `incident_id`,
  or a `reason` on a retry is refused before anything is read. An engine refusal
  (403, 404, 400) comes back as the error text with its `status`.
  - `retry` needs an open incident. The server reads `Needs.list` and keeps only
    `incident` rows that match; none (`no open incident found for …`) or several is an
    error that acts on nothing, and several lists each `{repo, number, actionId}`. It
    calls `IncidentRetrier.retry`, what `POST /camunda/incidents/{id}/retry` runs, and
    returns `{ok, action, incident_id, ticket, repo, engine: {action, activityId,
    rootInstanceId}}`.
  - `cancel` needs a non-blank `reason` and cancels a root flow through
    `FlowCanceller.cancel`, what `DELETE /intake/{id}` runs: Smiths stopped, open PR
    closed unmerged, issue commented and closed as not planned. It returns `{ok,
    action, incident_id?, ticket, repo, instanceId, failedWrites}`. By `ticket` it
    finds the flow running on the ticket, with or without an incident (parked on a
    question, or on nothing, #558): the root process instance whose `repo` and `issue`
    variables are the ticket's (`FlowCanceller.running`, the lookup a split feature's
    children use), and `incident_id` is set when the ticket has one open incident row.
    No running flow is an error saying so and that the issue can instead be closed as
    not planned with the frakworks server's `close_ticket`; several is an error listing each `instanceId`; neither acts on
    anything. By `incident_id` it cancels the root flow the incident's root cause is on.
  - A **doctor run**'s incident is its ticket's (#1084). Its `/needs` row has the
    ticket's repo and number, so the address, `repo`, `ticket` and the access check
    are the ticket's: its flow's kind on its repo. Cancelling it (by `incident_id`)
    ends the doctor run alone, as `cancelled` ("Doctor", "A doctor run's own
    incident"). Nothing is closed, and the ticket's flow goes on.
- **Who may use it.** `act_on_incident`: a running Smith sandbox, found by address, and a caller that
  is no Smith but presents a valid bearer token (First mate, #663; the
  token `EngineMcpController` reads for `McpCaller.Nobody`, as the frakworks server
  takes it for `submit_ask`). Any other caller (no Smith and no
  login), or any caller while Docker cannot be asked, gets a tool error. A login acts
  as itself (`Actor.Login`), exactly as the HTTP endpoints below: `Access.trusted` for
  the login, the endpoints' own 403 text when refused, and GitHub
  comments naming `@<login>`. A Smith acts
  as itself (`Actor.Smith`, `camunda/Actor.kt`): only on flows whose `repo` is its own
  repo (its container's `frakworks.smith.repo` label), and never on the root flow its
  own run is under, a split feature's children included. GitHub comments name it:
  "Smith run `<id>` working <repo>#<n> retried …" / "… cancelled this <kind> …".
  The HTTP endpoints are unchanged. `POST /camunda/incidents/{id}/retry` and
  `DELETE /intake/{id}` take a login checked by `Access.trusted` (`Actor.Login`, with the
  same 403 text as before), and `DELETE /intake/{id}` still refuses a Smith sandbox.
- **read_doctor_history.** `read_doctor_history({ticket?, repo?})`
  (`mcp/DoctorHistoryTool.kt`), read-only, listed and answered for a Smith and for any
  other caller the engine placed alike (a caller while Docker cannot be asked gets a
  tool error): with no arguments, `{count, tickets}`, one row per ticket the doctor
  ran on (`repo`, `issue`, `runs`, `lastStartedAt`, `lastOutcome`), newest last run
  first; with `{ticket, repo?}`, `{repo, issue, count, runs}`, that ticket's runs
  newest first with `GET /doctor/history`'s per-run fields, `sighting` included (`repo` is required
  with `ticket`; no runs is `[]`). Both are `DoctorHistory`'s queries, the
  ones "Doctor"'s endpoint serves, reached in-process (`EngineDoctorReads`).
- **read_friction_notes** (#876). `read_friction_notes({repo?, ticket?, kind?, source?,
  since?, limit?})` (`mcp/FrictionNotesTool.kt`), read-only, for the same callers as
  `read_doctor_history`: `engine.friction_note` newest first, as `{count, notes}`, each
  note `{id, repo, issue, runId, processInstanceId, notedAt, source, kind, summary,
  evidence, severity, login, signature, groupId, groupedBy, jevCallId, groupedLogin, jevConfidence,
  twoThings, twoThingsCallId}` (`login` is First
  mate's caller, null on a Smith's or retro row; the rest are "Friction notes"' exact
  key, group, how it joined, the `friction-group` call, its confidence, and the two-things mark with its call, null while ungrouped or unmarked). Filters: `repo` (owner/name), `ticket`, `kind`, `source`
  (`as-you-go` from a Smith's `note_friction`, `retro` from "The retro", `first-mate`
  from First mate's `note_friction`), `since` (an ISO-8601
  instant or date); `limit` 1–500, default 100. A bad filter is a tool error.
- **read_job_attempts** (#1174). `read_job_attempts({incident_id} | {job_id})`
  (`mcp/JobAttemptsTool.kt`), read-only, for the same callers as `read_doctor_history`:
  `{jobId, attempts}`, every failed attempt of the job oldest first, the rows
  `GET /camunda/instances/{id}` serves as an incident's `attempts` ("Incidents"). By
  `incident_id` it reads an open `failedJob` incident's job (its `configuration`); an
  incident that is not open or not a `failedJob` is a tool error. Exactly one of the two;
  both, neither or any other argument is a tool error. A job with no failures, or none, is `[]`.
- **read_tier_accounts** (#1260). `read_tier_accounts()` (`mcp/TierAccountsTool.kt`),
  read-only, for the same callers as `read_doctor_history`: the rows `GET /tiers/accounts`
  answers ("AI accounts"), each tier's model, effort and the account a Smith run at it would
  get right now, or `null` and `blockedUntil`. It is `peek`, never `pick`, and so does not touch
  the rotation. It takes no arguments; any is a tool error.
- **read_review_predictions** (#1770). `read_review_predictions({ticket, repo?, since?, until?, limit?})`
  (`mcp/ReviewPredictionsTool.kt`), read-only, for a Smith and a logged-in caller alike: a
  ticket's Jev review-prediction calls ("Predict a review: Jev on the live review run"), the
  `engine.jev_call` rows with `use_case` `review-prediction` for that repo and issue.
  `repo` (`owner/name`) defaults to a Smith's own repo and is required for a login; `limit`
  is 1..500, default 100. It answers `{repo, issue, calls, predictions}`: `calls` the number
  of rows, `predictions` the rows newest first (at most `limit`), each `{callId, calledAt,
  verdict, confidence, value, noAnswer, latencyMs, model}`, with `verdict` (`approve` or
  `deny`), `confidence` and `value` (-100..100) as `PredictReviewController.predictionOf`
  computes them, and all three null with `noAnswer` true when the call has no answer. Never
  the request, the response or the account. Keyed by ticket, not run (the predictor records
  no process instance): tell runs apart by `since` (inclusive) and `until` (exclusive), ISO
  instants or dates, a run's start and end; they bound `calls` too (#2255). A kept prediction
  makes no call and no row. No rows is `calls` 0 and `[]`; a bad `ticket`, `repo`, `since`,
  `until` or `limit`, a login with no `repo` or an unlisted argument is a tool error naming it.
- **read_jev_calls** (#2255). `read_jev_calls({repo?, ticket?, process_instance?, use_case?,
  since?, until?, limit?})` (`mcp/JevCallsTool.kt`), read-only, for the same callers as
  `read_review_predictions`: the `engine.jev_call` rows of any use case, as `GET /jev/calls`
  filters them, newest first. `repo` defaults to a Smith's own repo and to every repo for a
  login; a `ticket` needs a repo; `process_instance` keeps one flow's calls; `since` is
  inclusive and `until` exclusive; `limit` is 1..500, default 100. It answers `{total, calls}`:
  `total` the count the filters keep, `calls` at most `limit` rows, each `{id, calledAt,
  useCase, processInstance, repo, issue, model, latencyMs, threshold, action, outcome, pick,
  confidence, noAnswer}`. Never the request, the response or the account. A bad argument, a
  `ticket` with no repo or an unlisted argument is a tool error naming it.
- **The job tools** (#1227, `mcp/JobsTool.kt`): the `/jobs` API ("Custom jobs and
  the SDK") as tools, one per endpoint, through `JobRegistrar`, the code
  `JobController` runs, so each answers and refuses with its endpoint's text (a
  refusal's HTTP status rides along as `status` in the error).
  - `list_jobs({repo?})` is `GET /jobs` (`GET /jobs?repo=` with `repo`): every job,
    oldest first, each `{id, name, kind, repo, config, signal, createdBy, createdAt,
    nextRunAt, lastRunAt, lastInstance}`. `read_job({id})` is `GET /jobs/{id}`: that
    row, or the error `no job <id>`. `read_job_runs({id, limit?})` is
    `GET /jobs/{id}/runs`: the job's runs, newest first ("Refactoring jobs", "Run
    history"). All three read, for the same callers as `read_doctor_history`.
  - `register_job({name, kind, repo, config, signal})` is `POST /jobs`: the created
    row, or the endpoint's 400 text (a bean or flow job's `config.jar` is a file name
    in `/var/lib/frakworks/jobs`; there is no upload). `run_job({id})` is
    `POST /jobs/{id}/execute`: `{instance}`. `delete_job({id})` is `DELETE /jobs/{id}`
    (a flow job's diagrams undeployed, a bean job's jar released): `{deleted: <id>}`.
    An unknown id is `no job <id>`, a running job `job <id> is running in <instance>`
    and is kept.
  - The three writes are the endpoints' writes: refused to a Smith (`a Smith sandbox
    (run <id>) may not register a job`, `execute a job`, `delete a job`), to an unplaced
    caller (`cannot tell whether the caller is a Smith sandbox: …`), and to a caller with
    no token. The trusted list is not consulted. The named login acts
    as itself: it is the job's `createdBy`.
- **Why no credential.** The engine's boundary for a Smith is the address check
  plus the loopback-only published port ("The published port is loopback-only",
  above); for a login it is the bearer token, as for the frakworks
  server's `submit_ask`. This server adds no credential of its own and no GitHub App permission, and loosens
  neither.

Probe (on the host use `localhost:8090`; in a sandbox, `$ENGINE_URL`):

```bash
curl -H "Authorization: Bearer $FRAKWORKS_TOKEN" -s -X POST "$ENGINE_URL/mcp/frakworks-engine-diagnostics" -H 'Content-Type: application/json' \
  -H 'Accept: application/json, text/event-stream' \
  -d '{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-06-18","capabilities":{},"clientInfo":{"name":"probe","version":"0"}}}' \
  | jq .result.serverInfo                     # {"name":"frakworks-engine-diagnostics","version":"1"}
curl -H "Authorization: Bearer $FRAKWORKS_TOKEN" -s -X POST "$ENGINE_URL/mcp/frakworks-engine-diagnostics" -H 'Content-Type: application/json' \
  -d '{"jsonrpc":"2.0","id":2,"method":"tools/list"}' | jq '[.result.tools[].name]'   # ["act_on_incident",…,"sync_friction_fixes","restart_live_parent","list_jobs","read_job","register_job","run_job","delete_job"]
curl -H "Authorization: Bearer $FRAKWORKS_TOKEN" -s -X POST "$ENGINE_URL/mcp/frakworks-engine-diagnostics" -H 'Content-Type: application/json' \
  -d '{"jsonrpc":"2.0","id":3,"method":"tools/call","params":{"name":"read_friction_notes","arguments":{"since":"2026-10-01","limit":5}}}' \
  | jq -r '.result.content[0].text | fromjson | .count'
```

A `tools/call` from the host answers `frakworks-engine-diagnostics acts only for a
Smith sandbox; … is none`, which is the rule working.

**Portability.** The engine only needs `/var/run/docker.sock` mounted, which
is the same on Colima, Docker Desktop (macOS, Windows, Linux), OrbStack,
Rancher Desktop with dockerd, and native Linux; each one presents that path
inside its VM. Docker Desktop's *Enhanced Container Isolation* blocks socket
mounts by default and must allow the engine image. Podman needs
`/run/podman/podman.sock` and `ENGINE_DOCKER_HOST` pointed at it. The Java
client is docker-java's zero-dependency transport: the Apache HttpClient one
loses unix-socket support under the HttpClient 5.5 that Spring Boot 4 pins.

The `sibling container` is the Docker analogue of a Kubernetes Job; if the
stack ever moves to Kubernetes, `SmithSandbox` gets a second implementation
and nothing else changes.
