package com.codefrak.engine.github

import java.time.OffsetDateTime

/** The deployment patterns and ALLOW lines `CliGitPush.push` checks a Smith's commits against, opened; null when none is set. */
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
 * The deployment-patterns setting (#100115; engine/README.md, "Deployment patterns"): the patterns and the ALLOW lines,
 * each sealed under the engine's key. Set by `DeploymentPatternsController` through `PUT /settings/deployment-patterns`,
 * read by `CliGitPush.push` at each push. Nothing outside the database holds it: no property, file or environment
 * variable, and it never goes into a Smith's sandbox. The engine's is `StoredDeploymentPatterns` (engine-github), the one
 * row of `engine.deployment_patterns` (V102).
 */
interface DeploymentPatterns : DeploymentPatternSource {
    companion object {
        const val EVENT_NAME = "engine.deployment-patterns"
    }

    fun get(): DeploymentPatternsView

    /** The rules opened, or null when no pattern is set (ALLOW lines alone check nothing). */
    override fun rules(): DeploymentRules?

    /** Sets both: a null or blank value clears it (its key id and set time with it). */
    fun set(patterns: String?, allow: String?)
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
