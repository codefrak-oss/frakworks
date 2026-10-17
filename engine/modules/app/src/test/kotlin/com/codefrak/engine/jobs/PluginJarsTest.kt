package com.codefrak.engine.jobs

import com.codefrak.engine.ProcessFakes
import com.codefrak.engine.sdk.PluginDoctors
import com.codefrak.fixtureplugin.FixtureDoctor
import com.codefrak.fixtureplugin.RecordingJob
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.test.context.TestPropertySource
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * The plugin loader on its own (engine/README.md, "Custom jobs and the SDK"): a fixture jar in a temp directory,
 * loaded once per name and sha, shared by the jobs on it, closed by the last release, its doctor registered meanwhile;
 * and each refusal with its reason, leaving nothing loaded.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(ProcessFakes::class)
@TestPropertySource(properties = ["engine.smith.sandbox=fake"])
class PluginJarsTest {
    @Autowired lateinit var jars: PluginJars
    @Autowired lateinit var doctors: PluginDoctors

    private lateinit var dir: Path

    @BeforeEach fun setUp() { dir = PluginJarFixture.directory(); jars.closeAll(); jars.useDirectory(dir) }
    @AfterEach fun tearDown() { jars.closeAll(); PluginJarFixture.delete(dir) }

    @Test
    fun `two jobs on one jar share its context, the first release keeps it, the last closes it and drops its doctor`() {
        PluginJarFixture.write(dir)
        val first = jars.acquire("fixture.jar", "recorder", "job-1")
        val second = jars.acquire("fixture.jar", "recorder", "job-2")
        assertTrue(first is RecordingJob)
        assertSame(first, second, "one context, one bean")
        assertEquals(listOf("fixture.jar"), jars.loaded())
        assertSame(jars.entryOf("job-1"), jars.entryOf("job-2"))
        assertTrue(doctors.doctors()["fixture.jar:fixtureDoctor"] is FixtureDoctor, doctors.doctors().keys.toString())
        assertTrue(doctors.doctors().keys.first() == "fixture.jar:fixtureDoctor", "registered doctors come first: ${doctors.doctors().keys}")

        jars.release("job-1")
        assertEquals(listOf("fixture.jar"), jars.loaded(), "job-2 still holds it")
        assertNotNull(jars.entryOf("job-2")); assertNull(jars.entryOf("job-1"))
        jars.release("job-2")
        assertEquals(emptyList(), jars.loaded())
        assertNull(doctors.doctors()["fixture.jar:fixtureDoctor"], "unregistered on close")
        jars.release("job-2") // Releasing again is a no-op.
    }

    @Test
    fun `acquiring again for a job that holds the jar is a no-op, and the loader's classes come from the engine's loader first`() {
        PluginJarFixture.write(dir)
        val job = jars.acquire("fixture.jar", "recorder", "job-1")
        assertSame(job, jars.acquire("fixture.jar", "recorder", "job-1"))
        assertEquals(1, jars.entryOf("job-1")!!.jobIds.size)
        assertSame(RecordingJob::class.java, job::class.java, "the test classpath's class, resolved parent-first")
    }

    @Test
    fun `acquireContext shares the entry with a bean job on the same jar, lists its diagrams, and release closes it`() {
        PluginJarFixture.write(dir, diagrams = listOf(PluginJarFixture.FLOW, "unbound-flow"))
        jars.acquire("fixture.jar", "recorder", "bean-job")
        val entry = jars.acquireContext("fixture.jar", "flow-job")
        assertSame(jars.entryOf("bean-job"), entry)
        assertEquals(setOf("bean-job", "flow-job"), entry.jobIds)
        assertEquals(listOf("bpmn/fixture-flow.bpmn", "bpmn/unbound-flow.bpmn"), entry.diagrams().keys.toList())
        assertTrue(entry.diagrams().getValue("bpmn/fixture-flow.bpmn").decodeToString().contains("""id="fixture-flow""""))
        assertTrue(entry.context.containsLocalBean("fixtureDelegate"), "the jar's own beans")
        jars.release("bean-job")
        assertEquals(listOf("fixture.jar"), jars.loaded(), "the flow job still holds it")
        jars.release("flow-job")
        assertEquals(emptyList(), jars.loaded())
        // A jar with no diagrams has none; one that is not there is refused as a bean job's is.
        PluginJarFixture.write(dir)
        assertEquals(emptyMap(), jars.acquireContext("fixture.jar", "flow-job").diagrams())
        jars.release("flow-job")
        assertEquals("jar missing.jar is not in /var/lib/frakworks/jobs", assertThrows<PluginLoadException> { jars.acquireContext("missing.jar", "flow-job") }.message)
        assertEquals(emptyList(), jars.loaded())
    }

    @Test
    fun `a jar changed on disk is reloaded only once no job holds the old one`() {
        PluginJarFixture.write(dir)
        val oldEntry = jars.acquire("fixture.jar", "recorder", "job-1").let { jars.entryOf("job-1")!! }
        // Rewrite with a different manifest value for the same package: a new sha.
        PluginJarFixture.write(dir, pkg = "$PACKAGE ")
        jars.acquire("fixture.jar", "recorder", "job-2")
        assertSame(oldEntry, jars.entryOf("job-2"), "the loaded jar stays while job-1 holds it")
        jars.release("job-1"); jars.release("job-2")
        jars.acquire("fixture.jar", "recorder", "job-3")
        val newEntry = jars.entryOf("job-3")!!
        assertTrue(newEntry !== oldEntry && newEntry.sha256 != oldEntry.sha256, "reloaded from the new file")
    }

    @Test
    fun `each refusal names its reason and leaves nothing loaded`() {
        fun refused(jar: String, bean: String): String {
            val e = assertThrows<PluginLoadException> { jars.acquire(jar, bean, "job-x") }
            assertEquals(emptyList(), jars.loaded(), "nothing left loaded after: ${e.message}")
            assertNull(doctors.doctors()["$jar:fixtureDoctor"], "no doctor left registered")
            return e.message!!
        }
        assertEquals("jar missing.jar is not in /var/lib/frakworks/jobs", refused("missing.jar", "recorder"))
        PluginJarFixture.write(dir, "bare.jar", pkg = null)
        assertEquals("jar bare.jar has no Frak-Plugin-Package manifest attribute", refused("bare.jar", "recorder"))
        PluginJarFixture.write(dir)
        assertEquals("jar fixture.jar has no bean nope", refused("fixture.jar", "nope"))
        assertEquals("bean plain of fixture.jar is a com.codefrak.fixtureplugin.NotAJob, not a FrakJob", refused("fixture.jar", "plain"))
        PluginJarFixture.write(dir, "broken.jar", pkg = "com.codefrak.fixtureplugin.nowhere")
        assertEquals("jar broken.jar has no bean recorder", refused("broken.jar", "recorder"), "an empty package loads, but has no bean")
        Files.writeString(dir.resolve("garbage.jar"), "not a jar")
        assertContains(refused("garbage.jar", "recorder"), "garbage.jar")

        for (bad in listOf("../fixture.jar", "sub/fixture.jar", "sub\\fixture.jar", "..", " ", "")) {
            val e = assertThrows<IllegalArgumentException>(bad) { jars.acquire(bad, "recorder", "job-x") }
            assertEquals(PluginJars.NAME_REASON, e.message)
        }
        assertEquals(emptyList(), jars.loaded())
    }

    private companion object { const val PACKAGE = PluginJarFixture.PACKAGE }
}
