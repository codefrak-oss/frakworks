package com.codefrak.engine

import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import java.util.Properties
import kotlin.io.path.exists
import kotlin.io.path.isDirectory
import kotlin.io.path.readText
import kotlin.test.fail

/**
 * Dead and duplicated config fails the build (engine/README.md, "Configuration"; CLAUDE.md, "A setting that
 * moves leaves nothing behind"). Three cross-checks between the compose files, scripts/local-up.sh,
 * .env.example, application.properties and the main code's reads. Plain JUnit, no Spring context.
 *
 * The repo root is `..` from the Gradle project directory when that holds .env.example (a checkout); else
 * FRAKWORKS_REPO_ROOT, which engine/Dockerfile sets after copying the root files in from its `root` build
 * context. A missing file fails, naming its path: the test never skips.
 */
class ConfigConsistencyTest {
    /** The engine directory (engine/, the root Gradle project): the working directory of the test JVM (modules/app/build.gradle.kts). */
    private val engine: Path = Path.of("").toAbsolutePath()

    private val root: Path by lazy {
        engine.parent?.takeIf { it.resolve(".env.example").exists() }
            ?: System.getenv("FRAKWORKS_REPO_ROOT")?.let { Path.of(it) }
            ?: fail("no repo root: ${engine.parent?.resolve(".env.example")} does not exist and FRAKWORKS_REPO_ROOT is unset")
    }

    private fun read(path: Path): String = if (Files.isRegularFile(path)) path.readText() else fail("missing $path")

    private fun report(rule: String, problems: List<String>) {
        if (problems.isNotEmpty()) fail("$rule:\n  " + problems.joinToString("\n  "))
    }

    /** A file's lines with every whole-line comment dropped. */
    private fun code(text: String) = text.lines().filterNot { it.trimStart().startsWith("#") }

    private val composeFiles = listOf("compose.yaml", "engine/compose.local.yaml", "observability/compose.local.yaml")

    /** Variables Docker Compose itself provides; nothing declares them in .env.example. Each with its reason. */
    private val composeOwnVariables = mapOf(
        "COMPOSE_PROJECT_NAME" to "Compose sets it: the project name, the repo directory's unless overridden",
    )

    private fun envExampleKeys(): Set<String> =
        read(root.resolve(".env.example")).lines().mapNotNull { Regex("""^([A-Za-z_][A-Za-z0-9_]*)=""").find(it)?.groupValues?.get(1) }.toSet()

    private fun properties(): Properties =
        Properties().apply { read(engine.resolve("modules/app/src/main/resources/application.properties")).reader().use { load(it) } }

    @Test
    fun `every variable compose reads or local-up writes is declared in env example, and nothing else is`() {
        val read = composeFiles.flatMap { file ->
            code(read(root.resolve(file))).flatMap { line -> Regex("""[$][{]([A-Za-z_][A-Za-z0-9_]*)""").findAll(line).map { it.groupValues[1] to file } }
        }
        // What scripts/local-up.sh writes into .env: a KEY= it writes (the minted ENGINE_SECRETS_KEY), and the
        // keys of its `minted` table ("GRAFANA_ADMIN_PASSWORD": ...), whose empty .env.example lines it fills.
        val written = code(read(root.resolve("scripts/local-up.sh"))).flatMap { line ->
            Regex("""(?<![A-Z0-9_])([A-Z][A-Z0-9]*(?:_[A-Z0-9]+)+)(?:=|":)""").findAll(line).map { it.groupValues[1] to "scripts/local-up.sh" }
        }
        val used = (read + written).groupBy({ it.first }, { it.second }).filterKeys { it !in composeOwnVariables }
        val declared = envExampleKeys()
        report(
            ".env.example must declare exactly the variables compose reads and local-up.sh writes",
            (used.keys - declared).sorted().map { "$it: read by ${used.getValue(it).distinct().joinToString()}, not declared in .env.example" } +
                (declared - used.keys).sorted().map { "$it: declared in .env.example, read by none of ${composeFiles + "scripts/local-up.sh"}" } +
                (composeOwnVariables.keys intersect declared).map { "$it: Compose's own, not .env.example's" },
        )
    }

