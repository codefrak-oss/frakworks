package db.migration

import org.flywaydb.core.api.migration.BaseJavaMigration
import org.flywaydb.core.api.migration.Context

/**
 * The engine stops being a HITLP server (#2484; engine/README.md, "The HITLP requester"): it files its waits on the
 * HITLP server it is configured with and serves none itself. Before the tables go, every open `engine.hitlp_task`
 * row and every `engine.hitlp_rule` row is raised as a NOTICE, as JSON, so the deploy log keeps what to re-raise on
 * that server and which standing approvals to re-create there. A PL/pgSQL `DO` block is Postgres's alone: on the
 * test build's plain H2 the notices are skipped and the rest is the same. Then `hitlp_rule_writer`, `hitlp_rule`
 * and `hitlp_task` are dropped and the `hitlp.%` settings (V69's caps and retention) deleted. Nothing is kept.
 */
@Suppress("ClassName")
class V84__drop_hitlp_server : BaseJavaMigration() {
    override fun migrate(context: Context) {
        val connection = context.connection
        connection.createStatement().use { st ->
            if (connection.metaData.databaseProductName.equals("PostgreSQL", ignoreCase = true)) st.execute("""
                DO ${'$'}${'$'}
                DECLARE r RECORD;
                BEGIN
                    FOR r IN SELECT * FROM engine.hitlp_task WHERE status IN ('working', 'input_required') ORDER BY created_at LOOP
                        RAISE NOTICE 'hitlp_task %', row_to_json(r);
                    END LOOP;
                    FOR r IN SELECT * FROM engine.hitlp_rule ORDER BY created_at LOOP
                        RAISE NOTICE 'hitlp_rule %', row_to_json(r);
                    END LOOP;
                END
                ${'$'}${'$'}""")
            st.execute("DROP TABLE engine.hitlp_rule_writer")
            st.execute("DROP TABLE engine.hitlp_rule")
            st.execute("DROP TABLE engine.hitlp_task")
            st.execute("DELETE FROM engine.engine_setting WHERE name LIKE 'hitlp.%'")
        }
    }
}
