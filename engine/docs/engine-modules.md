# Engine modules

Part of the engine docs: [index](../README.md), [engine/README.md](../../README.md).

The engine is one Spring Boot app built from a multi-module Gradle build (#1557's split). Every module is under
`modules/`, included in `settings.gradle.kts` as `:engine-<name>` and `:engine-flow-<name>`; the root project holds no
code, only what the modules share (the plugin versions, the toolchain, the Spring Boot BOM, the compiler flags).
`engine-app` is the one application: its boot jar is the image (`Dockerfile`). Each module keeps the
`com.codefrak.engine.*` packages its code had, so a package can span modules, and component scanning from
`EngineApplication`, bean names and BPMN delegate expressions are unchanged.

**The layer rule.** The modules form layers, lowest first: `common`, `github`, `events`, `accounts`, `jev`, `smith`,
`incidents`, `hitlp`, `firstmate` (layers 0-2 below), the flow modules (one layer), `plugins`, `api`, `app`. A module uses its own classes
and those of lower layers only, never an upper layer's, and a flow module never uses another flow module's: what two
flows share is a contract in a lower layer. Gradle holds it (each build file names only lower modules), and two tests
in `engine-app` check it: `EngineModulesTest` resolves every import of each module against what the modules it may
use declare, and `LayerRuleTest` (ArchUnit) checks every compiled class's dependencies by the module it was loaded
from, with fixture classes it places by hand to show the rule fails for a lower layer that uses an upper one and for
a flow that uses another.

**Where tests live.** Each module tests itself: a test that needs only one module and the modules below it is in that
module's `src/test` (`modules/smith/src/test`, `modules/flow-doctor/src/test`, ...), so a change in one flow module
recompiles and re-runs that flow's tests and the app's, and every other module's test task is up to date or from the
build cache. `modules/app/src/test` keeps the tests that need the whole application: the `@SpringBootTest` classes
(the full context, the BPMN deployed from the app's resources), the process tests that drive a flow's BPMN end to
end, `EngineModulesTest`, `ConfigConsistencyTest` and `LayerRuleTest` (they read every module), the result tests that check a
Smith's result against `smith/schemas` (packed into the app's resources as `result-schemas/`: `ResultContractTest`,
`WorkResultTest`, `FixResultTest`, `DoctorResultTest`), `RefactorPromptTest` (it reads `bots/refactor-values.md`, which only the app packs), and the tests whose constants app tests use (`PushRejectedTest`,
`PitchRulesTest`, with `ResearchResultTest`). A helper tests in several modules share is a test fixture of the lowest module it needs
(`modules/events/src/testFixtures`: `EventRecorder`; `modules/smith/src/testFixtures`: `FakeSandbox`), used as
`testImplementation(testFixtures(project(...)))`, never copied. The root build (`engine/build.gradle.kts`) gives every module the same test libraries and test JVM settings;
every test runs from `engine/`.

| Module | Holds | Depends on |
|---|---|---|
| `engine-common` | `BadRequest`, `errorResponse`, `refuseUnknownFields` and `loginOf` (shared by the controllers that otherwise each defined their own), `Utc`, `Since`, `Apart`, `EngineJson`, `EngineSetting`, `SecretsKey`, and `CallerIdentity` with `CallerRefusal` (is a request from a Smith sandbox; `SandboxCallers` answers it from the `SmithSandbox`), and the request attributes the bearer filter sets (`TOKEN_LOGIN_ATTRIBUTE`, `TOKEN_ID_ATTRIBUTE`, `SMITH_AT_ADDRESS_ATTRIBUTE`) and `actingLogin`, the login a request acts as: the bearer token's ("Tokens") | Spring web and JDBC |
| `engine-github` | `github/**`, the target repo registry (`TargetRepos`), the interfaces it implements (`ci.RepoCiChecks`, `ci.CiCheck`, `webhook.WebhookSecrets`), `SetupReport` and `board.targetRepo` | common |
| `engine-events` | `EngineEvent`, `EngineEvents`, `FlowEvents`, `FlowEventsPlugin`, `GET /events`, and the payload types their frames carry (`BoardIssue` and what it holds, `BoardRows`); `FlowIssues` and `IssueBoard` are `engine-api`'s, behind `FlowIssueLookup` and `BoardRows` | github |
| `engine-accounts` | `accounts/**` and `tier/**`; `GET /accounts`'s live runs come from `RunningModels` (`SmithTelemetry`) | events |
| `engine-jev` | `jev/**` and `summarize/**`; the live runs a summary reads come from `LiveRunSessions` (`SmithLiveRunSessions`) | accounts, github |
| `engine-smith` | `smith/**` (the sandboxes, the Smith delegates, `read result`), `webhook/**`, and the flow-tracking half of `repos/**` (`RunningFlows`, `RootProcessInstance`, `HandledIssues`, `DeferredIssues`, `Access`); `TargetRepoController` and `RepoSetup*` are `engine-api`'s | jev |
| `engine-incidents` | `camunda/**` (incidents, their fixes, flows and timelines) `doctor.DoctorVars`, and the intake links `IntakeRefs` and `IntakeTickets` | smith |
| `engine-hitlp` | `hitlp/**` ("The HITLP requester"): the engine as a HITLP requester (`HitlpServerSettings`, `HitlpRequester`, `HitlpFiling`, `HitlpTaskStream`, `HitlpPoller`, `HitlpRequests`), the vendored spec/schemas (`resources/hitlp/**`), and the judge's escalation, teach and SSH-verdict readers; it serves no HITLP (#2484) | smith |
| `engine-firstmate` | `firstmate/**` ("First-mate sessions"): the phone's first-mate session store and transcript, `/first-mate/sessions`, the session pod's MCP config (`FirstMateMcpConfig`), the session pods running `fm-relay` (`FirstMatePodSpec`, `KubernetesFirstMateSessionPods`) and the relay's endpoints (`FirstMatePodController`), and their freeze and resume (`FirstMateFreezer`, `FirstMateSnapshots`), and the APNs pushes (`firstmate.push`: "Push notifications") | hitlp |
| `engine-flow-intake` | `intake/**`: the intake flow, `FlowCanceller`, `TicketAmender` (`IntakeController` is `engine-api`'s) | layers 0-2 |
| `engine-flow-feature` | `feature/**` and `bug/**` | layers 0-2 |
| `engine-flow-delivery` | `chore/**`, `ci/**`, `trigger/**` and `refactor/**` (`RefactorConfigs` and its controller are `engine-plugins`', over `jobs`) | layers 0-2 |
| `engine-flow-doctor` | `doctor/**` | layers 0-2 |
| `engine-flow-friction` | `friction/**` | layers 0-2 |
| `engine-flow-proposal` | `proposal/**` and `research/**` | layers 0-2 |
| `engine-plugins` | `jobs/**` (custom jobs, plugin jars and flows, the job controller), `sdk/**` (the engine's side of the SDK), `refactor.RefactorConfigs` and its controller, and `bpmn.FlowRules` (the diagram checks, for the engine's flows and a plugin's) | every flow module, `:sdk` |
| `engine-api` | `board/**` (the Issues board, needs, pitches), `mcp/**` (the engine-served MCP servers), `firstmate/**`, `frakpanel/**`, `TargetRepoController`, `EngineAccessController` and `RepoSetup*` in `repos`, `intake.IntakeController`, `events.FlowIssues`, and the root controllers (`HealthController`, `VersionController`, `EchoController`, `ProcessTestController`) | plugins |
| `engine-app` | `EngineApplication`, `ProcessMigrator`, `UnknownEngineProperties`, the root delegates (`HelloDelegate`, `NoopDelegate`, `NotBuiltListener`), the Kotlin Flyway migrations (`db.migration`), and every resource: `src/main/resources` (`bpmn/`, `db/migration`, `frakpanel/`, `application.properties`, `META-INF`), the Modeler drafts in `engine/bpmn` (a resource directory of the module, deployed as they are), `smith/schemas` and `bots/refactor-values.md`. Its tests are the ones that need the whole application ("Where tests live", below) | api |

Every package, by the modules that hold it (a package in several modules is split by class, as the table above says):

| Package (`com.codefrak.engine.…`) | Modules |
|---|---|
| (root) | common, api, app |
| `accounts` | common (`SecretsKey`), accounts |
| `board` | github, events, smith, api |
| `bpmn` | plugins |
| `bug` | flow-feature |
| `camunda` | events, incidents |
| `chore` | smith, flow-delivery |
| `ci` | github, flow-delivery |
| `doctor` | smith, incidents, flow-doctor |
| `events` | events, api (`FlowIssues`) |
| `feature` | smith, flow-feature |
| `firstmate` | api |
| `frakpanel` | api |
| `friction` | smith, flow-friction |
| `github` | github |
| `hitlp` | hitlp |
| `intake` | events, smith, incidents, flow-intake, api (`IntakeController`) |
| `jev` | jev |
| `jobs` | smith (`JOB_OUTCOME`, `JobCreators`), plugins |
| `mcp` | api |
| `proposal` | smith, flow-proposal |
| `refactor` | flow-delivery, plugins (`RefactorConfigs`) |
| `repos` | github, smith, api |
| `research` | smith, flow-proposal |
| `sdk` | plugins |
| `smith` | accounts, smith |
| `summarize` | jev |
| `tier` | accounts |
| `trigger` | smith, flow-delivery |
| `webhook` | github, smith |
| `db.migration` (not under `com.codefrak.engine`) | app |

`SecretsKey` is in `engine-common`, not `engine-accounts`, so `TargetRepos` can encrypt tokens without
`engine-github` depending on `engine-accounts` (which depends on `engine-events`, which depends on `engine-github`).

`JevAgreement` (`GET /jev/summary`'s `judged` and `agreed`, "Jev decisions") is a bean over every
`JevAgreementRule` bean: each use case's package registers the rule that reads its outcomes (`CiTriageAgreement`,
`IncidentTriageAgreement`, `DuplicateAgreement`, `RefinePickAgreement`, `SightingAgreement`,
`FrictionGroupAgreement`, `ProposalDecisionAgreement`; `acceptance`, which no flow asks any more, is
`engine-jev`'s own), so `engine-jev` imports none of them. Its tests are in `modules/jev/src/test`; the agreements' tests are with their modules.

`engine-smith` imports no flow module's code (`EngineModulesTest`); what it needs of one is an interface the flow
module implements:

- `ResultHandler`: `read result` (`ReadResultDelegate`) reads the smith-owned modes itself (work, reproduce, verify,
  review, security, job) and hands every other mode's result, checked against its schema, to the one handler bean
  that claims it: `RefineResultHandler` (refine), `FeatureResultHandler` (design, replan, accept),
  `ProposeResultHandler` (propose), `ResearchResultHandler` (research), `DoctorResultHandler` (doctor) and
  `engine-incidents`' `IncidentResultHandler` (fix, diagnose). Two handlers claiming one mode, or a smith-owned one,
  fail the startup. `WorkResultListener` beans hear of every accepted work result (`CiTriageListener`).
- `IntakeLookup` (`IntakeLookups`): the earlier intakes a brief names (`IntakeRef`, now in `smith`) and the refiner's
  tier; `RetroNotes` (`FrictionRetroNotes`): a run's retro notes as friction notes.
- `FlowWakes` (`IssueScanWakes`): what a webhook wake starts and wakes, the issue scan and the engine-check poll.
- `PluginMetrics` (`EngineMetrics`): the plugins' series `GET /metrics` appends.

`engine-incidents` reads the intake flow through `IntakeRecords` (`IntakeRecordsStore`). The variable an intake keeps
its filed ticket in is `Vars.FILED_TICKET`.

A flow module depends on layers 0-2 (common through incidents) alone, never on another flow module or an upper layer
(`EngineModulesTest` resolves every import of each module against what the modules it may use declare). What two
flows share is a contract in `engine-smith`, in the package it had, with the implementation in its flow:

- Types, constants and pure helpers, moved as they were: `feature.FeatureMode`/`AcceptResult`/`PlanQuestion`
  (`FeatureResults`), `SplitState` and `mergedText`, `intake.RefineResult`, `proposal.ProposeResult`, `PitchRules`,
  `DecisionVerb`, `research.ResearchResult`, `intake.IntakeQuestions` and `answerHint`, the intake's variables
  (`IntakeVars`: `SUBMITTED_BY`, `AUTHORIZED_BY`, `AUTHORIZED_BY_ENGINE`, `DOCTOR_BUG`, `FRICTION_BUG`, the engine
  actors `DOCTOR_ACTOR` and `FRICTION_ACTOR`, …), `intake.DuplicateCheck`, `doctor.Signature`, `trigger.ScanAction`,
  `chore.flow`, `board.NeedKeys` (what `Needs` names a user task and an asker by) and `jobs.JOB_OUTCOME`.
- Beans whose dependencies are all below, moved whole: `intake.TicketFiler`, `AuthorizedStart`, `RefinePicker`,
  `chore.FlowStarter`/`ProcessFlowStarter`.
- Interfaces a flow (or an upper layer) implements: `doctor.DoctorBugLinks` (`DoctorBugStore`), `doctor.DoctorRunEnding`
  (`DoctorRunEnder`), `friction.FrictionBugFilings` (`FrictionBugs`) and `FrictionFixFilings` (`FrictionFixes`),
  `trigger.DeferredStarts` (`IssueScanner`), and over the upper layers `board.OpenTickets` (`IssueBoard`),
  `doctor.PluginDiagnoses` (`PluginDoctors`, so no flow module depends on the SDK) and `jobs.JobCreators`
  (`JobStore`).
