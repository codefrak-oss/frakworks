package com.codefrak.engine.repos

import com.codefrak.engine.accounts.SecretsKey
import com.codefrak.engine.ci.CiCheck
import com.codefrak.engine.ci.RepoCiChecks
import com.codefrak.engine.webhook.WebhookSecrets
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository
import java.sql.ResultSet
import java.time.OffsetDateTime

/**
 * A target repo's owner/name, as `POST /repos` takes it; the frakworks MCP server's repo tools check it too, and so
 * does the friction fix link (`PATCH /friction/groups/{id}`).
 */
val FULL_NAME = Regex("[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+")

/**
 * `ciCheck` null: the repo has no engine check. `ciPaths` empty: every path needs it.
 * `securityReview` false: its chores, bugs and features skip the security review (engine/README.md, "Chore").
 * `hasToken`: the row holds its sealed GitHub token (engine/README.md, "Each repo's token"); the token itself
 * never leaves [TargetRepos.token].
 * `hasSmithToken`: the row holds its sealed Smith token, the one a Smith sandbox gets as GH_TOKEN, with no Issues
 * permission (engine/README.md, "Each repo's token"); the token itself never leaves [TargetRepos.smithToken].
 * `hasWebhookSecret`: the row holds its sealed webhook secret (engine/README.md, "When it scans"); the secret itself
 * never leaves [TargetRepos.webhookSecret].
 * `scanIntervalMinutes`: how often the engine scans it on its own, 1 to 1440 (engine/README.md, "When it scans").
 * `scanCalls`: what one scan of it costs in GitHub calls, filled in by `GET /repos` (null elsewhere);
 * `scanCallsPerScan` is its total, 0 when it is not filled in.
 * `doctorBugs`: the doctor files engine defects it sees on the repo's stuck tickets (engine/README.md, "Doctor").
 * `engineDefects`: the one repo those defects are filed in; only with `doctorBugs`.
 * `frictionBugs`: the engine files one bug per group of friction notes whose note Jev marks as two things, in the
 * note's repo (engine/README.md, "Friction notes", "Two things"); independent of `doctorBugs`.
 * `docsFirst`: the repo is mostly documentation; its tickets default to the light docs path (engine/README.md, "Docs path").
 * `childMode`: how a feature's split is cut and its children worked, one of [CHILD_MODES] (engine/README.md, "Split features").
 * `publishWorkItems`: the engine writes a one-way GitHub issue copy of each of the repo's work items (V89,
 * engine/docs/work-items.md, "Sources"); off by default, and nothing on the copy is read back.
 * `tokenPermission`, `callerPermission`: GitHub's collaborator permission of the repo token's account and of the
 * caller's login (its bearer token's), filled in by `GET /repos` from a cache held for the scan interval (null
 * elsewhere, and when GitHub would not say).
 */
data class TargetRepo(val fullName: String, val addedAt: OffsetDateTime, val ciCheck: String? = null, val ciPaths: List<String> = emptyList(),
                      val hasToken: Boolean = false, val tokenSetAt: OffsetDateTime? = null, val securityReview: Boolean = true,
                      val hasWebhookSecret: Boolean = false, val webhookSecretSetAt: OffsetDateTime? = null,
                      val scanIntervalMinutes: Int = DEFAULT_SCAN_INTERVAL_MINUTES, val scanCalls: ScanCalls? = null,
                      val doctorBugs: Boolean = false, val engineDefects: Boolean = false, val frictionBugs: Boolean = false,
                      val docsFirst: Boolean = false,
                      val childMode: String = DEFAULT_CHILD_MODE, val publishWorkItems: Boolean = false,
                      val tokenPermission: String? = null, val callerPermission: String? = null,
                      val hasSmithToken: Boolean = false, val smithTokenSetAt: OffsetDateTime? = null) {
    val scanCallsPerScan: Int get() = scanCalls?.total ?: 0
}

/**
 * The GitHub calls one scan of a repo makes, from what the engine knows now (engine/README.md, "When it scans"):
 * `listing` (the `authorized` listing, one call a 100 issues), `authorized` (one events read per open `authorized`
 * issue its last scan listed), `deferred` (one blocked-by read per waiting approval), `checks` (one check-runs
 * read per flow waiting on its engine check).
 */
