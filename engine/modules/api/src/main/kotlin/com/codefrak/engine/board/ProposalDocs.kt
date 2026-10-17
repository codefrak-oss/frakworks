package com.codefrak.engine.board

import com.codefrak.engine.github.RepoContents
import org.springframework.stereotype.Component
import java.util.concurrent.ConcurrentHashMap

/**
 * One doc under `docs/proposals/` on a repo's default branch, as the desktop's Proposals branch lists it:
 * its `Status:` (one of [ProposalDocs.STATUSES], or null when the doc says none of them), its `Decision log:`
 * items in order, the proposal ticket its `Ticket:` line names, the build ticket its log says was filed
 * (`build filed as #n`, what `task-log-decision` writes) or its `Builds:` line names, and the sibling pitch page.
 */
data class ProposalDoc(
    val repo: String, val slug: String, val path: String, val title: String, val status: String?,
    val decisionLog: List<String>, val ticket: Long?, val buildTicket: Long?, val pitch: String?,
)

/** What `GET /proposals` answers: every doc found, and the repos whose read failed (in All repos only). */
data class ProposalListing(val docs: List<ProposalDoc>, val errors: List<Map<String, String>>)

/**
 * Lists and parses each target repo's `.md` docs under `docs/proposals/` on its default branch. One GraphQL call lists
 * the directory with each blob's oid; a doc whose oid was parsed before is not read again, so a refresh
 * with no doc changed costs that one call, and one more (every changed doc in it) when some did.
 */
@Component
class ProposalDocs(private val contents: RepoContents) {
    /** The parts of a doc that do not depend on where it is. */
    data class Parsed(val title: String, val status: String?, val decisionLog: List<String>, val ticket: Long?, val buildTicket: Long?)

    companion object {
        const val DIR = "docs/proposals"
        val STATUSES = listOf("proposed", "accepted", "shelved", "rejected", "built")
        /** A slug safe to put in a contents API path as it is, as [Pitches] checks a path. */
        val SLUG = Regex("""^[A-Za-z0-9_-][A-Za-z0-9._-]*$""")
        private val STATUS = Regex("""(?:^|\s)Status:\s*([A-Za-z]+)""", RegexOption.IGNORE_CASE)
        private val LOG_HEADER = Regex("""^\s*Decision log:\s*$""", RegexOption.IGNORE_CASE)
        private val LIST_ITEM = Regex("""^\s*[-*+]\s+(.*)$""")
        private val TICKET = Regex("""^\s*Ticket:\s*#(\d+)""", RegexOption.IGNORE_CASE)
        private val BUILDS = Regex("""^\s*Builds:\s*#(\d+)""", RegexOption.IGNORE_CASE)
        private val BUILT = Regex("""build filed as #(\d+)""", RegexOption.IGNORE_CASE)

        /**
         * Parses the doc's head, the lines above its first `## ` heading or code fence, so a template in its
         * body (proposal-flow.md's) is not read as its own. Bold labels (`**Status:**`) read as plain ones. A
         * log item runs on over the indented lines under it.
         */
        fun parse(text: String, fallbackTitle: String): Parsed {
            val lines = text.lines()
            val title = lines.firstOrNull { it.startsWith("# ") }?.removePrefix("# ")?.trim()?.ifBlank { null } ?: fallbackTitle
            val head = lines.takeWhile { !it.startsWith("## ") && !it.trimStart().startsWith("```") }.map { it.replace("**", "") }
            val status = head.firstNotNullOfOrNull { STATUS.find(it) }?.groupValues?.get(1)?.lowercase()?.takeIf { it in STATUSES }
            val log = mutableListOf<String>()
            val at = head.indexOfFirst { LOG_HEADER.matches(it) }
            if (at >= 0) {
                for (line in head.drop(at + 1)) {
                    val item = LIST_ITEM.matchEntire(line)
                    when {
                        item != null -> log += item.groupValues[1].trim()
                        log.isNotEmpty() && line.isNotBlank() && line.first().isWhitespace() -> log[log.size - 1] = log.last() + " " + line.trim()
                        else -> break
                    }
                }
            }
            val ticket = head.firstNotNullOfOrNull { TICKET.find(it) }?.groupValues?.get(1)?.toLong()
            val build = log.mapNotNull { BUILT.find(it)?.groupValues?.get(1)?.toLong() }.lastOrNull()
                ?: head.firstNotNullOfOrNull { BUILDS.find(it) }?.groupValues?.get(1)?.toLong()
            return Parsed(title, status, log, ticket, build)
        }
    }

    /** Per repo, the parsed doc by blob oid; replaced on each listing, so it holds only the docs there now. */
    private val cache = ConcurrentHashMap<String, Map<String, Parsed>>()

    /** `repo`'s docs, sorted by slug. Throws when GitHub fails. */
    fun list(repo: String): List<ProposalDoc> {
        val entries = contents.treeEntries(repo, DIR)
        val names = entries.map { it.name }.toSet()
        val docs = entries.filter { it.name.endsWith(".md") && SLUG.matches(it.name.removeSuffix(".md")) }
        val known = cache[repo].orEmpty()
        val missing = docs.map { it.oid }.filter { it !in known }
        val texts = if (missing.isEmpty()) emptyMap() else contents.blobTexts(repo, missing)
        // A blob GitHub did not send is not kept, so the next listing asks for it again.
        val parsed = docs.mapNotNull { e ->
            (known[e.oid] ?: texts[e.oid]?.let { parse(it, e.name.removeSuffix(".md")) })?.let { e.oid to it }
        }.toMap()
        cache[repo] = parsed
        return docs.map { e ->
            val slug = e.name.removeSuffix(".md")
            val p = parsed[e.oid] ?: Parsed(slug, null, emptyList(), null, null)
            ProposalDoc(repo, slug, "$DIR/${e.name}", p.title, p.status, p.decisionLog, p.ticket, p.buildTicket,
                "$slug.html".takeIf { it in names }?.let { "$DIR/$it" })
        }.sortedBy { it.slug }
    }
}
