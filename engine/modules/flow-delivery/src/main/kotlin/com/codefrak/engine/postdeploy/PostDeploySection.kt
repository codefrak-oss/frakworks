package com.codefrak.engine.postdeploy

import com.codefrak.engine.engineJson
import tools.jackson.module.kotlin.readValue
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.format.DateTimeParseException

/**
 * A PR's `## POST-DEPLOYMENT-CHECK` section (bots/smith.md, "The post-deployment check"), read from the work
 * Smith's summary, which is the PR body: [what] to verify once the change is in production (prose), the
 * [recheck] interval the section asks for between `not_yet` runs (its `Re-check:` line), and the deadline after
 * which the check stops: [deadlineAfter] from the merge (`Deadline: 2d`) or [deadlineAt] (`Deadline: 2026-11-01`).
 * At most one of the two deadline fields is set.
 */
data class PostDeployCheck(val what: String, val recheck: Duration? = null, val deadlineAfter: Duration? = null, val deadlineAt: Instant? = null) {
    /** The section as a process variable (JSON bytes): durations as ISO-8601 (`PT4H`), the instant as ISO-8601. */
    fun toBytes(): ByteArray = engineJson.writeValueAsBytes(toMap())

    /** The brief's shape: `what`, `recheck`, `deadlineAfter`, `deadlineAt`, each duration ISO-8601, null when unset. */
    fun toMap(): Map<String, Any?> = mapOf("what" to what, "recheck" to recheck?.toString(), "deadlineAfter" to deadlineAfter?.toString(), "deadlineAt" to deadlineAt?.toString())

    companion object {
        /** The section back from [toBytes]; null for null bytes. */
        fun of(bytes: ByteArray?): PostDeployCheck? = bytes?.let { of(engineJson.readValue<Map<String, Any?>>(it)) }

        fun of(map: Map<String, Any?>): PostDeployCheck = PostDeployCheck(
            map["what"] as String,
            (map["recheck"] as? String)?.let { Duration.parse(it) },
            (map["deadlineAfter"] as? String)?.let { Duration.parse(it) },
            (map["deadlineAt"] as? String)?.let { Instant.parse(it) },
        )
    }
}

/** A section that cannot be read: it names the PR and what is wrong, and the merge is refused on it (part C). */
class PostDeploySectionError(message: String) : IllegalArgumentException(message)

/**
 * The grammar of the section. The heading is `POST-DEPLOYMENT-CHECK` at level 2 or 3, in any case; the section runs
 * to the next heading of the same level or the end of the body. Inside it, a line starting `Re-check:` or
 * `Deadline:` (any case) is a key; every other non-blank line is the prose `what`. A duration is `<n>m`, `<n>h`,
 * `<n>d` or `<n>w`; a deadline may also be an ISO date (`2026-11-01`, read as midnight UTC) or instant.
 */
object PostDeploySection {
    const val HEADING = "POST-DEPLOYMENT-CHECK"
    private val HEADING_LINE = Regex("""^(#{2,3})\s*$HEADING\s*#*\s*$""", setOf(RegexOption.IGNORE_CASE, RegexOption.MULTILINE))
    private val KEY_LINE = Regex("""^(re-check|deadline):\s*(.*)$""", RegexOption.IGNORE_CASE)
    private val DURATION = Regex("""^(\d+)\s*([mhdw])$""", RegexOption.IGNORE_CASE)

    /**
     * The section of [prBody], for PR [pr] (named in the error); null when the body has none. Throws
     * [PostDeploySectionError] for a section with no prose, a key given twice, or a `Re-check:` or `Deadline:` value
     * that is neither a duration nor (for the deadline) an ISO date.
     */
    fun parse(prBody: String?, pr: String = "the PR"): PostDeployCheck? {
        if (prBody.isNullOrBlank()) return null
        val heading = HEADING_LINE.find(prBody) ?: return null
        val level = heading.groupValues[1].length
        val after = prBody.substring(heading.range.last + 1)
        val sameLevel = Regex("""^#{1,$level}\s""", RegexOption.MULTILINE)
        val body = sameLevel.find(after)?.let { after.substring(0, it.range.first) } ?: after
        var recheck: Duration? = null
        var deadlineAfter: Duration? = null
        var deadlineAt: Instant? = null
        var seenRecheck = false
        var seenDeadline = false
        val prose = mutableListOf<String>()
        for (raw in body.lines()) {
            val line = raw.trim()
            val key = KEY_LINE.find(line)
            if (key == null) { if (line.isNotEmpty()) prose += line; continue }
            val value = key.groupValues[2].trim()
            when (key.groupValues[1].lowercase()) {
                "re-check" -> {
                    if (seenRecheck) throw PostDeploySectionError("$pr: the $HEADING section has two Re-check: lines")
                    seenRecheck = true
                    recheck = duration(value) ?: throw PostDeploySectionError("$pr: the $HEADING section's Re-check: '$value' is not a duration (30m, 4h, 2d, 1w)")
                }
                else -> {
                    if (seenDeadline) throw PostDeploySectionError("$pr: the $HEADING section has two Deadline: lines")
                    seenDeadline = true
                    val d = duration(value)
                    if (d != null) deadlineAfter = d
                    else deadlineAt = instant(value) ?: throw PostDeploySectionError("$pr: the $HEADING section's Deadline: '$value' is neither a duration (30m, 4h, 2d, 1w) nor an ISO date")
                }
            }
        }
        if (prose.isEmpty()) throw PostDeploySectionError("$pr: the $HEADING section says nothing to check: it needs prose besides its Re-check: and Deadline: lines")
        return PostDeployCheck(prose.joinToString("\n"), recheck, deadlineAfter, deadlineAt)
    }

    /** `30m`, `4h`, `2d`, `1w` as a [Duration]; null for anything else. */
    fun duration(text: String): Duration? {
        val m = DURATION.find(text.trim()) ?: return null
        val n = m.groupValues[1].toLongOrNull() ?: return null
        return when (m.groupValues[2].lowercase()) {
            "m" -> Duration.ofMinutes(n)
            "h" -> Duration.ofHours(n)
            "d" -> Duration.ofDays(n)
            else -> Duration.ofDays(7 * n)
        }
    }

    /** An ISO instant (`2026-11-01T09:00:00Z`) or date (`2026-11-01`, midnight UTC); null for anything else. */
    fun instant(text: String): Instant? {
        val t = text.trim()
        return try { Instant.parse(t) } catch (_: DateTimeParseException) {
            try { LocalDate.parse(t).atStartOfDay(ZoneOffset.UTC).toInstant() } catch (_: DateTimeParseException) { null }
        }
    }
}
