// engine-events (engine/README.md, "Engine modules"): the event bus, the flow events and `GET /events`, with the
// payload types its frames carry (BoardIssue and what it holds, SetupReport).
plugins {
    kotlin("jvm")
    kotlin("plugin.spring")
    id("io.spring.dependency-management")
    // EventRecorder (src/testFixtures): records the flow events a test emits, for the api and app tests.
    `java-test-fixtures`
}

dependencies {
    api(project(":engine-github-api"))
    // FlowEventsPlugin is a process engine plugin; the version is the app's (engine/build.gradle.kts).
    implementation("org.operaton.bpm.springboot:operaton-bpm-spring-boot-starter:2.1.5")
    // PostgresEventRelay LISTENs through the driver's PGConnection; the app ships the driver (runtimeOnly).
    compileOnly("org.postgresql:postgresql")
}
