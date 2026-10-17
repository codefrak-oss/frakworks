// engine-firstmate (engine/README.md, "First-mate sessions"): the phone's first-mate
// sessions, their transcript, their REST API, the session pod's MCP config, and the session pods running fm-relay and its endpoints. Depends on engine-hitlp and below; never on
// a flow, engine-api or the app.
plugins {
    kotlin("jvm")
    kotlin("plugin.spring")
    id("io.spring.dependency-management")
}

dependencies {
    api(project(":engine-hitlp"))
    // TargetRepos: the repo's Smith token for a session pod's clone.
    implementation(project(":engine-github-api"))
    // The session pods (FirstMatePods): the version is engine-smith's.
    implementation("io.fabric8:kubernetes-client:7.3.1")
    testImplementation("io.fabric8:kubernetes-server-mock:7.3.1")
    testImplementation(testFixtures(project(":engine-events")))
}
