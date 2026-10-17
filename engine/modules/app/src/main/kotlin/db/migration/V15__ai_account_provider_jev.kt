package db.migration

import org.flywaydb.core.api.migration.BaseJavaMigration
import org.flywaydb.core.api.migration.Context

/**
 * `jev` joins `anthropic` and `openai` as an `ai_account.provider`
 * (engine/README.md, "AI accounts"). V2 declared the CHECK inline, so it has
 * no name of ours: H2 calls it `CONSTRAINT_<n>`, Postgres
 * `ai_account_provider_check`. Kotlin, not SQL, to find it by its clause in
 * INFORMATION_SCHEMA (the same on both) and drop it; the new one is named.
 * The clause match on `anthropic` skips Postgres's NOT NULL checks.
 */
@Suppress("ClassName")
class V15__ai_account_provider_jev : BaseJavaMigration() {
    override fun migrate(context: Context) {
        val connection = context.connection
        val names = connection.prepareStatement("""
            SELECT tc.CONSTRAINT_NAME FROM INFORMATION_SCHEMA.TABLE_CONSTRAINTS tc
            JOIN INFORMATION_SCHEMA.CHECK_CONSTRAINTS cc
              ON cc.CONSTRAINT_SCHEMA = tc.CONSTRAINT_SCHEMA AND cc.CONSTRAINT_NAME = tc.CONSTRAINT_NAME
            WHERE UPPER(tc.TABLE_SCHEMA) = 'ENGINE' AND UPPER(tc.TABLE_NAME) = 'AI_ACCOUNT'
              AND tc.CONSTRAINT_TYPE = 'CHECK' AND cc.CHECK_CLAUSE LIKE '%anthropic%'""").use { st ->
            st.executeQuery().use { rs -> buildList<String> { while (rs.next()) add(rs.getString(1)) } }
        }
        check(names.isNotEmpty()) { "no provider CHECK on engine.ai_account to replace" }
        connection.createStatement().use { st ->
            names.forEach { st.execute("ALTER TABLE engine.ai_account DROP CONSTRAINT \"${it.replace("\"", "\"\"")}\"") }
            st.execute("ALTER TABLE engine.ai_account ADD CONSTRAINT ai_account_provider CHECK (provider IN ('anthropic', 'openai', 'jev'))")
        }
    }
}
