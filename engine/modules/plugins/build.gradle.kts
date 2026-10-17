// engine-plugins (engine/README.md, "Engine modules"): `jobs/**` (custom jobs, plugin jars and flows), `sdk/**`
// (the engine's side of the plugin API in ../sdk), `refactor/**` (refactoring jobs) and `bpmn/**` (FlowRules, which
// checks the engine's diagrams and a plugin's). Depends on every flow module and the SDK; never on engine-api or the app.
plugins {
    kotlin("jvm")
    kotlin("plugin.spring")
    id("io.spring.dependency-management")
}

dependencies {
    api(project(":sdk"))
    api(project(":engine-flow-intake"))
    api(project(":engine-flow-feature"))
    api(project(":engine-flow-delivery"))
    api(project(":engine-flow-doctor"))
    api(project(":engine-flow-friction"))
    api(project(":engine-flow-proposal"))
}
