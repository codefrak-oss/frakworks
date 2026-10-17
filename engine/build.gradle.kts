// The engine's root project: no code of its own, only what every module shares (engine/README.md, "Engine
// modules"). The application is engine-app (modules/app); the plugins are declared here, applied by the modules.
plugins {
    id("org.springframework.boot") version "4.0.8" apply false
    id("io.spring.dependency-management") version "1.1.7" apply false
    kotlin("jvm") version "2.2.20" apply false
    kotlin("plugin.spring") version "2.2.20" apply false
    id("org.gradle.test-retry") version "1.6.2" apply false
}

// What every engine module (engine/modules, the app among them) shares: the toolchain, the Spring Boot BOM, the
// all-open Spring plugin and the compiler flags. Each module's own build file names its dependencies.
configure(subprojects.filter { it.name.startsWith("engine-") }) {
    apply(plugin = "org.jetbrains.kotlin.jvm")
    apply(plugin = "org.jetbrains.kotlin.plugin.spring")
    apply(plugin = "io.spring.dependency-management")
    apply(plugin = "org.gradle.test-retry")
    group = "com.codefrak"
    version = "0.0.1"
    repositories {
        mavenCentral()
    }
    extensions.configure<JavaPluginExtension> {
        toolchain {
            languageVersion = JavaLanguageVersion.of(21)
        }
    }
    extensions.configure<io.spring.gradle.dependencymanagement.dsl.DependencyManagementExtension> {
        imports {
            mavenBom(org.springframework.boot.gradle.plugin.SpringBootPlugin.BOM_COORDINATES)
        }
    }
    extensions.configure<org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension> {
        compilerOptions {
            freeCompilerArgs.addAll("-Xjsr305=strict")
        }
    }
    // Every module tests itself (engine/README.md, "Where tests live"): the same test libraries and the same test
    // JVM settings everywhere, so a module's tests run, and are cached, with that module.
    dependencies {
        "testImplementation"("org.springframework.boot:spring-boot-starter-test")
        "testImplementation"("org.jetbrains.kotlin:kotlin-test-junit5")
        "testRuntimeOnly"("org.junit.platform:junit-platform-launcher")
    }
    tasks.withType<Test> {
        // The engine directory, for every module: ConfigConsistencyTest, EngineModulesTest and RunSmithPromptTest
        // read engine/modules, engine/smith and the repo root from it.
        workingDir = rootProject.projectDir
        // Contexts share one in-memory H2 database; a live JobExecutor in any cached context
        // races the tests that drive timer and async jobs by hand (#161). Tests run jobs themselves.
        systemProperty("operaton.bpm.job-execution.enabled", "false")
        // What every test context shares, here rather than on each test class: Spring caches one
        // context per distinct set of test properties, so the same flags spelled on each class (in
        // different orders, some missing) built 34 contexts where 18 do. System properties are not
        // part of that cache key. A class's @TestPropertySource sets only what it changes
        // (`engine.smith.sandbox=fake` stays on the class: it drops the Docker sandbox, and those
        // classes bring their own). The key is a test key, 32 zero bytes; never a real one.
        systemProperty("engine.scan.enabled", "false")
        systemProperty("engine.smith.exit-scan.enabled", "false")
        systemProperty("engine.secrets.key", "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=")
        useJUnitPlatform()
        // ContextGroupedClassOrderer (junit-platform.properties) runs the classes of one context back
        // to back, so each context is built once and the cache only needs the one in use. Two cached
        // contexts bound the heap: 768m fits next to the Gradle daemon inside a Smith's 3 GiB sandbox
        // (the default heap ran out at the eleventh context, 2026-09-24; 1536m at about the
        // twentieth). One fork in a Smith sandbox and on a laptop: a second test JVM gained ~10% on 4
        // cores and doubles the memory. The engine check (the only build that sets
        // GRADLE_REMOTE_CACHE_URL) runs on 8 vCPU buildkitd nodes, where :engine-app:test alone took
        // ~2m45s of a ~6 min check with one fork (2026-10-12): there it runs 2 forks. 3 forks were
        // faster, but one build then peaked near buildkitd's 10Gi limit (the Gradle JVM's -Xmx4g plus
        // ~3.3 GiB of test JVMs), and two builds on one buildkitd were OOM-killed (2026-10-12, every
        // buildkitd's memory.peak at the limit). Gradle hands classes to forks in turn, and the orderer
        // still groups each fork's classes by context. -Pengine.test.forks=<n> overrides either.
        maxHeapSize = "768m"
        val ciBuild = !System.getenv("GRADLE_REMOTE_CACHE_URL").isNullOrBlank()
        maxParallelForks = (findProperty("engine.test.forks") as String?)?.toInt() ?: if (ciBuild) 2 else 1
        systemProperty("spring.test.context.cache.maxSize", "2")
        // The engine refuses an engine.* property it does not declare (UnknownEngineProperties), and the
        // test JVM's contexts would inherit the build's environment: a Smith sandbox's ENGINE_URL (local
        // build) is the sandbox's, not the engine's, so no ENGINE_* variable reaches the tests.
        setEnvironment(environment.filterKeys { !it.startsWith("ENGINE_") })
        // A failed test runs again, up to twice (engine/README.md, "Flaky tests"): one that then passes no longer fails
        // the build, and is printed as `FLAKY-TEST: <Class> > <method>() passed on retry` on a line of its own, which
        // the engine reads from a passing check's log (CiLog.flaky) and files as a flaky-test ticket. More than 10
        // failures in a task is a broken build, not flakes: those are not retried. The line never ends in FAILED.
        extensions.configure<org.gradle.testretry.TestRetryTaskExtension> {
            maxRetries.set(2)
            maxFailures.set(10)
            failOnPassedAfterRetry.set(false)
        }
        val failedOnce = java.util.Collections.synchronizedSet(mutableSetOf<String>())
        val flaky = java.util.Collections.synchronizedSet(linkedSetOf<String>())
        addTestListener(object : TestListener {
            override fun beforeSuite(suite: TestDescriptor) {}
            override fun afterSuite(suite: TestDescriptor, result: TestResult) {
                // The root suite ends once per retry round: each test is printed once.
                if (suite.parent == null) synchronized(flaky) { flaky.forEach { println("FLAKY-TEST: $it passed on retry") }; flaky.clear() }
            }
            override fun beforeTest(test: TestDescriptor) {}
            override fun afterTest(test: TestDescriptor, result: TestResult) {
                val cls = test.className ?: return
                val key = "$cls > ${test.name.removeSuffix("()")}()"
                when (result.resultType) {
                    TestResult.ResultType.FAILURE -> failedOnce += key
                    TestResult.ResultType.SUCCESS -> if (key in failedOnce) flaky += key
                    else -> {}
                }
            }
        })
        // The build runs inside docker build, where the HTML report is out of reach.
        testLogging {
            events("failed")
            exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
        }
    }

    // Downloads every dependency jar without compiling; the Dockerfile runs it (`gradle resolveDependencies` at the
    // root) in a layer of its own so code-only changes rebuild without network. In every module: each has test
    // dependencies of its own (a mock server, test fixtures) that the app's classpaths do not hold.
    tasks.register("resolveDependencies") {
        doLast {
            configurations.filter { it.isCanBeResolved }.forEach { it.resolve() }
        }
    }
}
