package com.codefrak.engine.smith

import com.codefrak.engine.engineJson
import tools.jackson.module.kotlin.readValue
import java.security.MessageDigest
import java.time.Instant

/**
 * One link of a HITLP 0.2 lineage (codefrak-oss/hitlp spec/hitlp.md §5.3; engine/README.md, "Lineage"): [kind] is
 * `ticket`, `intake`, `session`, `proposal` or `command`; [claim] the one sentence at that level; [source] where it is
 * recorded (`{repo, number, bodyRev}` for a ticket, `{intake, filedBy}` for an intake, `{login}` or `{run, session}`
 * for a session); [by] who wrote it.
 */
data class LineageLink(val kind: String, val claim: String, val source: Map<String, Any?>, val by: String)

/**
 * The chain of intent a Smith run or a first-mate session works under, captured once at its start ([asOf]) and handed
 * to the agent to pass through unchanged. At most [Lineage.MAX] links; [truncated] says the chain went on past them.
 */
data class Lineage(val asOf: String, val links: List<LineageLink>, val truncated: Boolean = false) {
    fun toBytes(): ByteArray = engineJson.writeValueAsBytes(this)

    companion object {
        /** The most links a lineage carries; a longer chain is cut and says so ([truncated]). */
        const val MAX = 8
        /** An intake whose filer the engine never recorded: left explicit, never invented. */
        const val UNKNOWN = "unknown"

        fun of(bytes: ByteArray): Lineage = engineJson.readValue(bytes)

        /** `sha256:<hex>` of the ticket body as captured: diff a later amendment against the body that hashes to it. */
        fun bodyRev(body: String): String =
            "sha256:" + MessageDigest.getInstance("SHA-256").digest(body.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

        /** The login a ticket's engine trailer names (`<!-- engine: … · by <login> · … -->`), or null. */
        fun trailerBy(body: String): String? = TRAILER_BY.find(body)?.groupValues?.get(1)

        /** A ticket's claim: the first sentence of its `## Goal`, or its title when it has none. */
        fun claim(ticket: Ticket): String {
            val goal = GOAL.find(ticket.body.replace("\r\n", "\n"))?.groupValues?.get(1)?.trim()?.takeIf { it.isNotEmpty() }
                ?: return ticket.title
            return sentence(goal)
        }

        /** The first sentence of [text] (to its first `. ` or line end), at most [CLAIM_CHARS] characters. */
        fun sentence(text: String): String {
            val line = text.trim().lineSequence().first().trim()
            val end = Regex("""(?<=[.!?])\s""").find(line)?.range?.first ?: line.length
            val s = line.substring(0, end).trim()
            return if (s.length > CLAIM_CHARS) s.take(CLAIM_CHARS) + "…" else s
        }

        /**
         * The ticket's link. [filedByIntake]: the engine's own record says an intake filed it, so its trailer is the
         * engine's and its `by` is trusted; on a hand-filed ticket the trailer is anyone's HTML comment, so the author.
         */
        fun ticketLink(repo: String, ticket: Ticket, filedByIntake: Boolean): LineageLink = LineageLink(
            "ticket", claim(ticket), linkedMapOf("repo" to repo, "number" to ticket.number, "bodyRev" to bodyRev(ticket.body)),
            (if (filedByIntake) trailerBy(ticket.body) else null) ?: ticket.author)

        /**
         * The lineage of a run on [ticket] in [repo]: the ticket, its intake, the ticket whose run filed that intake, its
         * intake, and so on up to the login that first asked, as [sources] records them. A link it cannot find ends the
         * chain; an intake whose filer is not recorded says `unknown`.
         */
        fun build(repo: String, ticket: Ticket, sources: LineageSources, now: Instant = Instant.now()): Lineage {
            val links = mutableListOf<LineageLink>()
            val seen = mutableSetOf<String>()
            var at: Pair<String, Ticket>? = repo to ticket
            while (at != null && links.size <= MAX) {
                val (r, t) = at
                val origin = runCatching { sources.intakeOf(r, t.number) }.getOrNull()
                links += ticketLink(r, t, origin != null)
                if (origin == null) break
                if (!seen.add(origin.intake)) break
                val filedBy: Any = when {
                    origin.run != null -> linkedMapOf("run" to origin.run, "repo" to origin.runRepo, "number" to origin.runIssue)
                    origin.submittedBy != null -> origin.submittedBy
                    else -> UNKNOWN
                }
                links += LineageLink("intake", origin.ask?.let { sentence(it) }.orEmpty(),
                    linkedMapOf("intake" to origin.intake, "filedBy" to filedBy),
                    origin.submittedBy ?: origin.run?.let { "smith:$it" } ?: UNKNOWN)
                at = origin.run?.let { run -> runCatching { sources.ticketOfRun(run) }.getOrNull()?.let { (origin.runRepo ?: r) to it } }
            }
            return Lineage(now.toString(), links.take(MAX), links.size > MAX)
        }

        /** A first-mate session's lineage: one `session` link, the chat's [ask] by [login] ([session] on the phone). */
        fun session(login: String, ask: String, session: String? = null, now: Instant = Instant.now()): Lineage {
            val source = linkedMapOf<String, Any?>("login" to login).apply { if (session != null) put("session", session) }
            return Lineage(now.toString(), listOf(LineageLink("session", sentence(ask).ifEmpty { "(no ask yet)" }, source, login)))
        }

        private const val CLAIM_CHARS = 500
        private val GOAL = Regex("""(?m)^##\s+Goal\s*\n+([\s\S]*?)(?=\n##\s|\z)""")
        private val TRAILER_BY = Regex("""<!--\s*engine:[^>]*?·\s*by\s+([A-Za-z0-9-]+)""")
    }
}

/** The intake that filed a ticket, as the engine recorded it: its ask, and the login or Smith run (with its ticket) that submitted it. */
data class IntakeOrigin(val intake: String, val ask: String?, val submittedBy: String?, val run: String? = null,
                        val runRepo: String? = null, val runIssue: Long? = null)

/** Where [Lineage.build] reads the links above a ticket: the intake flow's records (`IntakeLookups`). */
interface LineageSources {
    /** The intake that filed ticket [number] in [repo] (or whose issue it is), or null for a hand-filed issue. */
    fun intakeOf(repo: String, number: Long): IntakeOrigin? = null

    /** The ticket Smith run [run] worked, as its flow captured it, or null when it is not found. */
    fun ticketOfRun(run: String): Ticket? = null
}
