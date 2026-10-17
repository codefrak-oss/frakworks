package com.codefrak.engine

import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.isDirectory
import kotlin.io.path.name
import kotlin.io.path.readText
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The engine modules' boundaries (engine/README.md, "Engine modules"), read from their build files and sources. */
class EngineModulesTest {
    private val modules: Path = Path.of("").toAbsolutePath().resolve("modules")

    /** The library modules in layer order: each may use the ones before it. */
    private val layers = listOf("common", "github-api", "github", "events", "accounts", "jev", "smith", "incidents", "hitlp", "firstmate")
    /** The flow modules: each may use every layer, never another flow. */
    private val flows = listOf("flow-delivery", "flow-doctor", "flow-feature", "flow-friction", "flow-intake", "flow-proposal")
    /** The upper layers in order, over every flow: each may use the flows, the layers and the ones before it, and the SDK. */
    private val upper = listOf("plugins", "api", "app")

    @Test
    fun `the modules exist and depend on no project but each other`() {
        val names = Files.list(modules).use { s -> s.filter { it.isDirectory() }.map { it.name }.sorted().toList() }
        assertEquals((layers + flows + upper).sorted(), names)
        names.forEach { name ->
            val projects = projects(name)
            val ok = if (name in upper) projects.all { it.startsWith(":engine-") || it == ":sdk" } else projects.all { it.startsWith(":engine-") }
            assertTrue(ok, "modules/$name depends on $projects: only :engine-* modules (and :sdk above the flows), never the root project")
        }
    }

    @Test
    fun `engine-hitlp is a requester only - no module serves HITLP`() {
        // #2484: the engine's HITLP server (/mcp/hitlp, its task store, rules, timeouts and settings) was removed.
        val gone = listOf("HitlpMcpServer", "HitlpTasks", "HitlpTasksApi", "HitlpService", "StandingApprovals", "HitlpTick", "HitlpSettings")
        val sources = Files.walk(modules).use { s -> s.filter { it.toString().endsWith(".kt") && "/src/main/" in it.toString() }.toList() }
        assertEquals(emptyList<String>(), sources.filter { it.name.removeSuffix(".kt") in gone }.map { modules.relativize(it).toString() })
        assertEquals(emptyList<String>(), sources.filter { "\"/mcp/hitlp\"" in it.readText() }.map { modules.relativize(it).toString() })
    }

    @Test
    fun `engine-jev imports none of the packages that register agreement rules`() {
        val forbidden = Regex("""^import com\.codefrak\.engine\.(intake|friction|doctor|ci|camunda|proposal)\.""", RegexOption.MULTILINE)
        val offenders = Files.walk(modules.resolve("jev")).use { s -> s.filter { it.toString().endsWith(".kt") }.toList() }
            .filter { forbidden.containsMatchIn(it.readText()) }
        assertEquals(emptyList<String>(), offenders.map { modules.relativize(it).toString() })
    }

    @Test
    fun `engine-smith, engine-incidents and engine-hitlp depend only on the modules below them`() {
        val allowed = mapOf(
            "smith" to setOf(":engine-jev"),
            "incidents" to setOf(":engine-smith"),
            "hitlp" to setOf(":engine-smith", ":engine-github-api"),
        )
        allowed.forEach { (name, ok) -> assertEquals(ok, projects(name), "modules/$name's project dependencies") }
    }

    @Test
    fun `the flow modules depend only on layers 0-2`() {
        val ok = layers.map { ":engine-$it" }.toSet()
        flows.forEach { name ->
            val projects = projects(name)
            assertTrue(projects.isNotEmpty() && ok.containsAll(projects), "modules/$name depends on $projects: only $ok")
        }
    }

    /**
     * Every `com.codefrak.*` import in a module resolves to a declaration in a module it may use: a layer in its
     * own or a layer before it, and for a flow module the layers and itself. So `engine-smith` imports no flow
     * module's code (a contract it holds in a flow's package, `intake.TicketFiler` say, is its own), no flow module
     * imports another's, no module below engine-plugins imports the SDK, and none imports a layer above its own.
     */
    @Test
    fun `each module imports only what the modules it may use declare`() {
        val declared = (layers + flows + upper).associateWith { declarations(it) }
        val offenders = (layers + flows + upper).flatMap { name ->
            val usable = when (name) {
                in flows -> layers + name
                in upper -> layers + flows + upper.take(upper.indexOf(name) + 1)
                else -> layers.take(layers.indexOf(name) + 1)
            }
            val known = usable.flatMap { declared.getValue(it) }.toSet()
            sources(name).flatMap { file ->
                IMPORT.findAll(file.readText()).map { it.groupValues[1] }
                    .filterNot { resolves(it, known) || (name in upper && it.startsWith("com.codefrak.sdk.")) }
                    .map { "${modules.relativize(file)}: $it" }.toList()
            }
        }
        assertEquals(emptyList<String>(), offenders)
    }

    /** The project dependencies a module's main code carries: not a testImplementation or testFixtures one. */
    private fun projects(name: String): Set<String> {
        val build = modules.resolve(name).resolve("build.gradle.kts").readText()
        return Regex("""(?m)^\s*(\w+)\(.*project\("([^"]*)"\)""").findAll(build)
            .filterNot { it.groupValues[1].startsWith("test") }
            .map { it.groupValues[2] }.toSet()
    }

    private fun sources(name: String): List<Path> {
        val root = modules.resolve("$name/src/main/kotlin")
        if (!root.isDirectory()) return emptyList()
        return Files.walk(root).use { s -> s.filter { it.toString().endsWith(".kt") }.toList() }
    }

    /** The module's top-level declarations as `package.Name`, and each of its packages as `package.*`. */
    private fun declarations(name: String): Set<String> = sources(name).flatMap { file ->
        val text = file.readText()
        val pkg = PACKAGE.find(text)?.groupValues?.get(1) ?: return@flatMap emptyList()
        TOP_LEVEL.findAll(text).map { "$pkg.${it.groupValues[1]}" }.toList() + "$pkg.*"
    }.toSet()

    /** [import] names a declaration in [known], or something nested in one; a star import a package in it. */
    private fun resolves(import: String, known: Set<String>): Boolean {
        if (!import.startsWith("com.codefrak.engine.")) return false
        if (import.endsWith(".*") && import in known) return true
        val parts = import.removeSuffix(".*").split('.')
        return (parts.size downTo 4).any { parts.take(it).joinToString(".") in known }
    }

    private companion object {
        val PACKAGE = Regex("""^package ([\w.]+)""", RegexOption.MULTILINE)
        val IMPORT = Regex("""^import (com\.codefrak(?:\.\w+)+(?:\.\*)?)""", RegexOption.MULTILINE)
        val TOP_LEVEL = Regex(
            """^(?:(?:public|internal|private|data|sealed|enum|abstract|open|const|inline|value|annotation|fun)\s+)*""" +
                """(?:class|object|interface|fun|val|var|typealias)\s+(?:<[^>]*>\s*)?(?:[\w.<>, ?*]+\.)?(\w+)""",
            RegexOption.MULTILINE,
        )
    }
}
