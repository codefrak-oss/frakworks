package com.codefrak.engine.repos

import com.codefrak.engine.github.Blocker
import com.codefrak.engine.github.IssueSnapshot
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.jdbc.support.SQLExceptionSubclassTranslator
import java.sql.SQLException
import javax.sql.DataSource
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * A claim or a deferral of a row that exists answers false without a failed statement (#2581). On PostgreSQL a failed
 * INSERT aborts the transaction it runs in, a flow job's included, even when the duplicate is caught: FileChildDelegate's
 * next engine query then failed with a persistence error that hid its own. H2 does not abort, so the test counts the
 * statements that failed rather than the transaction's state.
 */
@SpringBootTest
class DuplicateWithoutFailedStatementTest(@Autowired dataSource: DataSource) {
    private val failed = mutableListOf<String?>()
    private val jdbc = JdbcClient.create(JdbcTemplate(dataSource).apply {
        exceptionTranslator = object : SQLExceptionSubclassTranslator() {
            override fun doTranslate(task: String, sql: String?, ex: SQLException) = super.doTranslate(task, sql, ex).also { failed += sql }
        }
    })
    private val repo = "acme/duplicate-test"

    @Test
    fun `a second claim of the same approval runs no failing INSERT`() {
        val handled = HandledIssues(jdbc)
        assertTrue(handled.claim(repo, 7, 0, "feature", "started", null))
        assertFalse(handled.claim(repo, 7, 0, "feature", "started", null))
        assertEquals(emptyList(), failed)
        handled.release(repo, 7, 0)
    }

    @Test
    fun `a second deferral of the same issue replaces it with no failing INSERT`() {
        val deferred = DeferredIssues(jdbc)
        val d = Deferred(repo, 8, 0, "engine", "feature", null, IssueSnapshot(8, "t", "b", "someone"), listOf(Blocker(repo, 9, "open")))
        assertTrue(deferred.defer(d))
        assertFalse(deferred.defer(d.copy(blockers = listOf(Blocker(repo, 10, "open")))))
        assertEquals(emptyList(), failed)
        assertEquals(10L, deferred.get(repo, 8)?.blockers?.single()?.number)
        deferred.remove(repo, 8)
    }
}
