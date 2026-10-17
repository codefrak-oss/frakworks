// engine-incidents (engine/README.md, "Engine modules"): `camunda/**`, incidents and their fixes, flows and their
// timelines; what it reads of the intake flow comes through IntakeRecords (`intake` in the root).
plugins {
    kotlin("jvm")
    kotlin("plugin.spring")
    id("io.spring.dependency-management")
}

dependencies {
    api(project(":engine-smith"))
}