data class ScanCalls(val listing: Int, val authorized: Int, val deferred: Int, val checks: Int) {
    val total: Int get() = listing + authorized + deferred + checks
}

/**
 * A new repo's scan interval when `POST /repos` names none, written on its row: the hourly scan every repo had before
 * the interval was per repo (V32 gave the existing rows the same). Never read back as a fallback: the column is NOT NULL.
 */
const val DEFAULT_SCAN_INTERVAL_MINUTES = 60

/** The scan interval's bounds, in minutes: a minute (the scheduler's tick) to a day. */
val SCAN_INTERVAL_RANGE = 1..1440

/**
 * A repo's child-work modes (engine/README.md, "Split features"), the values `engine.target_repo.child_mode` takes (V86):
 * `serial` (the children are worked one after another, with a replan between), `parallel` (the design is cut for children
 * that can run at the same time) and `live` (as `parallel`, with the parent's session running on while they are worked).
 * `parallel` and `live` steer the design prompt; their flow behaviour lands in later parts of #2499.
 */
val CHILD_MODES = listOf("serial", "parallel", "live")

/** The mode of every repo until it is set: the column's default and today's flow behaviour. */
const val DEFAULT_CHILD_MODE = "serial"

/** The engine's own `engine.target_repo` table; Flyway owns its schema (db/migration). */
@Repository
class TargetRepos(private val jdbc: JdbcClient, private val secrets: SecretsKey) : RepoCiChecks, WebhookSecrets {
    fun list(): List<TargetRepo> =
        jdbc.sql("""SELECT full_name, added_at, ci_check, ci_paths, token IS NOT NULL, token_set_at, security_review,
                    webhook_secret IS NOT NULL, webhook_secret_set_at, scan_interval_minutes, doctor_bugs, engine_defects, friction_bugs, docs_first, child_mode, publish_work_items,
                    smith_token IS NOT NULL, smith_token_set_at
                    FROM engine.target_repo ORDER BY full_name""")
            .query { rs, _ -> row(rs) }.list()

    fun contains(fullName: String): Boolean = list().any { it.fullName == fullName }

    /** The row whose name matches `fullName` ignoring case, or null. */
    fun find(fullName: String): TargetRepo? = list().firstOrNull { it.fullName.equals(fullName, ignoreCase = true) }

    /** False when it was already there (its check settings and scan interval are then left as they were). */
    fun add(fullName: String, ciCheck: String? = null, ciPaths: List<String> = emptyList(), securityReview: Boolean = true,
            scanIntervalMinutes: Int = DEFAULT_SCAN_INTERVAL_MINUTES): Boolean =
        jdbc.sql("""INSERT INTO engine.target_repo (full_name, ci_check, ci_paths, security_review, scan_interval_minutes)
                    SELECT :n, CAST(:c AS VARCHAR(200)), CAST(:p AS VARCHAR(2000)), :s, :i
                    WHERE NOT EXISTS (SELECT 1 FROM engine.target_repo WHERE full_name = :n)""")
            .param("n", fullName).param("c", ciCheck).param("p", joined(ciPaths)).param("s", securityReview)
            .param("i", scanIntervalMinutes).update() == 1

    /** Sets both check columns and `security_review`; false when the repo is not there. */
    fun update(fullName: String, ciCheck: String?, ciPaths: List<String>, securityReview: Boolean): Boolean =
        jdbc.sql("""UPDATE engine.target_repo SET ci_check = CAST(:c AS VARCHAR(200)), ci_paths = CAST(:p AS VARCHAR(2000)),
                    security_review = :s WHERE full_name = :n""")
            .param("n", fullName).param("c", ciCheck).param("p", joined(ciPaths)).param("s", securityReview).update() == 1

    /** Sets how often the engine scans the repo on its own, in minutes ([SCAN_INTERVAL_RANGE]); false when the repo is not there. */
    fun setScanInterval(fullName: String, minutes: Int): Boolean {
        require(minutes in SCAN_INTERVAL_RANGE) { "a scan interval is ${SCAN_INTERVAL_RANGE.first} to ${SCAN_INTERVAL_RANGE.last} minutes" }
        return jdbc.sql("UPDATE engine.target_repo SET scan_interval_minutes = :i WHERE full_name = :n")
            .param("i", minutes).param("n", fullName).update() == 1
    }

