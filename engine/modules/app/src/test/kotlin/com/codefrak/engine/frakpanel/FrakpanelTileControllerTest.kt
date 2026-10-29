package com.codefrak.engine.frakpanel

import com.codefrak.engine.accounts.AccountUsages
import com.codefrak.engine.accounts.AiAccounts
import com.codefrak.engine.accounts.MeterReading
import com.codefrak.engine.github.ReadFakes
import com.codefrak.engine.repos.TargetRepos
import com.codefrak.engine.workitems.ListedTickets
import com.codefrak.engine.workitems.WorkItemStore
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.test.context.TestPropertySource
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import com.codefrak.engine.accounts.ApiKeyKind
import java.math.BigDecimal
import java.time.LocalDate
import java.time.OffsetDateTime
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The AI accounts and frakworks issues tiles over MockMvc: 404 while the setting is off, the frakpanel page and its state while on. */
@SpringBootTest
@AutoConfigureMockMvc
@Import(ReadFakes::class)
@TestPropertySource(properties = ["engine.build.commit=" + FrakpanelTileControllerTest.COMMIT])
class FrakpanelTileControllerTest {
    @Autowired lateinit var mvc: MockMvc
    @Autowired lateinit var tiles: FrakpanelTiles
    @Autowired lateinit var accounts: AiAccounts
    @Autowired lateinit var usages: AccountUsages
    @Autowired lateinit var jdbc: JdbcClient
    @Autowired lateinit var store: WorkItemStore
    @Autowired lateinit var repos: TargetRepos

    private val names = listOf("tile-on", "tile-off", "tile-blocked", "tile-fable", "tile-credit", "tile-nocredit", "tile-crediterr")

    @AfterEach
    fun reset() {
        tiles.set(false)
        names.forEach {
            accounts.remove(it)
            jdbc.sql("DELETE FROM engine.ai_account_usage WHERE account = :a").param("a", it).update()
        }
        jdbc.sql("DELETE FROM engine.work_item").update()
        listOf("engine.handled_issue", "engine.target_repo").forEach { jdbc.sql("DELETE FROM $it").update() }
    }

    @Test
    fun `off - the page and its state answer 404`() {
        tiles.set(false)
        mvc.get("/frakpanel/tiles/ai-accounts").andExpect { status { isNotFound() } }
        mvc.get("/frakpanel/tiles/ai-accounts/state").andExpect { status { isNotFound() } }
    }

    @Test
    fun `on - the page is an html tile a panel may frame, with or without the cursor flag`() {
        tiles.set(true)
        listOf("/frakpanel/tiles/ai-accounts", "/frakpanel/tiles/ai-accounts?cursor=1").forEach { url ->
            val res = mvc.get(url).andExpect {
                status { isOk() }
                content { contentTypeCompatibleWith("text/html") }
                header { doesNotExist("X-Frame-Options") }
            }.andReturn().response
            val body = res.contentAsString
            assertTrue("812px" in body && "720px" in body && "cursor: none" in body)
            assertFalse("tier" in body.lowercase())
            assertTrue(".row.disabled .fill" in body && "class=\"row ${'$'}{esc(a.state)}\"" in body)
        }
    }

