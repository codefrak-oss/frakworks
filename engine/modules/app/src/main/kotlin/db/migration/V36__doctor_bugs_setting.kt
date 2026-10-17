package db.migration

import org.flywaydb.core.api.migration.BaseJavaMigration
import org.flywaydb.core.api.migration.Context

/**
 * Doctor bugs (engine/README.md, "Doctor"): `doctor_bugs` lets the doctor
 * file engine defects from a repo's stuck tickets; `engine_defects` marks the one repo they are filed in, and
 * needs `doctor_bugs`. Both off for every repo; no row is set true.
 *
 * In Kotlin, not SQL, for one statement: "at most one row is true" is a partial unique index on Postgres, which
 * H2 (the tests' database) cannot parse; H2 gets the same rule from a unique index over a generated column that is
 * true for the marked row and null for the rest (a unique index allows any number of nulls).
 */
@Suppress("ClassName")
class V36__doctor_bugs_setting : BaseJavaMigration() {
    override fun migrate(context: Context) {
        val postgres = context.connection.metaData.databaseProductName.contains("PostgreSQL", ignoreCase = true)
        val statements = listOf(
            "ALTER TABLE engine.target_repo ADD COLUMN doctor_bugs BOOLEAN NOT NULL DEFAULT FALSE",
            "ALTER TABLE engine.target_repo ADD COLUMN engine_defects BOOLEAN NOT NULL DEFAULT FALSE",
            "ALTER TABLE engine.target_repo ADD CONSTRAINT target_repo_engine_defects_needs_doctor_bugs CHECK (doctor_bugs OR NOT engine_defects)",
        ) + if (postgres) listOf(
            "CREATE UNIQUE INDEX target_repo_engine_defects ON engine.target_repo (engine_defects) WHERE engine_defects",
        ) else listOf(
            "ALTER TABLE engine.target_repo ADD COLUMN engine_defects_one BOOLEAN GENERATED ALWAYS AS (CASE WHEN engine_defects THEN TRUE END)",
            "CREATE UNIQUE INDEX target_repo_engine_defects ON engine.target_repo (engine_defects_one)",
        )
        context.connection.createStatement().use { s -> statements.forEach(s::execute) }
    }
}
