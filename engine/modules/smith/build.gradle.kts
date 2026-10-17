// engine-smith (engine/README.md, "Engine modules"): Smith runs (`smith/**`: the sandboxes, the delegates, result
// dispatch through each flow's ResultHandler), GitHub's webhook (`webhook/**`, waking the flows through FlowWakes) and
// the flow-tracking half of `repos` (RunningFlows, RootProcessInstance, HandledIssues, DeferredIssues, Access).
plugins {
    kotlin("jvm")
    kotlin("plugin.spring")
    id("io.spring.dependency-management")
    // FakeSandbox (src/testFixtures): the in-memory SmithSandbox every sandbox-driving test uses.
    `java-test-fixtures`
}

dependencies {
    api(project(":engine-jev"))
    // The versions are the app's (engine/build.gradle.kts).
    api("org.operaton.bpm.springboot:operaton-bpm-spring-boot-starter:2.1.5")
    implementation("com.github.docker-java:docker-java-core:3.4.1")
    implementation("com.github.docker-java:docker-java-transport-zerodep:3.4.1")
    implementation("io.fabric8:kubernetes-client:7.3.1")
    implementation("com.networknt:json-schema-validator:3.0.7")
    // KubernetesSandboxTest: a mock API server for the fabric8 client.
    testImplementation("io.fabric8:kubernetes-server-mock:7.3.1")
    // TicketFilerTest: EventRecorder.
    testImplementation(testFixtures(project(":engine-events")))
    // TicketRecorder.tickets(): a FakeTickets whose ends are recorded as the fake's closes.
    testFixturesApi(testFixtures(project(":engine-github")))
}
