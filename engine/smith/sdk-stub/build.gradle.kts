// The stand-in for ../sdk in the Smith image's dependency bake (smith/Dockerfile): sdk/build.gradle.kts's
// plugin, repository and toolchain, so the bake resolves what the real SDK's compile needs. Keep in step with it.
plugins {
    kotlin("jvm")
}

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

repositories {
    mavenCentral()
}
