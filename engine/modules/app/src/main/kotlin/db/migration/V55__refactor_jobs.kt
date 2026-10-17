package db.migration

import org.flywaydb.core.api.migration.BaseJavaMigration
import org.flywaydb.core.api.migration.Context

/**
 * Refactoring jobs (#1352; engine/README.md, "Refactoring jobs"): `engine.job` gains `owner` (user|system, every
 * existing row `user`) and the kind `refactor`, and `engine.refactor_config` holds one row per target repo that set
 * its refactoring up. The V46 kind CHECK was inline, so its name is the database's (`job_kind_check` on Postgres, a
 * generated one on H2): looked up and dropped, as V40 did for the account provider.
 */
@Suppress("ClassName")
class V55__refactor_jobs : BaseJavaMigration() {
    override fun migrate(context: Context) {
        val connection = context.connection
        val names = connection.prepareStatement("""
            SELECT tc.CONSTRAINT_NAME FROM INFORMATION_SCHEMA.TABLE_CONSTRAINTS tc
            JOIN INFORMATION_SCHEMA.CHECK_CONSTRAINTS cc
              ON cc.CONSTRAINT_SCHEMA = tc.CONSTRAINT_SCHEMA AND cc.CONSTRAINT_NAME = tc.CONSTRAINT_NAME
            WHERE UPPER(tc.TABLE_SCHEMA) = 'ENGINE' AND UPPER(tc.TABLE_NAME) = 'JOB'
              AND tc.CONSTRAINT_TYPE = 'CHECK' AND cc.CHECK_CLAUSE LIKE '%prompt%'""").use { st ->
            st.executeQuery().use { rs -> buildList<String> { while (rs.next()) add(rs.getString(1)) } }
        }
        check(names.isNotEmpty()) { "no kind CHECK on engine.job to replace" }
        connection.createStatement().use { st ->
            names.forEach { st.execute("ALTER TABLE engine.job DROP CONSTRAINT \"${it.replace("\"", "\"\"")}\"") }
            st.execute("ALTER TABLE engine.job ADD CONSTRAINT job_kind CHECK (kind IN ('prompt', 'bean', 'flow', 'refactor'))")
            // Who owns the job: a user registered it (POST /jobs), or the engine made it for a repo's refactoring config.
            st.execute("ALTER TABLE engine.job ADD COLUMN owner VARCHAR(6) NOT NULL DEFAULT 'user'")
            st.execute("ALTER TABLE engine.job ADD CONSTRAINT job_owner CHECK (owner IN ('user', 'system'))")
            // One row per target repo that set its refactoring up; GET answers the defaults for a repo with none.
            // values_text NULL: the shipped bots/refactor-values.md is in force, so a later edit of it reaches the repo.
            st.execute("""
                CREATE TABLE engine.refactor_config (
                    repo             VARCHAR(200) PRIMARY KEY REFERENCES engine.target_repo (full_name) ON DELETE CASCADE,
                    enabled          BOOLEAN      NOT NULL DEFAULT FALSE,
                    schedule_seconds INT          NOT NULL DEFAULT 604800 CHECK (schedule_seconds >= 60),
                    values_text      TEXT,
                    max_tickets      INT          NOT NULL DEFAULT 2 CHECK (max_tickets BETWEEN 1 AND 20),
                    max_lines        INT          NOT NULL DEFAULT 300 CHECK (max_lines BETWEEN 10 AND 5000),
                    approval         VARCHAR(5)   NOT NULL DEFAULT 'human' CHECK (approval IN ('human', 'none')),
                    job_id           VARCHAR(36)  REFERENCES engine.job (id) ON DELETE SET NULL,
                    updated_at       TIMESTAMP WITH TIME ZONE NOT NULL,
                    updated_by       VARCHAR(100)
                )""")
        }
    }
}
