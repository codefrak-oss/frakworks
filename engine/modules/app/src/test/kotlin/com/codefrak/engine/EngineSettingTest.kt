package com.codefrak.engine

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.jdbc.core.simple.JdbcClient
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull

/** [JdbcClient.upsertEngineSetting]: insert on a name with no row, update in place on a second call. */
@SpringBootTest
class EngineSettingTest {
    @Autowired lateinit var jdbc: JdbcClient

    private val name = "engine-setting-test.probe"

    @AfterEach
    fun cleanUp() {
        jdbc.sql("DELETE FROM engine.engine_setting WHERE name = :n").param("n", name).update()
    }

    private fun row(): Pair<String, java.sql.Timestamp>? =
        jdbc.sql("SELECT setting, updated_at FROM engine.engine_setting WHERE name = :n").param("n", name)
            .query { rs, _ -> rs.getString(1) to rs.getTimestamp(2) }.optional().orElse(null)

    @Test
    fun `no existing row inserts one`() {
        assertNull(row())
        jdbc.upsertEngineSetting(name, "first")
        assertEquals("first", row()?.first)
    }

    @Test
    fun `engineSetting reads null on a missing row, the stored value after upsert`() {
        assertNull(jdbc.engineSetting(name))
        jdbc.upsertEngineSetting(name, "value")
        assertEquals("value", jdbc.engineSetting(name))
    }

    @Test
    fun `an existing row is updated in place, not duplicated, and updated_at advances`() {
        jdbc.upsertEngineSetting(name, "first")
        val before = row()
        Thread.sleep(5)
        jdbc.upsertEngineSetting(name, "second")
        val after = row()
        assertEquals("second", after?.first)
        assertNotEquals(before?.second, after?.second)
        assertEquals(1, jdbc.sql("SELECT count(*) FROM engine.engine_setting WHERE name = :n").param("n", name)
            .query(Int::class.java).single())
    }
}