    /** The `environment:` keys of the `engine` service in a compose file, by indentation (no YAML parser on the classpath). */
    private fun engineServiceEnvironment(text: String): List<String> {
        val lines = text.lines().filter { it.isNotBlank() && !it.trimStart().startsWith("#") }
        fun indent(line: String) = line.length - line.trimStart().length
        val service = lines.indexOfFirst { it == "  engine:" }.takeIf { it >= 0 } ?: fail("no engine service in engine/compose.local.yaml")
        val body = lines.drop(service + 1).takeWhile { indent(it) > 2 }
        val env = body.indexOfFirst { it == "    environment:" }.takeIf { it >= 0 } ?: fail("the engine service in engine/compose.local.yaml has no environment")
        return body.drop(env + 1).takeWhile { indent(it) > 4 }.filter { indent(it) == 6 }
            .mapNotNull { Regex("""^\s+([A-Za-z_][A-Za-z0-9_]*):""").find(it)?.groupValues?.get(1) }
    }

    @Test
    fun `the engine service mounts the engine-jobs volume at the plugin jar directory, declared with engine-pgdata`() {
        val lines = read(root.resolve("engine/compose.local.yaml")).lines().filter { it.isNotBlank() && !it.trimStart().startsWith("#") }
        fun indent(line: String) = line.length - line.trimStart().length
        val service = lines.indexOfFirst { it == "  engine:" }.takeIf { it >= 0 } ?: fail("no engine service in engine/compose.local.yaml")
        val body = lines.drop(service + 1).takeWhile { indent(it) > 2 }
        val volumes = body.indexOfFirst { it == "    volumes:" }.takeIf { it >= 0 } ?: fail("the engine service in engine/compose.local.yaml has no volumes")
        val mounts = body.drop(volumes + 1).takeWhile { indent(it) > 4 }.map { it.trim().removePrefix("- ").trim() }
        val problems = mutableListOf<String>()
        if ("engine-jobs:/var/lib/frakworks/jobs" !in mounts) problems += "the engine service mounts none of $mounts at /var/lib/frakworks/jobs as engine-jobs (PluginJars.DIRECTORY)"
        val top = lines.indexOfFirst { it == "volumes:" }.takeIf { it >= 0 } ?: fail("no top-level volumes in engine/compose.local.yaml")
        val declared = lines.drop(top + 1).takeWhile { indent(it) > 0 }.filter { indent(it) == 2 }.map { it.trim().removeSuffix(":") }
        for (name in listOf("engine-pgdata", "engine-jobs")) if (name !in declared) problems += "$name: not declared under the top-level volumes ($declared)"
        report("engine/compose.local.yaml must hold the plugin jar volume", problems)
    }

    /** Spring's own prefixes the engine container may set as SPRING_* variables. Each with its reason. */
    private val springEnvironmentPrefixes = mapOf(
        "spring.datasource." to "the local stack's Postgres (engine-db) in place of application.properties' in-memory H2",
    )

    @Test
    fun `every ENGINE_ and SPRING_ variable the engine container gets binds to a property`() {
        val keys = properties().stringPropertyNames().map { UnknownEngineProperties.relaxed(it) }.toSet()
        val problems = engineServiceEnvironment(read(root.resolve("engine/compose.local.yaml"))).mapNotNull { name ->
            when {
                name.startsWith("ENGINE_") ->
                    if (UnknownEngineProperties.relaxed(name) in keys) null
                    else "$name: binds to no key of application.properties"
                name.startsWith("SPRING_") -> {
                    val property = name.lowercase().replace('_', '.')
                    if (springEnvironmentPrefixes.keys.any { property.startsWith(it) }) null
                    else "$name: under none of the allowed Spring prefixes ${springEnvironmentPrefixes.keys}"
                }
                else -> null
            }
        }
        report("the engine service's environment in engine/compose.local.yaml must bind to settings", problems)
    }

    /** Prefixes of application.properties keys that are the frameworks', not the engine's. Each with its reason. */
    private val frameworkPrefixes = mapOf(
        "spring." to "Spring Boot: the application name, error bodies, datasource, Flyway, MVC",
        "server." to "Spring Boot's embedded Tomcat: the port",
        "management." to "Spring Boot Actuator's settings",
        "logging." to "Spring Boot's log levels and output",
        "operaton." to "the Operaton engine's Spring Boot starter: schema update, history level",
    )

