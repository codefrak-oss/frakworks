package com.codefrak.engine.repos

import com.codefrak.engine.accounts.SecretsKey
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository
import java.sql.ResultSet
import java.time.OffsetDateTime

/** [TargetRepos] as the engine's own `engine.target_repo` table; Flyway owns its schema (db/migration). */
@Repository
class StoredTargetRepos(private val jdbc: JdbcClient, private val secrets: SecretsKey) : TargetRepos {
    override fun list(): List<TargetRepo> =
        jdbc.sql("""SELECT full_name, added_at, ci_check, ci_paths, token IS NOT NULL, token_set_at, security_review,
                    webhook_secret IS NOT NULL, webhook_secret_set_at, scan_interval_minutes, doctor_bugs, engine_defects, friction_bugs, docs_first, child_mode, publish_work_items,
                    smith_token IS NOT NULL, smith_token_set_at
                    FROM engine.target_repo ORDER BY full_name""")
            .query { rs, _ -> row(rs) }.list()

    /** False when it was already there (its check settings and scan interval are then left as they were). */
    override fun add(fullName: String, ciCheck: String?, ciPaths: List<String>, securityReview: Boolean,
            scanIntervalMinutes: Int): Boolean =
        jdbc.sql("""INSERT INTO engine.target_repo (full_name, ci_check, ci_paths, security_review, scan_interval_minutes)
                    SELECT :n, CAST(:c AS VARCHAR(200)), CAST(:p AS VARCHAR(2000)), :s, :i
                    WHERE NOT EXISTS (SELECT 1 FROM engine.target_repo WHERE full_name = :n)""")
            .param("n", fullName).param("c", ciCheck).param("p", joined(ciPaths)).param("s", securityReview)
            .param("i", scanIntervalMinutes).update() == 1

    /** Sets both check columns and `security_review`; false when the repo is not there. */
    override fun update(fullName: String, ciCheck: String?, ciPaths: List<String>, securityReview: Boolean): Boolean =
        jdbc.sql("""UPDATE engine.target_repo SET ci_check = CAST(:c AS VARCHAR(200)), ci_paths = CAST(:p AS VARCHAR(2000)),
                    security_review = :s WHERE full_name = :n""")
            .param("n", fullName).param("c", ciCheck).param("p", joined(ciPaths)).param("s", securityReview).update() == 1

    /** Sets how often the engine scans the repo on its own, in minutes ([SCAN_INTERVAL_RANGE]); false when the repo is not there. */
    override fun setScanInterval(fullName: String, minutes: Int): Boolean {
        require(minutes in SCAN_INTERVAL_RANGE) { "a scan interval is ${SCAN_INTERVAL_RANGE.first} to ${SCAN_INTERVAL_RANGE.last} minutes" }
        return jdbc.sql("UPDATE engine.target_repo SET scan_interval_minutes = :i WHERE full_name = :n")
            .param("i", minutes).param("n", fullName).update() == 1
    }

    /**
     * Sets the repo's doctor bugs and whether it is the engine-defects repo; false when the repo is not there.
     * `engineDefects` needs `doctorBugs`, and only one repo has it: the schema refuses either (a check and a
     * unique index, V36), so the caller asks first ([engineDefectsRepo]).
     */
    override fun setDoctorBugs(fullName: String, doctorBugs: Boolean, engineDefects: Boolean): Boolean =
        jdbc.sql("UPDATE engine.target_repo SET doctor_bugs = :b, engine_defects = :e WHERE full_name = :n")
            .param("b", doctorBugs).param("e", engineDefects).param("n", fullName).update() == 1

    /** Sets whether the engine files two-things friction bugs in the repo; false when the repo is not there. */
    override fun setFrictionBugs(fullName: String, on: Boolean): Boolean =
        jdbc.sql("UPDATE engine.target_repo SET friction_bugs = :b WHERE full_name = :n").param("b", on).param("n", fullName).update() == 1

    /** Sets whether the repo is docs-first (engine/README.md, "Docs path"); false when the repo is not there. */
    override fun setDocsFirst(fullName: String, on: Boolean): Boolean =
        jdbc.sql("UPDATE engine.target_repo SET docs_first = :b WHERE full_name = :n").param("b", on).param("n", fullName).update() == 1

    /** Sets the repo's child-work mode, one of [CHILD_MODES] (engine/README.md, "Split features"); false when the repo is not there. */
    override fun setChildMode(fullName: String, mode: String): Boolean {
        require(mode in CHILD_MODES) { "childMode is ${CHILD_MODES.joinToString(", ")}" }
        return jdbc.sql("UPDATE engine.target_repo SET child_mode = :m WHERE full_name = :n").param("m", mode).param("n", fullName).update() == 1
    }

    /** Sets whether the engine publishes the repo's work items to GitHub, one way (V89); false when the repo is not there. */
    override fun setPublishWorkItems(fullName: String, on: Boolean): Boolean =
        jdbc.sql("UPDATE engine.target_repo SET publish_work_items = :b WHERE full_name = :n").param("b", on).param("n", fullName).update() == 1

    /** False when it was not there. */
    override fun remove(fullName: String): Boolean =
        jdbc.sql("DELETE FROM engine.target_repo WHERE full_name = :n").param("n", fullName).update() == 1

