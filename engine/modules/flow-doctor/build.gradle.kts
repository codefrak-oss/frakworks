// engine-flow-doctor (engine/README.md, "Engine modules"): `doctor/**`, the doctor (bpmn/doctor.bpmn) and its bugs. Depends on layers 0-2 alone and on no other
// flow module: what it shares with one is a contract in engine-smith-api.
plugins {
    kotlin("jvm")
    kotlin("plugin.spring")
    id("io.spring.dependency-management")
}

dependencies {
    api(project(":engine-incidents"))
}
