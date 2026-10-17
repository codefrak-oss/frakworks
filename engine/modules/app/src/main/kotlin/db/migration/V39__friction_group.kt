package db.migration

import com.codefrak.engine.friction.FrictionKey
import org.flywaydb.core.api.migration.BaseJavaMigration
import org.flywaydb.core.api.migration.Context

/**
 * Friction notes grouped by signature (engine/README.md, "Friction notes"): `engine.friction_group` (a group of notes that one change would have spared), `engine.friction_signature`
 * (each exact key and the group it points at), and on `engine.friction_note` the key (`signature`), the group, how the
 * note joined it (`grouped_by`: `new`, `exact`, `jev`, `fallback`, `no-jev`, `user`; null while ungrouped), the
 * `friction-group` Jev call, and the grouper's retry state (`group_attempts`, `next_attempt_at`).
 *
 * In Kotlin, not SQL, because the backfill computes every existing row's key with [FrictionKey.of], the function
 * `FrictionNotes.insert` uses, so no second rule exists; and because the drain's index is partial on Postgres
 * (`WHERE group_id IS NULL`), which H2 cannot parse: H2 gets a plain index over `(group_id, noted_at)`, as
 * `V36__doctor_bugs_setting` mirrors its partial unique index. Every group is left null: the grouper's first drain
 * groups the existing notes oldest first, by the same steps as new ones.
 */
@Suppress("ClassName")
class V39__friction_group : BaseJavaMigration() {
    override fun migrate(context: Context) {
        val c = context.connection
        val postgres = c.metaData.databaseProductName.contains("PostgreSQL", ignoreCase = true)
        val before = listOf(
            """CREATE TABLE engine.friction_group (
                id          BIGSERIAL     PRIMARY KEY,
                title       VARCHAR(240)  NOT NULL,   -- the founder's summary line, cut
                created_at  TIMESTAMP WITH TIME ZONE NOT NULL
            )""",
            """CREATE TABLE engine.friction_signature (
                signature   VARCHAR(64)   PRIMARY KEY,  -- FrictionKey.of: SHA-256 of the field and the normalised key line
                group_id    BIGINT        NOT NULL REFERENCES engine.friction_group (id),
                line        VARCHAR(160)  NOT NULL,     -- the normalised key line, for reading
                first_seen  TIMESTAMP WITH TIME ZONE NOT NULL
            )""",
            "ALTER TABLE engine.friction_note ADD COLUMN signature VARCHAR(64)",
            "ALTER TABLE engine.friction_note ADD COLUMN group_id BIGINT REFERENCES engine.friction_group (id)",
            "ALTER TABLE engine.friction_note ADD COLUMN grouped_by VARCHAR(10)",
            "ALTER TABLE engine.friction_note ADD COLUMN jev_call_id VARCHAR(64)",
            "ALTER TABLE engine.friction_note ADD COLUMN group_attempts INTEGER NOT NULL DEFAULT 0",
            "ALTER TABLE engine.friction_note ADD COLUMN next_attempt_at TIMESTAMP WITH TIME ZONE",
            "CREATE INDEX friction_note_group ON engine.friction_note (group_id)",
        ) + if (postgres) listOf(
            "CREATE INDEX friction_note_ungrouped ON engine.friction_note (noted_at) WHERE group_id IS NULL",
        ) else listOf(
            "CREATE INDEX friction_note_ungrouped ON engine.friction_note (group_id, noted_at)",
        )
        c.createStatement().use { s -> before.forEach(s::execute) }

        // The backfill: every existing row's key, by the insert's own function.
        val rows = mutableListOf<Triple<String, String, String?>>()
        c.createStatement().use { s ->
            s.executeQuery("SELECT id, summary, evidence FROM engine.friction_note").use { rs ->
                while (rs.next()) rows += Triple(rs.getString(1), rs.getString(2), rs.getString(3))
            }
        }
        c.prepareStatement("UPDATE engine.friction_note SET signature = ? WHERE id = ?").use { ps ->
            for ((id, summary, evidence) in rows) {
                ps.setString(1, FrictionKey.of(summary, evidence))
                ps.setString(2, id)
                ps.addBatch()
            }
            if (rows.isNotEmpty()) ps.executeBatch()
        }
        c.createStatement().use { s -> s.execute("ALTER TABLE engine.friction_note ALTER COLUMN signature SET NOT NULL") }
    }
}
