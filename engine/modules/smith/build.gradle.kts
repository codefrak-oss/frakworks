// engine-smith (engine/README.md, "Engine modules"): the implementations of engine-smith-api. Smith runs (`smith/**`:
// the sandboxes DockerSandbox and KubernetesSandbox, the smith-run delegates, the telemetry and the live sessions,
// the stores StoredSmithRunStats, StoredSmithSessions, StoredSmithHosting, StoredRunTokens and StoredEngineTokens,
// the engine's own controllers), and GitHub's webhook (`webhook/**`, waking the flows through FlowWakes). Only
// engine-app depends on this module (the beans reach the context by component scanning, the packages being the same),
// so a change here rebuilds and retests this module and the app, no module in between.
plugins {
    kotlin("jvm")
    kotlin("plugin.spring")
    id("io.spring.dependency-management")
}

dependencies {
    api(project(":engine-smith-api"))
    // The versions are the app's (engine/build.gradle.kts).
    implementation("com.github.docker-java:docker-java-core:3.4.1")
    implementation("com.github.docker-java:docker-java-transport-zerodep:3.4.1")
    implementation("io.fabric8:kubernetes-client:7.3.1")
    // KubernetesSandboxTest: a mock API server for the fabric8 client.
    testImplementation("io.fabric8:kubernetes-server-mock:7.3.1")
    testImplementation(testFixtures(project(":engine-events")))
    // FakeSandbox and TicketRecorder.
    testImplementation(testFixtures(project(":engine-smith-api")))
}