    /**
     * Sets the repo's doctor bugs and whether it is the engine-defects repo; false when the repo is not there.
     * `engineDefects` needs `doctorBugs`, and only one repo has it: the schema refuses either (a check and a
     * unique index, V36), so the caller asks first ([engineDefectsRepo]).
     */
    fun setDoctorBugs(fullName: String, doctorBugs: Boolean, engineDefects: Boolean): Boolean =
        jdbc.sql("UPDATE engine.target_repo SET doctor_bugs = :b, engine_defects = :e WHERE full_name = :n")
            .param("b", doctorBugs).param("e", engineDefects).param("n", fullName).update() == 1

    /** The repo marked for engine defects (the doctor files its bugs there), or null when none is. */
    fun engineDefectsRepo(): String? = list().firstOrNull { it.engineDefects }?.fullName

    /** Whether the doctor files engine defects from `repo`'s stuck tickets, matched ignoring case; false when it has no row. */
    fun doctorBugs(repo: String): Boolean = find(repo)?.doctorBugs ?: false

    /** Sets whether the engine files two-things friction bugs in the repo; false when the repo is not there. */
    fun setFrictionBugs(fullName: String, on: Boolean): Boolean =
        jdbc.sql("UPDATE engine.target_repo SET friction_bugs = :b WHERE full_name = :n").param("b", on).param("n", fullName).update() == 1

    /** Whether the engine files two-things friction bugs in `repo`, matched ignoring case; false when it has no row. */
    fun frictionBugs(repo: String): Boolean = find(repo)?.frictionBugs ?: false

    /** Sets whether the repo is docs-first (engine/README.md, "Docs path"); false when the repo is not there. */
    fun setDocsFirst(fullName: String, on: Boolean): Boolean =
        jdbc.sql("UPDATE engine.target_repo SET docs_first = :b WHERE full_name = :n").param("b", on).param("n", fullName).update() == 1

    /** Whether `repo` is docs-first, matched ignoring case; false when it has no row. */
    fun docsFirst(repo: String): Boolean = find(repo)?.docsFirst ?: false

    /** Sets the repo's child-work mode, one of [CHILD_MODES] (engine/README.md, "Split features"); false when the repo is not there. */
    fun setChildMode(fullName: String, mode: String): Boolean {
        require(mode in CHILD_MODES) { "childMode is ${CHILD_MODES.joinToString(", ")}" }
        return jdbc.sql("UPDATE engine.target_repo SET child_mode = :m WHERE full_name = :n").param("m", mode).param("n", fullName).update() == 1
    }

    /** `repo`'s child-work mode, matched ignoring case; [DEFAULT_CHILD_MODE] when it has no row. */
    fun childMode(repo: String): String = find(repo)?.childMode ?: DEFAULT_CHILD_MODE

    /** Sets whether the engine publishes the repo's work items to GitHub, one way (V89); false when the repo is not there. */
    fun setPublishWorkItems(fullName: String, on: Boolean): Boolean =
        jdbc.sql("UPDATE engine.target_repo SET publish_work_items = :b WHERE full_name = :n").param("b", on).param("n", fullName).update() == 1

    /** Whether `repo` publishes its work items, matched ignoring case; false when it has no row. */
    fun publishWorkItems(repo: String): Boolean = find(repo)?.publishWorkItems ?: false

    /** False when it was not there. */
    fun remove(fullName: String): Boolean =
        jdbc.sql("DELETE FROM engine.target_repo WHERE full_name = :n").param("n", fullName).update() == 1

    /**
     * Seals the repo's own GitHub token under the engine's key, the row's name as associated data, and stores it;
     * false when there is no such row. `fullName` is the row's exact name ([find] gives it).
     */
    fun setToken(fullName: String, token: String): Boolean =
        jdbc.sql("UPDATE engine.target_repo SET token = :t, key_id = :k, token_set_at = CURRENT_TIMESTAMP WHERE full_name = :n")
            .param("t", secrets.seal(token, fullName)).param("k", secrets.id).param("n", fullName).update() == 1

    /** Drops the repo's token, so nothing runs on it until it is given another; false when there is no such row. */
    fun clearToken(fullName: String): Boolean =
        jdbc.sql("UPDATE engine.target_repo SET token = NULL, key_id = NULL, token_set_at = NULL WHERE full_name = :n")
            .param("n", fullName).update() == 1

