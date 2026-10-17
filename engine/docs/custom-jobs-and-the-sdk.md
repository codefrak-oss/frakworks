# Custom jobs and the SDK

Part of the engine docs: [index](../README.md), [engine/README.md](../../README.md).

A plugin jar adds its own jobs, doctors and diagrams to the engine without a
change here. `sdk/` is what it programs against: a Kotlin module with no Spring
or Operaton dependency, built as `:sdk` of the engine build
(`engine/settings.gradle.kts` includes it from `../sdk`; the Dockerfile copies
it to `/sdk` from the `root` build context every build already passes). Package
`com.codefrak.sdk` holds the interfaces; package `com.codefrak.engine.sdk` holds
what a loaded plugin receives for each. Part 1 of #1212 built every piece
below; part 2 added the job registrar ("The job registrar", below): jobs are
stored and started on their signals, and a prompt job runs end to end; part 3
the plugin loader ("The plugin loader", below): a bean job's jar is loaded into
the engine's JVM at registration and its `FrakJob` runs on the job's signal;
part 4 the flow jobs ("Flow jobs", below): a jar's diagrams are validated and
deployed at registration and the named process runs on the job's signal.
The `/jobs` API is also reachable as the diagnostics server's `list_jobs`,
`read_job`, `register_job`, `run_job` and `delete_job` (#1227, "The diagnostics
server"), with the same answers and the same authorization.

| SDK (`com.codefrak.sdk`) | What it is | Engine side (`com.codefrak.engine.sdk`) |
|---|---|---|
| `FrakJob` | `execute(ctx: JobContext)`: one run of a job | `RunBeanJobDelegate` (`task-run-bean-job` of `bpmn/job.bpmn`) calls the bean the job's row names, from the jar `PluginJars` loaded; an exception is the run's outcome `failed: <message>`, no incident |
| `JobContext` | `jobId`, `repo`, `config`, `getVariable`, `setVariable`: the run's id, target repo, stored configuration and flow variables | `ExecutionJobContext` over the `job` instance's execution: the row's id, repo and config as a map, the instance's variables |
| `Tickets` | `create(repo, title, body, labels)`, `read(repo, number)`, `update(repo, number, title?, body?, labels?)`, `close(repo, number, reason)`: every call names its repo, there is no default | `EngineTickets` over the engine's work items (`Tickets`), no GitHub call |
| `Friction` | `note(kind, summary, evidence?, severity?)`, `list(filter)` | `EngineFriction` over `FrictionLog`: source `as-you-go`, stamped by the engine with the caller's job id, its run when it has one, and a null ticket (`EngineFrictions.forJob`) |
| `Doctor` | `diagnose(incident: Incident): Diagnosis?`; `Diagnosis(summary, remedy)`, `Remedy { RETRY, NONE }` | `PluginDoctors`: every `Doctor` bean, asked by `Doctor.tick` (below) |
| `Jev` | `decide(useCase, questions, state, threshold?)` with the SDK's `JevQuestion`, `JevAnswer`, `JevDecision` | `EngineJev` over `JevDecider`: every call is an `engine.jev_call` row with the caller's repo (`EngineJevs.forJob`) |
| `Metrics` | `counter(name, labels)`, `gauge(name, labels, supplier)` | `EngineMetrics`: a registry whose series `GET /metrics` appends after `smith_run_active`, named `frak_plugin_<name>` |
| `CallableSmith` | `PROCESS_KEY = "callable-smith"`, `PROMPT`, `REPO`, `RESULT` | `bpmn/callable-smith.bpmn` (below) |

**The plugin doctor.** On each tick, before it starts a Smith doctor run for a
stuck ticket ("Doctor"), the doctor maps the ticket's first open incident to an
SDK `Incident` (id, process instance and key, activity, type, message, job id,
repo, issue) and asks `PluginDoctors`: each `com.codefrak.sdk.Doctor` bean in
order, the first non-null diagnosis wins, one that throws is logged and
skipped. A diagnosis is recorded in `engine.doctor_run` as a finished run
(`DoctorHistory.record`: a fresh id, started and ended now) with target
`plugin:<bean name>`: a `RETRY` retries the incident as the engine's doctor
(`IncidentRetrier.retryAsSystem`), outcome `applied` (or `failed` with the
reason when the retry throws); `NONE` is remedy and outcome `none`. Either way
no Smith doctor starts for that episode, and a `setting` `doctor` frame says
`ended` with the target. With no answer the Smith doctor runs as before.

**Plugin metrics.** `EngineMetrics` keeps counters and gauges by name and
labels; `SmithTelemetry.metrics` renders them (HELP, TYPE, one line per series)
after the `smith_run_active` lines, so the fleet's scrape of `GET /metrics`
picks them up with the engine's own. A name is `[a-zA-Z_:][a-zA-Z0-9_:]*`; a
gauge is read at each scrape.

**`callable-smith`** (`bpmn/callable-smith.bpmn`) is the one reusable diagram a
plugin's own calls: in `prompt` and `repo` (and `jobId` when the caller has
one; `model`, `effort`, `dryRun`, `account` as for any Smith run), it prepares
the job (`prepareCallableSmith`: `jobId` from the caller or the instance's id,
mode `job`, a missing prompt or repo refused before any sandbox starts), calls
`reusable-smith-run` with `in variables="all"` and mode `job`, and sets `result`
(`setCallableResult`: the run's `result.json` as text, or as bytes when it is
past the 4000 characters a String variable holds, which a refactoring run's
with a few full ticket bodies is; `Brief.text` reads either) and `outcome`
before its end event. `prompt` may likewise be given as text or as bytes
(`Brief.textValue`); `prepareCallableSmith` and the brief take both. A plugin's diagram calls it as
`modules/app/src/test/resources/bpmn/plugin-test.bpmn` does (`CallableSmithProcessTest`).

