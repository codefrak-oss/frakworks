package com.codefrak.engine.github

import com.codefrak.engine.accounts.SecretsKey
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository
import java.time.OffsetDateTime

/** The deployment patterns and ALLOW lines [CliGitPush.push] checks a Smith's commits against, opened; null when none is set. */
fun interface DeploymentPatternSource {
    fun rules(): DeploymentRules?

    companion object {
        val NONE = DeploymentPatternSource { null }
    }
}

/** The patterns (one extended regex per line) and the exact benign lines that pass anyway. Never printed. */
class DeploymentRules(val patterns: String, val allow: String) {
    override fun toString() = "DeploymentRules(sealed)"
}

/** `GET /settings/deployment-patterns`: whether each is set and when, never a value. */
data class DeploymentPatternsView(val patternsSetAt: OffsetDateTime?, val allowSetAt: OffsetDateTime?)

/**
 * The deployment-patterns setting (#100115; engine/README.md, "Deployment patterns"): the one row of
 * `engine.deployment_patterns` (V102), the patterns and the ALLOW lines each sealed under the engine's key
 * ([PATTERNS_AAD], [ALLOW_AAD]). Set by `DeploymentPatternsController` through `PUT /settings/deployment-patterns`, read
 * by [CliGitPush.push] at each push. Nothing outside the database holds it: no property, file or environment variable,
 * and it never goes into a Smith's sandbox.
 */
@Repository
class DeploymentPatterns(private val jdbc: JdbcClient, private val secrets: SecretsKey) : DeploymentPatternSource {
    companion object {
        const val PATTERNS_AAD = "engine.deployment.patterns"
        const val ALLOW_AAD = "engine.deployment.allow"
        const val EVENT_NAME = "engine.deployment-patterns"
    }

    fun get(): DeploymentPatternsView =
        jdbc.sql("SELECT patterns_set_at, allow_set_at FROM engine.deployment_patterns")
            .query { rs, _ -> DeploymentPatternsView(rs.getObject(1, OffsetDateTime::class.java), rs.getObject(2, OffsetDateTime::class.java)) }
            .optional().orElse(DeploymentPatternsView(null, null))

    /** The rules opened, or null when no pattern is set (ALLOW lines alone check nothing). */
    override fun rules(): DeploymentRules? =
        jdbc.sql("SELECT patterns, allow FROM engine.deployment_patterns")
            .query { rs, _ -> rs.getBytes(1) to rs.getBytes(2) }.optional().orElse(null)
            ?.let { (p, a) -> p?.let { DeploymentRules(secrets.open(it, PATTERNS_AAD), a?.let { s -> secrets.open(s, ALLOW_AAD) } ?: "") } }

    /** Sets both: a null or blank value clears it (its key id and set time with it). */
    fun set(patterns: String?, allow: String?) {
        jdbc.sql("INSERT INTO engine.deployment_patterns (singleton) SELECT TRUE WHERE NOT EXISTS (SELECT 1 FROM engine.deployment_patterns)").update()
        column("patterns", patterns, PATTERNS_AAD)
        column("allow", allow, ALLOW_AAD)
    }

    private fun column(name: String, value: String?, aad: String) {
        if (value.isNullOrBlank()) jdbc.sql("UPDATE engine.deployment_patterns SET $name = NULL, ${name}_key_id = NULL, ${name}_set_at = NULL").update()
        else jdbc.sql("UPDATE engine.deployment_patterns SET $name = :v, ${name}_key_id = :k, ${name}_set_at = CURRENT_TIMESTAMP")
            .param("v", secrets.seal(value, aad)).param("k", secrets.id).update()
    }
}

/** Where a pattern matched an added line: never the line or the pattern. */
data class DeploymentHit(val commit: String, val path: String, val line: Int) {
    override fun toString() = "$path:$line (commit ${commit.take(12)})"
}

/**
 * The check of `scripts/check-deployment-values.sh`, on `git log -p -U0` output: every added line of every commit,
 * against each pattern (blank lines and `#` comments skipped), a line equal to an ALLOW line passing.
 */
object DeploymentValueScan {
    /** The `--format` of the log [scan] reads: a NUL then the commit. */
    const val FORMAT = "%x00commit %H"

    fun lines(text: String): List<String> = text.lines().map { it.trimEnd('\r') }.filter { it.isNotBlank() && !it.trimStart().startsWith("#") }

    fun scan(log: String, rules: DeploymentRules): List<DeploymentHit> {
        val patterns = lines(rules.patterns).map { Regex(it) }
        if (patterns.isEmpty()) return emptyList()
        val allow = rules.allow.lines().map { it.trimEnd('\r') }.filter { it.isNotEmpty() }.toSet()
        val hits = mutableListOf<DeploymentHit>()
        var commit = ""; var path: String? = null; var line = 0
        for (raw in log.lines()) {
            when {
                raw.startsWith("\u0000commit ") -> { commit = raw.removePrefix("\u0000commit ").trim(); path = null }
                raw.startsWith("+++ ") -> path = raw.removePrefix("+++ ").let { if (it == "/dev/null") null else it.removePrefix("b/") }
                raw.startsWith("@@ ") -> line = Regex("""\+(\d+)""").find(raw.substringAfter("@@ ").substringBefore(" @@"))?.groupValues?.get(1)?.toInt() ?: 0
                raw.startsWith("+") && path != null -> {
                    val added = raw.substring(1)
                    if (added !in allow && patterns.any { it.containsMatchIn(added) }) hits += DeploymentHit(commit, path!!, line)
                    line++
                }
            }
        }
        return hits
    }
}