    /**
     * The engine.* keys a Kotlin source reads through Spring placeholders: `${engine.x}` or `${engine.x:default}` in
     * @Value and the @Scheduled strings that resolve the same way. In Kotlin source a placeholder is always escaped,
     * `\${engine.x}` in a regular string or `${'$'}{engine.x}` in a raw one; a bare `${engine.name}` is a string
     * template on a local named `engine`, not a property read.
     */
    internal fun placeholderKeys(source: String): List<String> =
        Regex("""(?:\\[$]|[$][{]'[$]'[}])[{](engine\.[A-Za-z0-9.\-]+)""").findAll(source).map { it.groupValues[1] }.toList()

    /** Every engine.* key the main code reads, with the files that read it. */
    private fun keysReadByCode(): Map<String, List<String>> {
        // The engine modules' sources (engine/modules/*/src/main/kotlin, engine/README.md "Engine modules"); the root holds none.
        val modules = engine.resolve("modules")
        if (!modules.isDirectory()) fail("missing $modules")
        val sources = Files.walk(modules).use { it.toList() }
        val reads = sources.filter { it.toString().endsWith(".kt") && "/src/main/" in it.toString() }.asSequence().flatMap { file ->
            val text = file.readText()
            val placeholders = placeholderKeys(text).asSequence()
            // @ConditionalOnProperty("engine.x", ...): Spring reads the key to decide whether a bean exists.
            val conditions = Regex("""@ConditionalOnProperty\(\s*(?:name\s*=\s*|value\s*=\s*)?"(engine\.[A-Za-z0-9.\-]+)"""").findAll(text).map { it.groupValues[1] }
            // @ConfigurationProperties("engine.x"): a prefix; every key under it is read.
            val prefixes = Regex("""@ConfigurationProperties\(\s*(?:prefix\s*=\s*|value\s*=\s*)?"(engine\.[A-Za-z0-9.\-]+)"""").findAll(text).map { it.groupValues[1] + ".*" }
            (placeholders + conditions + prefixes).map { it to engine.relativize(file).toString() }
        }
        return reads.toList().groupBy({ it.first }, { it.second })
    }

    @Test
    fun `every engine key in application properties is read by the code, and every one the code reads is declared`() {
        val declared = properties().stringPropertyNames()
        val read = keysReadByCode()
        fun isRead(key: String) = key in read || read.keys.any { it.endsWith(".*") && key.startsWith(it.dropLast(1)) }
        val engineKeys = declared.filter { it.startsWith("engine.") }
        report(
            "application.properties and the main code's reads must match",
            engineKeys.filterNot(::isRead).sorted().map { "$it: in application.properties, read by no main code" } +
                declared.filter { !it.startsWith("engine.") && frameworkPrefixes.keys.none { p -> it.startsWith(p) } }.sorted()
                    .map { "$it: neither engine.* nor under an allowed framework prefix ${frameworkPrefixes.keys}" } +
                read.keys.filter { !it.endsWith(".*") && it !in declared }.sorted()
                    .map { "$it: read by ${read.getValue(it).distinct().joinToString()}, not declared in application.properties" },
        )
    }

    @Test
    fun `a bare Kotlin template on a local named engine is no property read`() {
        val source = "val engine = Engine()\nval label = \"${'$'}{engine.name}\"\n"
        kotlin.test.assertEquals(emptyList(), placeholderKeys(source))
    }

    @Test
    fun `an escaped placeholder is a property read, in a regular or a raw string`() {
        kotlin.test.assertEquals(listOf("engine.not.declared"), placeholderKeys("@Value(\"\\${'$'}{engine.not.declared}\")"))
        kotlin.test.assertEquals(listOf("engine.x"), placeholderKeys("@Value(\"\\${'$'}{engine.x:default}\")"))
        kotlin.test.assertEquals(listOf("engine.x"), placeholderKeys("@Value(\"\"\"${'$'}{'${'$'}'}{engine.x}\"\"\")"))
    }
}
