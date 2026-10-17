package com.codefrak.engine.firstmate

import com.codefrak.engine.smith.Lineage
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.OffsetDateTime
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** A phone session's lineage (engine/README.md, "Lineage"): rooted at the chat's ask, in the Secret, mounted read-only, named by `FM_LINEAGE_FILE` and by its literal path in the appended prompt for the Read tool (#2496). */
class FirstMateLineageTest {
    private val built: Lineage = Lineage.session("lundmatt1", "Restart the office DNS. Then check it.", "4F2a-9c", Instant.parse("2026-10-11T10:00:00Z"))
    private val session = FirstMateSession("4F2a-9c", "lundmatt1", "tok-1", "acme/tickets", "open", OffsetDateTime.now(), null,
        lineage = String(built.toBytes()))
    private val name = firstMatePodName(session)

    @Test
    fun `the session's lineage is one session link rooted at the login and the chat's ask`() {
        val read = Lineage.of(session.lineage!!.toByteArray())
        assertEquals("2026-10-11T10:00:00Z", read.asOf)
        assertFalse(read.truncated)
        val link = read.links.single()
        assertEquals("session", link.kind)
        assertEquals("Restart the office DNS.", link.claim)
        assertEquals(mapOf("login" to "lundmatt1", "session" to "4F2a-9c"), link.source)
        assertEquals("lundmatt1", link.by)
    }

    @Test
    fun `the Secret carries it byte for byte and the pod mounts it read-only with FM_LINEAGE_FILE`() {
        val secret = firstMateSecret(name, session, FirstMateMcp(emptyMap(), emptyList()), null, null, "fw_pod")
        assertEquals(session.lineage, secret.stringData[FIRST_MATE_LINEAGE_KEY])
        val pod = firstMatePod(name, session, "img", listOf("fm-relay"), name, null, 1, 1.0)
        val c = pod.spec.containers.single()
        assertEquals("$FIRST_MATE_MCP_DIR/$FIRST_MATE_LINEAGE_KEY", c.env.single { it.name == "FM_LINEAGE_FILE" }.value)
        assertEquals(true, c.volumeMounts.single { it.name == "mcp" }.readOnly)
        val items = pod.spec.volumes.single { it.name == "mcp" }.secret.items.map { it.key }
        assertEquals(listOf("mcp.json", FIRST_MATE_LINEAGE_KEY), items)
    }

    @Test
    fun `the appended prompt gives the mounted file's literal path for the Read tool, and none without a lineage`() {
        val prompt = FirstMatePrompt("You are the first mate.", listOf("Read"), emptyList())
        fun appended(argv: List<String>) = argv[argv.indexOf("--append-system-prompt") + 1]
        val with = appended(firstMateArgv("m", "low", prompt, lineage = true))
        assertTrue(with.endsWith("\n\nLineage file: $FIRST_MATE_MCP_DIR/$FIRST_MATE_LINEAGE_KEY (open it with the Read tool, never Bash)."))
        val pod = firstMatePod(name, session, "img", listOf("fm-relay"), name, null, 1, 1.0)
        assertTrue(with.contains(pod.spec.containers.single().env.single { it.name == "FM_LINEAGE_FILE" }.value), "the path the pod mounts")
        assertEquals("You are the first mate.", appended(firstMateArgv("m", "low", prompt)))
        assertFalse(appended(firstMateArgv("m", "low", prompt)).contains("Lineage file:"))
    }

    @Test
    fun `a session from before lineages gets no file and no variable`() {
        val old = session.copy(lineage = null)
        val pod = firstMatePod(name, old, "img", listOf("fm-relay"), name, null, 1, 1.0)
        assertNull(pod.spec.containers.single().env.firstOrNull { it.name == "FM_LINEAGE_FILE" })
        assertNull(firstMateSecret(name, old, FirstMateMcp(emptyMap(), emptyList()), null, null, "fw_pod").stringData[FIRST_MATE_LINEAGE_KEY])
    }
}
