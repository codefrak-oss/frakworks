package com.codefrak.engine.accounts

import com.codefrak.engine.engineJson
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Primary
import org.springframework.http.HttpStatus
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.web.server.ResponseStatusException
import java.math.BigDecimal
import java.time.LocalDate
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** An account's API key, admin key, billing cycle and credit (engine/docs/ai-accounts.md, "API keys and credit"), on H2. */
@SpringBootTest
class AiAccountControllerKeysTest(@Autowired val accounts: AiAccounts, @Autowired val api: AiAccountController,
                                  @Autowired val verifier: FakeVerifier, @Autowired val report: FakeCostReport,
                                  @Autowired val refresh: ApiCreditRefresh, @Autowired val jdbc: JdbcClient) {
    @TestConfiguration
    class Fakes {
        @Bean @Primary fun verifier() = FakeVerifier()
        @Bean @Primary fun costReport() = FakeCostReport()
    }

    @AfterEach
    fun clean() { accounts.list().forEach { accounts.remove(it.name) } }

    private fun refused(status: HttpStatus, block: () -> Unit) =
        assertEquals(status.value(), assertFailsWith<ResponseStatusException> { block() }.statusCode.value())

    @Test
    fun `keys are stored sealed, shown only as set, and never serialized`() {
        accounts.add("max-a", "anthropic")
        api.setBilling("max-a", SetBilling(LocalDate.parse("2026-01-14"), 10_000))
        api.setApiKey("max-a", SetKey("sk-ant-usr-apisecret"))
        api.setAdminKey("max-a", SetKey("sk-ant-usr-adminsecret"))
        assertEquals(listOf(ApiKeyKind.API to "sk-ant-usr-apisecret", ApiKeyKind.ADMIN to "sk-ant-usr-adminsecret"), verifier.keys)

        val json = engineJson.writeValueAsString(api.list())
        assertFalse(json.contains("apisecret") || json.contains("adminsecret"), json)
        val view = api.list().single()
        assertTrue(view.apiKeySet && view.adminKeySet)
        assertEquals(10_000, view.grantCents)

        val stored = jdbc.sql("SELECT api_key FROM engine.ai_account WHERE name = 'max-a'").query { rs, _ -> rs.getBytes(1) }.single()
        assertFalse(String(stored, Charsets.ISO_8859_1).contains("apisecret"))
        assertEquals("sk-ant-usr-adminsecret", accounts.adminKey("max-a"))

        api.clearApiKey("max-a")
        assertFalse(api.list().single().apiKeySet)
    }

    @Test
    fun `an admin key without a billing-cycle date is refused`() {
        accounts.add("max-b", "anthropic")
        refused(HttpStatus.CONFLICT) { api.setAdminKey("max-b", SetKey("sk-ant-admin01-k")) }
        assertTrue(verifier.keys.none { it.second == "sk-ant-admin01-k" }, "not even tried")
        assertFalse(accounts.get("max-b")!!.adminKeySet)
    }

    @Test
    fun `a key anthropic refuses is not stored and the refusal carries its message`() {
        accounts.add("max-c", "anthropic")
        verifier.nextKey = Verdict(false, true, "anthropic refused the API key (401): invalid x-api-key")
        val e = assertFailsWith<ResponseStatusException> { api.setApiKey("max-c", SetKey("sk-ant-api03-bad")) }
        assertEquals(422, e.statusCode.value())
        assertEquals("anthropic refused the API key (401): invalid x-api-key", e.reason)
        assertFalse(accounts.get("max-c")!!.apiKeySet)
        refused(HttpStatus.BAD_REQUEST) { api.setApiKey("max-c", SetKey("sk-ant-admin01-wrongkind")) }
        accounts.add("oa", "openai")
        refused(HttpStatus.CONFLICT) { api.setApiKey("oa", SetKey("sk-ant-api03-k")) }
    }

    @Test
    fun `the refresh stores used and remaining for the cycle, and a failure keeps the figure and shows the error`() {
        accounts.add("max-d", "anthropic")
        api.setBilling("max-d", SetBilling(LocalDate.parse("2026-01-31"), 10_000))
        report.next = BigDecimal("3712.5")
        api.setAdminKey("max-d", SetKey("sk-ant-admin01-k"))
        var view = api.list().single()
        assertEquals(BigDecimal("3712.5"), view.usedCents?.stripTrailingZeros())
        assertEquals(BigDecimal("6287.5"), view.remainingCents?.stripTrailingZeros())
        assertNull(view.creditError)

        report.failure = "cost_report answered 500: overloaded"
        assertFalse(refresh.refresh("max-d", LocalDate.parse("2026-03-01")))
        assertEquals(LocalDate.parse("2026-02-28"), report.since.last())
        view = api.list().single()
        assertEquals(BigDecimal("3712.5"), view.usedCents?.stripTrailingZeros())
        assertEquals("cost_report answered 500: overloaded", view.creditError)
    }

    @Test
    fun `a rename reseals the keys under the new name`() {
        accounts.add("max-e", "anthropic")
        api.setApiKey("max-e", SetKey("sk-ant-api03-k"))
        api.rename("max-e", Rename("max-f"))
        assertEquals("sk-ant-api03-k", accounts.key("max-f", ApiKeyKind.API))
    }
}

/** Answers [next] cents, or throws [failure] once; records each cycle start it was asked from. */
class FakeCostReport : CostReport {
    var next: BigDecimal = BigDecimal.ZERO
    var failure: String? = null
    val since = mutableListOf<LocalDate>()
    override fun spentCents(adminKey: String, since: LocalDate): BigDecimal {
        this.since += since
        failure?.let { failure = null; throw CostReportFailed(it) }
        return next
    }
}