    @Test
    fun `on - the state lists every account's state and windows, never a credential or email`() {
        tiles.set(true)
        val ahead = OffsetDateTime.now().plusHours(2)
        accounts.add("tile-on", "anthropic", "on@example.com"); accounts.setCredential("tile-on", "sk-ant-oat01-tile-secret")
        usages.record("tile-on", MeterReading(0.25, "allowed", ahead, 0.5, "allowed", ahead.plusDays(3), "allowed", 0.4, ahead.plusDays(2)), 200)
        accounts.add("tile-off", "anthropic"); accounts.setEnabled("tile-off", false)
        accounts.add("tile-blocked", "anthropic")
        usages.record("tile-blocked", MeterReading(1.0, "rejected", ahead, 0.9, "allowed", ahead.plusDays(3), "rejected"), 429)

        accounts.add("tile-fable", "anthropic")
        usages.record("tile-fable", MeterReading(0.1, "allowed", ahead, 0.2, "allowed", ahead.plusDays(3), "allowed"), 200)
        usages.markFableReached("tile-fable")

        val body = mvc.get("/frakpanel/tiles/ai-accounts/state").andExpect {
            status { isOk() }
            jsonPath("$.accounts[?(@.name=='tile-on')].state") { value("enabled") }
            jsonPath("$.accounts[?(@.name=='tile-on')].fiveHour.utilization") { value(0.25) }
            jsonPath("$.accounts[?(@.name=='tile-on')].sevenDay.utilization") { value(0.5) }
            jsonPath("$.accounts[?(@.name=='tile-on')].fable.utilization") { value(0.4) }
            jsonPath("$.accounts[?(@.name=='tile-on')].fable.reset") { isNotEmpty() }
            jsonPath("$.accounts[?(@.name=='tile-on')].fableLimit.reached") { value(false) }
            jsonPath("$.accounts[?(@.name=='tile-blocked')].fable.utilization") { value(null as Any?) }
            jsonPath("$.accounts[?(@.name=='tile-blocked')].fable.reset") { value(null as Any?) }
            jsonPath("$.accounts[?(@.name=='tile-fable')].fableLimit.reached") { value(true) }
            jsonPath("$.accounts[?(@.name=='tile-fable')].fableLimit.reset") { isNotEmpty() }
            jsonPath("$.accounts[?(@.name=='tile-off')].state") { value("disabled") }
            jsonPath("$.accounts[?(@.name=='tile-blocked')].state") { value("blocked") }
            jsonPath("$.accounts[?(@.name=='tile-blocked')].fiveHour.utilization") { value(1.0) }
        }.andReturn().response.contentAsString
        assertFalse("sk-ant" in body || "tile-secret" in body || "example.com" in body || "credential" in body.lowercase())
        assertFalse("tier" in body.lowercase())
    }

    @Test
    fun `on - an account with a cycle and grant carries this cycle's API credit, one without none, an error as such`() {
        tiles.set(true)
        val anchor = LocalDate.now(java.time.ZoneOffset.UTC).minusDays(3)
        accounts.add("tile-credit", "anthropic"); accounts.storeKey("tile-credit", ApiKeyKind.ADMIN, "sk-ant-admin01-tile-secret")
        accounts.setBilling("tile-credit", anchor, 10000); accounts.recordCredit("tile-credit", BigDecimal("2550"), null)
        accounts.add("tile-nocredit", "anthropic")
        accounts.add("tile-crediterr", "anthropic"); accounts.setBilling("tile-crediterr", anchor, 5000)
        accounts.recordCredit("tile-crediterr", null, "cost_report answered 401")

        val body = mvc.get("/frakpanel/tiles/ai-accounts/state").andExpect {
            status { isOk() }
            jsonPath("$.accounts[?(@.name=='tile-credit')].credit.grantCents") { value(10000) }
            jsonPath("$.accounts[?(@.name=='tile-credit')].credit.usedCents") { value(2550.0) }
            jsonPath("$.accounts[?(@.name=='tile-credit')].credit.remainingCents") { value(7450.0) }
            jsonPath("$.accounts[?(@.name=='tile-credit')].credit.cycleStart") { value(anchor.toString()) }
            jsonPath("$.accounts[?(@.name=='tile-credit')].credit.cycleEnd") { isNotEmpty() }
            jsonPath("$.accounts[?(@.name=='tile-credit')].credit.refreshedAt") { isNotEmpty() }
            jsonPath("$.accounts[?(@.name=='tile-credit')].credit.error") { value(null as Any?) }
            jsonPath("$.accounts[?(@.name=='tile-nocredit')].credit") { value(null as Any?) }
            jsonPath("$.accounts[?(@.name=='tile-crediterr')].credit.grantCents") { value(5000) }
            jsonPath("$.accounts[?(@.name=='tile-crediterr')].credit.usedCents") { value(null as Any?) }
            jsonPath("$.accounts[?(@.name=='tile-crediterr')].credit.error") { value("cost_report answered 401") }
        }.andReturn().response.contentAsString
        assertFalse("sk-ant" in body || "tile-secret" in body || "keyset" in body.lowercase() || "apikey" in body.lowercase() || "adminkey" in body.lowercase())
    }

    @Test
    fun `off - the frakworks issues page and its state answer 404`() {
        tiles.set(false)
        listOf("/frakpanel/tiles/frakworks-issues", "/frakpanel/tiles/frakworks-issues/", "/frakpanel/tiles/frakworks-issues/state").forEach {
            mvc.get(it).andExpect { status { isNotFound() } }
        }
    }

