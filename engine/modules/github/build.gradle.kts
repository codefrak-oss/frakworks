// engine-github (engine/README.md, "Engine modules"): the GitHub clients and the target repo registry (TargetRepos).
plugins {
    kotlin("jvm")
    kotlin("plugin.spring")
    id("io.spring.dependency-management")
    // FakeTickets (src/testFixtures): the in-memory Tickets the flow and app tests end tickets through.
    `java-test-fixtures`
}

dependencies {
    api(project(":engine-common"))
}
