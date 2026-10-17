// engine-jev (engine/README.md, "Engine modules"): Jev calls and their summary, with the agreement each use case's
// package registers (JevAgreementRule), and the live-run summarizer.
plugins {
    kotlin("jvm")
    kotlin("plugin.spring")
    id("io.spring.dependency-management")
}

dependencies {
    api(project(":engine-accounts"))
    api(project(":engine-github"))
}
