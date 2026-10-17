// engine-accounts (engine/README.md, "Engine modules"): the AI accounts and the tier map.
plugins {
    kotlin("jvm")
    kotlin("plugin.spring")
    id("io.spring.dependency-management")
}

dependencies {
    api(project(":engine-events"))
}
