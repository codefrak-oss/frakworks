// engine-flow-delivery (engine/README.md, "Engine modules"): `chore/**`, `ci/**`, `trigger/**` and `refactor/**`: the chore flow and the delegates it shares with bug, feature and proposal, the engine check, the scan, and the refactoring job. Depends on layers 0-2 alone and on no other
// flow module: what it shares with one is a contract in engine-smith.
plugins {
    kotlin("jvm")
    kotlin("plugin.spring")
    id("io.spring.dependency-management")
}

dependencies {
    api(project(":engine-incidents"))
    // HitlpFiling: a hitlp change's merge acceptance is filed as a HITLP human.approve (#2467).
    implementation(project(":engine-hitlp"))
    // testFiler and TicketRecorder (engine-smith's fixtures): the post-deployment check's bug filing test.
    testImplementation(testFixtures(project(":engine-smith")))
    // FakeTickets: the in-memory Tickets the delegates comment on and list (#100039).
    testImplementation(testFixtures(project(":engine-github-api")))
}