    /**
     * The repo's own token, opened, or null when its row has none (or there is no row); `fullName` matched ignoring
     * case, as GitHub does. Callers are the GitHub clients, through `RepoTokens`, never the API.
     */
    fun token(fullName: String): String? =
        jdbc.sql("SELECT full_name, token FROM engine.target_repo WHERE LOWER(full_name) = LOWER(:n) AND token IS NOT NULL").param("n", fullName)
            .query { rs, _ -> rs.getString(1) to rs.getBytes(2) }.optional().map { (name, sealed) -> secrets.open(sealed, name) }.orElse(null)

    /**
     * Seals the repo's Smith token (the sandbox's GH_TOKEN, no Issues permission) and stores it; false when there is no
     * such row. The associated data is the row's name and `#smith`, so the ciphertext does not open as the row's token.
     */
    fun setSmithToken(fullName: String, token: String): Boolean =
        jdbc.sql("UPDATE engine.target_repo SET smith_token = :t, smith_token_key_id = :k, smith_token_set_at = CURRENT_TIMESTAMP WHERE full_name = :n")
            .param("t", secrets.seal(token, "$fullName#smith")).param("k", secrets.id).param("n", fullName).update() == 1

    /** Drops the repo's Smith token, so no Smith runs on it until it is given another; false when there is no such row. */
    fun clearSmithToken(fullName: String): Boolean =
        jdbc.sql("UPDATE engine.target_repo SET smith_token = NULL, smith_token_key_id = NULL, smith_token_set_at = NULL WHERE full_name = :n")
            .param("n", fullName).update() == 1

    /** The repo's Smith token, opened, or null when its row has none; never the repo's own token. Callers are the sandboxes, through `RepoTokens`. */
    fun smithToken(fullName: String): String? =
        jdbc.sql("SELECT full_name, smith_token FROM engine.target_repo WHERE LOWER(full_name) = LOWER(:n) AND smith_token IS NOT NULL").param("n", fullName)
            .query { rs, _ -> rs.getString(1) to rs.getBytes(2) }.optional().map { (name, sealed) -> secrets.open(sealed, "$name#smith") }.orElse(null)

    /**
     * Seals the repo's webhook secret under the engine's key and stores it; false when there is no such row. The
     * associated data is the row's name and `#webhook`, so the ciphertext does not open as the row's token.
     */
    fun setWebhookSecret(fullName: String, secret: String): Boolean =
        jdbc.sql("UPDATE engine.target_repo SET webhook_secret = :s, webhook_secret_key_id = :k, webhook_secret_set_at = CURRENT_TIMESTAMP WHERE full_name = :n")
            .param("s", secrets.seal(secret, webhookAad(fullName))).param("k", secrets.id).param("n", fullName).update() == 1

    /** Drops the repo's webhook secret: its deliveries are refused until it has another; false when there is no such row. */
    fun clearWebhookSecret(fullName: String): Boolean =
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

    /** The repo's row's check, or null when it has no row or its row names none. */
    override fun ciCheck(repo: String): CiCheck? = find(repo)?.let { r -> r.ciCheck?.let { CiCheck(it, r.ciPaths) } }

    /**
     * Whether `repo`'s chores, bugs and features get a security review: its row's `security_review`, `fullName`
     * matched ignoring case; true when it has no row, as before the setting.
     */
    fun securityReview(repo: String): Boolean = find(repo)?.securityReview ?: true

    private fun row(rs: ResultSet) = TargetRepo(rs.getString(1), rs.getObject(2, OffsetDateTime::class.java), rs.getString(3),
        rs.getString(4).orEmpty().split(',').map { it.trim() }.filter { it.isNotEmpty() },
        rs.getBoolean(5), rs.getObject(6, OffsetDateTime::class.java), rs.getBoolean(7),
        rs.getBoolean(8), rs.getObject(9, OffsetDateTime::class.java), rs.getInt(10), doctorBugs = rs.getBoolean(11),
        engineDefects = rs.getBoolean(12), frictionBugs = rs.getBoolean(13), docsFirst = rs.getBoolean(14),
        childMode = rs.getString(15), publishWorkItems = rs.getBoolean(16),
        hasSmithToken = rs.getBoolean(17), smithTokenSetAt = rs.getObject(18, OffsetDateTime::class.java))

    private fun joined(paths: List<String>): String? = paths.takeIf { it.isNotEmpty() }?.joinToString(",")
}
