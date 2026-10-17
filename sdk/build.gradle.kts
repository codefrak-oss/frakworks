// The plugin API (engine/README.md, "Custom jobs and the SDK"): the interfaces a plugin jar programs
// against and the engine implements. Kotlin only, no Spring or Operaton, so a plugin build depends on
// nothing of the engine's. Built as `:sdk` of the engine build (engine/settings.gradle.kts).
plugins {
    // The version is the engine's (engine/build.gradle.kts): the root build puts the plugin on the classpath.
    kotlin("jvm")
}

group = "com.codefrak"
version = "0.0.1"

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

repositories {
    mavenCentral()
}

kotlin {
    compilerOptions {
        freeCompilerArgs.addAll("-Xjsr305=strict")
    }
}
