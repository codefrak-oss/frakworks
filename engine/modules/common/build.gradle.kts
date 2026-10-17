// engine-common (engine/README.md, "Engine modules"): what every layer uses, the request and setting helpers
// (BadRequest, Utc, Since, EngineSetting), the caller check (CallerIdentity, SmithRefusal) and the secrets key.
// The root build (engine/build.gradle.kts) applies the toolchain, the Spring Boot BOM and the compiler flags.
plugins {
    kotlin("jvm")
    kotlin("plugin.spring")
    id("io.spring.dependency-management")
}

dependencies {
    api("org.springframework.boot:spring-boot-starter-webmvc")
    api("org.springframework.boot:spring-boot-starter-jdbc")
    api("tools.jackson.module:jackson-module-kotlin")
    api("org.jetbrains.kotlin:kotlin-reflect")
}
// test ticket 2026-10-15T12:25Z: safe to delete
