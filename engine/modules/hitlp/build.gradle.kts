// engine-hitlp (engine/README.md, "Engine modules"; "The HITLP requester"): the engine as a HITLP requester (HitlpRequester,
// HitlpFiling, HitlpPoller, HitlpTaskStream), its vendored spec/schemas (`resources/hitlp/**`), the judge's escalations, teach and SSH
// verdicts. Since #2484 it serves no HITLP: the server, its task store, rules, timeouts and settings were removed. Depends on engine-smith (Access, SmithSandbox) and engine-github (RepoContents, for the judge teach commit);
// never on engine-api or the app.
plugins {
    kotlin("jvm")
    kotlin("plugin.spring")
    id("io.spring.dependency-management")
}

dependencies {
    api(project(":engine-smith"))
    // RepoContents for POST /judge/teach (JudgeTeachController): the rubric line is committed with the repo's own token.
    implementation(project(":engine-github"))
    // HITLP tool arguments and decision records against the vendored spec/schemas (HitlpSchemas); the 3.x line is on
    // Jackson 3, like the engine (the smith module declares the same for ResultSchemas). `api`, not `implementation`:
    // HitlpSchemas.of answers a Schema, so the app module's tests see the type through :engine-api.
    api("com.networknt:json-schema-validator:3.0.7")
    // FakeSandbox, for JudgeTeachControllerTest.
    testImplementation(testFixtures(project(":engine-smith")))
}
