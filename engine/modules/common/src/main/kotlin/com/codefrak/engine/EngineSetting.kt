package com.codefrak.engine

import org.springframework.jdbc.core.simple.JdbcClient

/** Shared "update, insert if no row" upsert against `engine.engine_setting`. */
fun JdbcClient.upsertEngineSetting(name: String, value: String) {
    val updated = sql("UPDATE engine.engine_setting SET setting = :v, updated_at = CURRENT_TIMESTAMP WHERE name = :n")
        .param("v", value).param("n", name).update()
    if (updated == 0) sql("INSERT INTO engine.engine_setting (name, setting) VALUES (:n, :v)")
        .param("n", name).param("v", value).update()
}

/** `name`'s row of `engine.engine_setting`, or null if it has none. The read half of [upsertEngineSetting]. */
fun JdbcClient.engineSetting(name: String): String? =
    sql("SELECT setting FROM engine.engine_setting WHERE name = :n").param("n", name)
        .query(String::class.java).optional().orElse(null)