**Mode `job`** is the Smith run `callable-smith` makes ("Smith runs"): a run
with no issue. `Brief.prompt` carries the prompt; `SmithSpec.issue` is null and
`SmithSpec.jobId` set; the sandbox is `smith-job-<jobId>-<run8>`, labelled
`frakworks.smith.job` instead of `frakworks.smith.issue`, with `SMITH_JOB` in
its environment and no `SMITH_ISSUE`; its telemetry rows and `smith_run_active`
series carry `job="<jobId>"` with an empty `issue`, and its filename is
`job-<jobId>-job-<runId>.log`; the `session` frames carry `jobId` and a null
`number`; `engine.smith_session` and `engine.smith_run_stats` take a null
`issue` (V45). `run-smith.sh` clones the repo on `job/<jobId>`, reads no ticket,
and prompts with the brief's `prompt` verbatim followed by the common sandbox
rules (the framework docs, no GitHub writes, the product in `/work/out`, the
repo's own instructions, the build rule, `note_friction`); the result is
`schemas/job.schema.json`, `{"outcome": "done"|"failed", "summary"}`; no bundle
and no retro. Every GitHub write in `smith-run` and `reusable-smith-run` (the
rerun note) skips a run with no issue, and a `note_friction` from such a
sandbox is stamped with the job id and a null ticket (`friction_note.job_id`).

**The plugin jar.** A plugin is a jar with a `META-INF/MANIFEST.MF` attribute
`Frak-Plugin-Package: <package>`; its beans (`FrakJob`s, `Doctor`s, delegates)
live in that package, and its diagrams under `bpmn/` in the jar. The engine
loads it from the fixed directory `/var/lib/frakworks/jobs` inside its
container (`PluginJars.DIRECTORY`), the named volume `engine-jobs` on the
`engine` service of `engine/compose.local.yaml` (`ConfigConsistencyTest` holds
the mount and its top-level declaration): the operator copies the jar in
(`docker compose cp plugin.jar engine:/var/lib/frakworks/jobs/`). There is no
upload endpoint and no setting for the path. A flow job deploys the jar's
`bpmn/*.bpmn` ("Flow jobs", below).

**The plugin loader** (`jobs/PluginJars.kt`). A bean job's `config.jar` is a
file name in that directory, never a path: a separator or `..` is refused by
`POST /jobs` (400 `config.jar is a file name in /var/lib/frakworks/jobs, not a
path`) before anything else is checked. `PluginJars.acquire(jar, bean, jobId)` loads
the jar once per file name and sha-256: a `URLClassLoader` over the file whose
parent is the engine's loader, and an `AnnotationConfigApplicationContext`
whose parent is the `SdkContext` and whose class loader is that one, scanning
the package `Frak-Plugin-Package` names. `SdkContext` (`jobs/SdkContext.kt`) is
one context built at engine start holding only the `com.codefrak.engine.sdk` implementations
(`EngineTickets`, an `EngineFriction`, an `EngineJev`, `EngineMetrics`, the
`PluginDoctors` registrar), so a plugin's beans autowire the SDK's `Tickets`,
`Friction`, `Jev`, `Metrics` and nothing else of the engine's context by
injection. Registration refuses, as a 400 with the reason in the body, a
missing file (`jar <name> is not in /var/lib/frakworks/jobs`), one that cannot
be read, a missing attribute (`jar <name> has no Frak-Plugin-Package manifest
attribute`), a context that fails to refresh (`jar <name> failed to load: <root
cause>`), a missing bean (`jar <name> has no bean <bean>`) and one that is not
a `FrakJob` (`bean <bean> of <name> is a <class>, not a FrakJob`); every
refusal closes what the call opened, and the row is inserted only after the
jar loaded, so nothing is left behind. Jobs on the same jar share the loaded
context (`LoadedJar.jobIds`); `DELETE /jobs/{id}` releases the job's hold and
the last job's delete closes the context and the loader. Every `Doctor` bean
of a loaded jar is registered with `PluginDoctors` as `<jar>:<bean>` on load
and removed on close; the registered doctors are asked before the engine
context's own. A jar rewritten on disk under a loaded name is reloaded (new
sha) once no job holds the old one; until then the old stays, with a WARN. A
loaded context does not survive an engine restart: the first run after one
loads the jar again (`RunBeanJobDelegate` calls `acquire` too, a no-op while
the job holds the jar), and a jar gone by then is that run's `failed: jar
<name> is not in /var/lib/frakworks/jobs`. Removing a target repo cascades
its job rows; a context those rows held is closed at the next engine stop.
`PluginJars.useDirectory` is the tests' override of the directory (a
`PluginJarFixture` jar written with `JarOutputStream` from the
`com.codefrak.fixtureplugin` classes compiled under `modules/app/src/test`); there is no
property.

**Flow jobs** (`jobs/PluginFlows.kt`, `jobs/PluginBeanResolver.kt`,
`bpmn/FlowRules.kt`). A flow job's config is `{"jar": "<file>", "process":
"<key>"}`: a jar in the directory above and the id of an executable process
among its `bpmn/*.bpmn` entries (that path, no deeper; `PluginJars.DIAGRAM_ENTRY`).
`POST /jobs` with kind `flow` loads the jar as a bean job's is loaded
(`PluginJars.acquireContext`: the same refusals up to the bean, the job holding
the entry, a jar shared with the bean jobs on it), reads every diagram, holds each
to the five diagram rules ("Diagram rules") and the plugin rules below
(`FlowRules.pluginProblems` against the jar's own beans and classes), checks
`process` is one of the jar's executable processes and that no deployment defines
any of the jar's keys yet (a key belongs to one deployment; the refusal names the
holder), and deploys them all in one deployment named `job:<id>` with source
`plugin`, in the same transaction as the row insert (`@Transactional`: Operaton
runs its commands on the application's transaction manager). A refusal is a 400
whose body lists every problem of every diagram, one per line, prefixed with the
entry name (`bpmn/nightly.bpmn: task-x: delegateExpression "${nobody}" names
nobody, which is no bean of the jar`), and leaves no row, no deployment and no
loaded context. The plugin rules: no script task and no script listener; every
`operaton:delegateExpression`, `operaton:expression` (its leading identifier) and
`operaton:class` on a task or a listener binds in the jar's own context or loader
and not in the engine's; no other expression (a condition, a `sourceExpression`,
a field, an input or output parameter) names an engine bean; every `callActivity`
calls `callable-smith` and none of its `operaton:in` has `variables="all"`: the
variables are named, `prompt`, `repo`, `jobId` in, `result` (and `outcome`) out,
as `modules/app/src/test/resources/bpmn/plugin-test.bpmn` and the test fixture
`modules/app/src/test/resources/plugin-flows/fixture-flow.bpmn.xml` do.

*The bind rule.* `PluginBeanResolver` is a `jakarta.el.ELResolver` installed by
`PluginFlowsPlugin`, a `ProcessEnginePlugin` whose `preInit` replaces the
configuration's `SpringExpressionManager` (the Spring starter's factory bean sets
one before `init()`) with a subclass whose EL resolver is the engine's own
composite followed by the plugin resolver. So `${name}` in any diagram resolves
first as it always did (the variables, the engine's application context, the
rest); only a name nothing else resolved reaches the plugin resolver, which finds
the executing definition's deployment through `Context.getBpmnExecutionContext()`,
and for a `job:<id>` deployment answers that job's loaded jar's own bean of the
name (`containsLocalBean`: not the SDK parent's), loading the jar from the job's
row when nothing holds it yet (an in-flight instance after a restart). A jar's
`JavaDelegate`s, `ExecutionListener`s and `TaskListener`s bind this way, in that
jar's flows only. A name present in both the engine and a plugin is the engine's
at run time, which is why `FlowRules` refuses the diagram for it (`names
noteJobEnded, an engine bean; a plugin diagram binds only the jar's own beans`).

*A run.* `job`'s flow branch (`bpmn/job.bpmn`): `task-prepare-flow-job`
(`PrepareFlowJobDelegate`: the row by `jobId`, `acquireContext` for the job, and
`jobProcessKey` set to the config's `process`; a job or jar gone is an incident on
the task) → `call-plugin-flow` (`calledElement="${jobProcessKey}"`, `in
variables="all"`: the plugin process receives `jobId`, `repo`, `kind`, `name`,
`config` and `jobProcessKey`; out `result` and `outcome` when it set them) →
`task-note-job-ended`. The `every` and `execute` signals start it as any job.

*The undeploy guard.* `DELETE /jobs/{id}` of a flow job is 409 while a `job`
instance of it runs (as for every job) and 409 `job <id>'s flow is running in N
instance(s) of job:<id>` while any instance of the deployment's definitions runs
(`PluginFlows.undeploy`); otherwise it deletes the deployment with cascade false
(finished history stays), the row, and releases the jar. `ProcessMigrator` never
migrates a definition of a `job:` deployment ("Process migration"): a plugin's
flows are the job's to version, by delete and re-register.

**Security.** Code loaded from a jar runs in the engine's JVM. It can reach
the sealed store (`ENGINE_SECRETS_KEY`, `AiAccounts`), every target repo's
token, the Docker socket and the database by reflection or static access,
whatever its Spring context offers: the SDK-only parent context stops
accidental injection, not a hostile author. So only the operator's own jars
belong in `/var/lib/frakworks/jobs`, and registering one needs a named caller
login. The plugin author is the operator. Third-party code is not for
this path (an external task worker stays the route for it).

## The job registrar

A **custom job** is a row of `engine.job` (V46): what to run, in which target
repo, and on what signal. The engine starts it with its signal, never on an
issue: a job's run has no ticket, writes nothing to GitHub, and its runs never
overlap (a due tick is skipped and an execute is 409 while the last run is
active). Package `jobs/`.

| `engine.job` column | What |
|---|---|
| `id` | a UUID the engine mints on `POST /jobs` |
| `name` | the job's name, as the frames and logs say it |
| `kind` | `prompt`, `bean`, `flow` or `refactor` (a CHECK holds it; V55 widened it): `JobKind`. `refactor` is never registered through `POST /jobs`: "Refactoring jobs" |
| `owner` | `user` (a job registered through `POST /jobs`; every row from before V55) or `system` (the engine's own, today a repo's refactoring job): `JobOwner`. `GET /jobs` shows it; `DELETE /jobs/{id}` refuses a system job |
| `repo` | the target repo (`engine.target_repo.full_name`, cascade on its removal); required, there is no default repo |
| `config` | JSON as TEXT, exactly the kind's keys, each a non-blank string (a refactor job's is `{}`: its run reads `engine.refactor_config`): a prompt job's `{"prompt": markdown}`; a bean job's `{"jar", "bean"}` (the jar's file name in `/var/lib/frakworks/jobs` and the `FrakJob` bean's name, loaded at registration: "The plugin loader"); a flow job's `{"jar", "process"}` (the jar's file name and the key of one of its `bpmn/*.bpmn` processes, validated and deployed at registration: "Flow jobs") |
| `signal` | JSON as TEXT with a `kind`: `{"kind": "every", "every": N}`, `{"kind": "execute"}` or `{"kind": "at", "at": "<instant>"}` |
| `created_by` | the token login that registered it |
| `created_at`, `next_run_at`, `last_run_at`, `last_instance` | when; when the ticker next starts it (null unless the signal is `every`, or `at` before its run); the last run's start and its `job` process instance |

**Registering one needs a named caller.** `POST /jobs`, `DELETE /jobs/{id}` and
`POST /jobs/{id}/execute` act as the bearer token's login ("Tokens"); without a valid token they are 401. The
trusted list does not gate jobs. A Smith sandbox is refused outright by its
source address, as the other write endpoints refuse it (403; 503 when Docker
cannot be asked).
The reads (`GET /jobs`, `GET /jobs/{id}`) are open like every read.

**The signals** (`jobs/JobSignals.kt`, a sealed `Signal` parsed from the JSON by
its `kind`; an unknown kind is refused naming it and the kinds there are):

- `every`: `{"kind": "every", "every": N}`, a run every N seconds, N at least
  1. `next_run_at` is set to now + N on registration and after every start.
- `execute`: `{"kind": "execute"}`, runs only on `POST /jobs/{id}/execute`.
  `next_run_at` stays null. (An `every` job can be executed by hand too.)
- `at`: `{"kind": "at", "at": "<ISO-8601 instant>"}`, one run at that instant
  ("do this later"), started by the ticker at or right after it and never
  again. `next_run_at` is the instant until it runs and null after. A malformed
  instant, a missing or non-string `at`, or other keys are 400; so is an
  instant that is not after now (`at <instant> is in the past`). An execute
  before the instant leaves the scheduled run in place. A fired `at` job is
  kept as history (`lastRunAt` and `lastInstance` set, `nextRunAt` null) until
  `DELETE /jobs/{id}`. From an interactive session, `register_job` on the
  engine's MCP with a `prompt` job and this signal schedules a prompt for later.

Adding a kind is a `Signal` subclass and a branch in `Signal.parse`; the column
is TEXT, so no migration.

**The ticker** (`jobs/JobTicker.kt`): once a second (`JobTickClock`,
`@Scheduled(fixedDelay = 1000)`) `JobTicker.tick` reads every job whose
`next_run_at <= now`. One whose last `job` instance is still active is skipped
(logged `job <name> (<id>) skipped: <instance> still runs`); every due job, run
or skipped, gets `next_run_at = now + every`, so a skipped run is not caught up
and ticks missed while the engine was down collapse to one run. Each job fails
on its own, as a WARN. An `at` job is the exception to the skip: it keeps its
`next_run_at` and is tried again on the next tick, since its one run would
otherwise be lost. `engine.jobs.tick-enabled=false` leaves the clock bean
out: the test properties set it, as they set the doctor's, and `JobTickerTest`
calls `tick` by hand with its clock stepped. One engine instance ticks; a
second one would need a lease, which this does not have.

**A run** is `JobStarter.start`: `runtime.startProcessInstanceByKey("job", …)`
with `jobId`, `repo`, `kind`, `name`, `config` (the JSON text) and, for a
prompt job, `prompt` from the config, then `last_run_at`, `last_instance` and
`next_run_at` stamped on the row. `bpmn/job.bpmn`, a root process like any
other (`ProcessMigrator` migrates it with the rest, no change there):
`start-job` → `task-note-job-started` (`noteJobStarted`, async, so the start
returns at once: the `job` frame `started`) → `gateway-kind` → by kind:
`prompt`: `call-callable-smith` (`callable-smith`, above: in `prompt`, `repo`,
`jobId`, and `model`, `effort`, `dryRun`, `account` when the caller set them;
out `result` and `outcome`) | `bean`: `task-run-bean-job` (`runBeanJob`,
`RunBeanJobDelegate`: the job's row by `jobId`, its `FrakJob` from
`PluginJars.acquire` by the row's `jar` and `bean`, run with an
`ExecutionJobContext` over the execution; an exception, the job's or the
loader's, is logged and sets `outcome` to `failed: <message>`, and the flow
goes on, no incident) | `flow`: `task-prepare-flow-job` (`prepareFlowJob`,
`PrepareFlowJobDelegate`: the row by `jobId`, its jar held for the job, and
`jobProcessKey` set to the config's `process`) → `call-plugin-flow`
(`calledElement="${jobProcessKey}"`, the job's deployed process with every
variable of the instance; out `result` and `outcome`: "Flow jobs") →
`task-note-job-ended` (`noteJobEnded`: the `job` frame `ended` with
`outcome`, the callable-smith's for a prompt job, `failed: <message>` for a
bean job that threw, `done` when the branch set none) → `end-job`.

```
curl -H "Authorization: Bearer $FRAKWORKS_TOKEN" -XPOST localhost:8090/jobs -H 'content-type: application/json' \
  -d '{"name":"docs count","kind":"prompt","repo":"acme/widgets","config":{"prompt":"Count the files under docs/."},"signal":{"kind":"every","every":3600}}'
curl -H "Authorization: Bearer $FRAKWORKS_TOKEN" -XPOST localhost:8090/jobs -H 'content-type: application/json' \
  -d '{"name":"sweep","kind":"bean","repo":"acme/widgets","config":{"jar":"sweeper.jar","bean":"sweeper"},"signal":{"kind":"execute"}}'
curl -H "Authorization: Bearer $FRAKWORKS_TOKEN" 'localhost:8090/jobs?repo=acme/widgets'
curl -H "Authorization: Bearer $FRAKWORKS_TOKEN" -XPOST localhost:8090/jobs/<id>/execute       # {"instance": "..."}
curl -H "Authorization: Bearer $FRAKWORKS_TOKEN" -XDELETE localhost:8090/jobs/<id>
```

**Building against the SDK.** `cd engine && gradle :sdk:jar` builds
`sdk/build/libs/sdk-0.0.1.jar`. A plugin build either includes the engine build
(`includeBuild("../frakworks/engine")` in its `settings.gradle.kts`, then
`implementation("com.codefrak:sdk")`) or depends on the jar file
(`implementation(files(".../sdk/build/libs/sdk-0.0.1.jar"))`). Nothing is
published and no token is needed.
