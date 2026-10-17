// engine-smith-api (engine/README.md, "Engine modules"): the Smith contract every module above programs against, with
// no sandbox, delegate or store behind it: the interfaces (SmithSandbox, ResultHandler, SmithRunStats, SmithSessions,
// SmithHosting, SmithExitNotifier, RunTokens, EngineTokens, IntakeLookup, FlowWakes, PluginMetrics), the run's value
// types (SmithSpec, Brief, Ticket, Finding, Vars, the result types, SmithRunStat, RunLabels, Lineage, IntakeRef), the
// pure helpers (DelegateVariables, AllowedScope, ResultSchemas, SmithRefusal, RunSandboxStopper) and the flow-shared
// layer the flows build on in their own packages (intake, feature, proposal, research, doctor, friction, chore, trigger,
// board, jobs, and the flow-tracking half of repos). The sandboxes, the delegates, the telemetry and the stores that
// implement the contract are engine-smith's, which only engine-app depends on: a change there rebuilds and retests
// the smith module and the app, not every module in between.
plugins {
    kotlin("jvm")
    kotlin("plugin.spring")
    id("io.spring.dependency-management")
    // FakeSandbox, TicketRecorder and eventually (src/testFixtures): what every sandbox-driving and ticket-filing test uses.
    `java-test-fixtures`
}

dependencies {
    api(project(":engine-jev"))
    // The versions are the app's (engine/build.gradle.kts). The process engine's types are in the contract
    // (DelegateExecution in ResultHandler, RuntimeService in RunSandboxStopper and the flow-tracking helpers).
    api("org.operaton.bpm.springboot:operaton-bpm-spring-boot-starter:2.1.5")
    // ResultSchemas: the result contract's JSON Schemas; `api`, since ResultSchemas.of answers a Schema (hitlp declares the same).
    api("com.networknt:json-schema-validator:3.0.7")
    // kubernetesConfig: the one client config the Smith sandbox and the first-mate session pods share.
    implementation("io.fabric8:kubernetes-client:7.3.1")
    // TicketFilerTest: EventRecorder.
    testImplementation(testFixtures(project(":engine-events")))
    // TicketRecorder.tickets(): a FakeTickets whose ends are recorded as the fake's closes.
    testFixturesApi(testFixtures(project(":engine-github-api")))
}