    /**
     * Seals the repo's own GitHub token under the engine's key, the row's name as associated data, and stores it;
     * false when there is no such row. `fullName` is the row's exact name ([find] gives it).
     */
    override fun setToken(fullName: String, token: String): Boolean =
        jdbc.sql("UPDATE engine.target_repo SET token = :t, key_id = :k, token_set_at = CURRENT_TIMESTAMP WHERE full_name = :n")
            .param("t", secrets.seal(token, fullName)).param("k", secrets.id).param("n", fullName).update() == 1

    /** Drops the repo's token, so nothing runs on it until it is given another; false when there is no such row. */
    override fun clearToken(fullName: String): Boolean =
        jdbc.sql("UPDATE engine.target_repo SET token = NULL, key_id = NULL, token_set_at = NULL WHERE full_name = :n")
            .param("n", fullName).update() == 1

    /**
     * The repo's own token, opened, or null when its row has none (or there is no row); `fullName` matched ignoring
     * case, as GitHub does. Callers are the GitHub clients, through `RepoTokens`, never the API.
     */
    override fun token(fullName: String): String? =
        jdbc.sql("SELECT full_name, token FROM engine.target_repo WHERE LOWER(full_name) = LOWER(:n) AND token IS NOT NULL").param("n", fullName)
            .query { rs, _ -> rs.getString(1) to rs.getBytes(2) }.optional().map { (name, sealed) -> secrets.open(sealed, name) }.orElse(null)

    /**
     * Seals the repo's Smith token (the sandbox's GH_TOKEN, no Issues permission) and stores it; false when there is no
     * such row. The associated data is the row's name and `#smith`, so the ciphertext does not open as the row's token.
     */
    override fun setSmithToken(fullName: String, token: String): Boolean =
        jdbc.sql("UPDATE engine.target_repo SET smith_token = :t, smith_token_key_id = :k, smith_token_set_at = CURRENT_TIMESTAMP WHERE full_name = :n")
            .param("t", secrets.seal(token, "$fullName#smith")).param("k", secrets.id).param("n", fullName).update() == 1

    /** Drops the repo's Smith token, so no Smith runs on it until it is given another; false when there is no such row. */
    override fun clearSmithToken(fullName: String): Boolean =
        jdbc.sql("UPDATE engine.target_repo SET smith_token = NULL, smith_token_key_id = NULL, smith_token_set_at = NULL WHERE full_name = :n")
            .param("n", fullName).update() == 1

    /** The repo's Smith token, opened, or null when its row has none; never the repo's own token. Callers are the sandboxes, through `RepoTokens`. */
    override fun smithToken(fullName: String): String? =
        jdbc.sql("SELECT full_name, smith_token FROM engine.target_repo WHERE LOWER(full_name) = LOWER(:n) AND smith_token IS NOT NULL").param("n", fullName)
            .query { rs, _ -> rs.getString(1) to rs.getBytes(2) }.optional().map { (name, sealed) -> secrets.open(sealed, "$name#smith") }.orElse(null)

    /**
     * Seals the repo's webhook secret under the engine's key and stores it; false when there is no such row. The
     * associated data is the row's name and `#webhook`, so the ciphertext does not open as the row's token.
     */
    override fun setWebhookSecret(fullName: String, secret: String): Boolean =
        jdbc.sql("UPDATE engine.target_repo SET webhook_secret = :s, webhook_secret_key_id = :k, webhook_secret_set_at = CURRENT_TIMESTAMP WHERE full_name = :n")
            .param("s", secrets.seal(secret, webhookAad(fullName))).param("k", secrets.id).param("n", fullName).update() == 1

    /** Drops the repo's webhook secret: its deliveries are refused until it has another; false when there is no such row. */
    override fun clearWebhookSecret(fullName: String): Boolean =
        jdbc.sql("UPDATE engine.target_repo SET webhook_secret = NULL, webhook_secret_key_id = NULL, webhook_secret_set_at = NULL WHERE full_name = :n")
            .param("n", fullName).update() == 1

    /**
     * The repo's webhook secret, opened, or null when its row has none, there is no row, or the engine has no
     * sealing key; `repo` matched ignoring case, as GitHub does. The one caller is the webhook's signature check.
     */
    override fun webhookSecret(repo: String): String? {
        if (!secrets.configured) return null
        return jdbc.sql("SELECT full_name, webhook_secret FROM engine.target_repo WHERE LOWER(full_name) = LOWER(:n) AND webhook_secret IS NOT NULL")
            .param("n", repo).query { rs, _ -> rs.getString(1) to rs.getBytes(2) }.optional()
            .map { (name, sealed) -> secrets.open(sealed, webhookAad(name)) }.orElse(null)
    }

    private fun webhookAad(fullName: String) = "$fullName#webhook"

    private fun row(rs: ResultSet) = TargetRepo(rs.getString(1), rs.getObject(2, OffsetDateTime::class.java), rs.getString(3),
        rs.getString(4).orEmpty().split(',').map { it.trim() }.filter { it.isNotEmpty() },
        rs.getBoolean(5), rs.getObject(6, OffsetDateTime::class.java), rs.getBoolean(7),
        rs.getBoolean(8), rs.getObject(9, OffsetDateTime::class.java), rs.getInt(10), doctorBugs = rs.getBoolean(11),
        engineDefects = rs.getBoolean(12), frictionBugs = rs.getBoolean(13), docsFirst = rs.getBoolean(14),
        childMode = rs.getString(15), publishWorkItems = rs.getBoolean(16),
        hasSmithToken = rs.getBoolean(17), smithTokenSetAt = rs.getObject(18, OffsetDateTime::class.java))

    private fun joined(paths: List<String>): String? = paths.takeIf { it.isNotEmpty() }?.joinToString(",")
}
