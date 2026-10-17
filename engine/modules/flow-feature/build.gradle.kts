// engine-flow-feature (engine/README.md, "Engine modules"): `feature/**` and `bug/**`, the feature and bug flows. Depends on layers 0-2 alone and on no other
// flow module: what it shares with one is a contract in engine-smith.
plugins {
    kotlin("jvm")
    kotlin("plugin.spring")
    id("io.spring.dependency-management")
}

dependencies {
    api(project(":engine-incidents"))
    // HitlpFiling and HitlpAnswerer: every ask wait is filed as a HITLP task and continues from its decision record (#2462).
    implementation(project(":engine-hitlp"))
}
