// engine-app (engine/README.md, "Engine modules"): the one Spring Boot application. EngineApplication, the process
// migrator and the root delegates, the Kotlin Flyway migrations (`db.migration`) and every resource: the BPMN
// (src/main/resources/bpmn and the Modeler drafts in engine/bpmn), the SQL migrations, application.properties.
// Its tests are the ones that need the whole application (engine/README.md, "Where tests live"); LayerRuleTest checks
// the modules' layer rule.
plugins {
    id("org.springframework.boot")
    kotlin("jvm")
    kotlin("plugin.spring")
    id("io.spring.dependency-management")
}

dependencies {
    implementation(project(":engine-api"))
    implementation("org.springframework.boot:spring-boot-starter-webmvc")
    implementation("tools.jackson.module:jackson-module-kotlin")
    implementation("org.jetbrains.kotlin:kotlin-reflect")
    implementation("org.operaton.bpm.springboot:operaton-bpm-spring-boot-starter:2.1.5")
    implementation("com.github.docker-java:docker-java-core:3.4.1")
    implementation("com.github.docker-java:docker-java-transport-zerodep:3.4.1")
    // The Kubernetes hosting model (KubernetesSandbox): Jobs on the k3s cluster, beside Docker.
    implementation("io.fabric8:kubernetes-client:7.3.1")
    implementation("org.springframework.boot:spring-boot-starter-jdbc")
    implementation("org.springframework.boot:spring-boot-starter-flyway")
    runtimeOnly("org.flywaydb:flyway-database-postgresql")
    runtimeOnly("org.postgresql:postgresql")
    runtimeOnly("com.h2database:h2")
    testImplementation("org.springframework.boot:spring-boot-starter-webmvc-test")
    testImplementation(testFixtures(project(":engine-events")))
    testImplementation(testFixtures(project(":engine-smith")))
    // LayerRuleTest: the modules' layer rule over the compiled classes.
    testImplementation("com.tngtech.archunit:archunit-junit5:1.4.1")
}

// META-INF/build-info.properties, for the version `GET /version` reports; no build time, so the jar stays reproducible.
springBoot {
    buildInfo { excludes.set(setOf("time")) }
}

// The Camunda Modeler drafts in engine/bpmn deploy as they are, so an edit in the
// Modeler is the edit that runs (auto-deploy scans the whole classpath).
sourceSets {
    main {
        resources.srcDir(rootProject.file("bpmn"))
    }
}

// The Smith result schemas live next to the prompts that use them (engine/smith/schemas, also copied into the
// Smith image); the engine loads the same files from the jar (ResultSchemas).
tasks.processResources {
    from(rootProject.file("smith/schemas")) { into("result-schemas") }
    // The shipped refactoring values (engine/README.md, "Refactoring jobs"): RefactorValues reads them from the jar.
    from(rootProject.file("../bots/refactor-values.md")) { into("refactor") }
}

// The one jar the image runs (engine/Dockerfile); no plain jar beside it.
tasks.jar {
    enabled = false
}