    @Test
    fun `on - the frakworks issues page is an html tile, with or without the cursor flag`() {
        tiles.set(true)
        listOf("/frakpanel/tiles/frakworks-issues", "/frakpanel/tiles/frakworks-issues/", "/frakpanel/tiles/frakworks-issues?cursor=1").forEach { url ->
            val body = mvc.get(url).andExpect {
                status { isOk() }
                content { contentTypeCompatibleWith("text/html") }
                header { string("Cache-Control", "no-store") }
                header { doesNotExist("X-Frame-Options") }
            }.andReturn().response.contentAsString
            assertTrue("812px" in body && "720px" in body && "cursor: none" in body && "get('cursor') === '1'" in body)
            assertTrue("<title>frakworks issues</title>" in body && "frakworks-issues/state" in body && "30000" in body)
        }
    }

    @Test
    fun `on - the frakworks issues state lists every target repo's open issues, never the board's author, asker or PR`() {
        tiles.set(true)
        val t0 = OffsetDateTime.parse("2026-10-01T10:00:00Z")
        repos.add("acme/widgets"); repos.add("acme/gadgets")
        val tickets = ListedTickets(store, jdbc)
        tickets.list("acme/widgets", 1, "Widget one", listOf("chore", "authorized"), t0, t0.plusMinutes(5), author = "the-author")
        tickets.list("acme/gadgets", 7, "Gadget seven", listOf("feature"), t0, t0.plusMinutes(9), author = "the-author")

        val body = mvc.get("/frakpanel/tiles/frakworks-issues/state").andExpect {
            status { isOk() }
            header { string("Cache-Control", "no-store") }
            jsonPath("$.issues.length()") { value(2) }
            jsonPath("$.at") { exists() }
            jsonPath("$.failures") { isEmpty() }
            jsonPath("$.issues[?(@.number==1)].repo") { value("acme/widgets") }
            jsonPath("$.issues[?(@.number==1)].title") { value("Widget one") }
            jsonPath("$.issues[?(@.number==1)].kind") { value("chore") }
            jsonPath("$.issues[?(@.number==1)].url") { value("https://github.com/acme/widgets/issues/1") }
            jsonPath("$.issues[?(@.number==7)].repo") { value("acme/gadgets") }
            jsonPath("$.issues[?(@.number==7)].labels[0]") { value("feature") }
            jsonPath("$.issues[?(@.number==7)].state.ended") { value(false) }
            jsonPath("$.issues[?(@.number==7)].state.incident") { value(false) }
            jsonPath("$.issues[?(@.number==7)].state.pendingQuestion") { value(false) }
        }.andReturn().response.contentAsString
        assertFalse("the-author" in body)
        listOf("author", "askedBy", "instance", "prUrl", "ticketUrl", "children", "blockedBy").forEach {
            assertFalse("\"$it\"" in body, "the tile carries no $it")
        }
    }

    @Test
    fun `on - each tile's state carries the engine's commit, the one GET version answers`() {
        tiles.set(true)
        listOf("/frakpanel/tiles/ai-accounts/state", "/frakpanel/tiles/frakworks-issues/state", "/version").forEach { url ->
            mvc.get(url).andExpect { status { isOk() }; jsonPath("$.commit") { value(COMMIT) } }
        }
    }

    @Test
    fun `on - the ai accounts tile reads a stale reset as awaiting new reading, like the desktop's STALE_RESET_MS`() {
        tiles.set(true)
        val body = mvc.get("/frakpanel/tiles/ai-accounts").andReturn().response.contentAsString
        // observability/desktop/src/renderer/accounts.jsx: STALE_RESET_MS = 5 * 60 * 1000.
        assertTrue("STALE_RESET_MS = 5 * 60 * 1000" in body)
        assertTrue("awaiting new reading" in body)
        assertTrue(Regex("function stale\\(reset\\)").containsMatchIn(body))
    }

    @Test
    fun `on - each tile page keeps its first commit and reloads when a later poll answers another`() {
        tiles.set(true)
        listOf("/frakpanel/tiles/ai-accounts", "/frakpanel/tiles/frakworks-issues").forEach { url ->
            val body = mvc.get(url).andReturn().response.contentAsString
            assertTrue("if (commit === undefined) commit = t.commit;" in body, url)
            assertTrue("else if (t.commit !== commit) { location.reload(); return; }" in body, url)
            assertTrue(Regex("setInterval\\(").findAll(body).count() == 1, url)
        }
    }

    companion object {
        const val COMMIT = "0123456789abcdef0123456789abcdef01234567"
    }
}
