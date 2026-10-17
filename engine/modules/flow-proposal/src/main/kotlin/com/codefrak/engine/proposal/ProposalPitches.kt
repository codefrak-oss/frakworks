package com.codefrak.engine.proposal

import com.codefrak.engine.github.IssueDetails
import com.codefrak.engine.github.PullFiles
import org.springframework.stereotype.Component

/**
 * A proposal ticket's pitch page (`docs/proposals/<slug>.html`, engine/README.md, "Proposal") found from
 * GitHub alone, for `GET /ticket/{owner}/{repo}/{n}/pitch` when the engine kept no record of the proposal
 * ([com.codefrak.engine.board.Pitches] finds none): the one page under docs/proposals/ the ticket's closing
 * PR adds or changes. The page is read from the PR's head branch while the PR is not merged, and from the
 * default branch once it is, or when the branch no longer has it.
 */
@Component
class ProposalPitches(private val details: IssueDetails) {
    /** A pitch page found through a PR: its path, the PR's URL and the page as committed. */
    data class Page(val path: String, val prUrl: String, val html: String)

    /** The page, or null when no closing PR decides one (not a proposal, a draft from before pitches, no such issue). */
    fun page(repo: String, issue: Long): Page? {
        val pulls = details.ticketPulls(repo, issue) ?: return null
        val pull = pick(pulls.pulls) ?: return null
        val path = pitchIn(pull) ?: return null
        val refs = if (pull.state == "MERGED") listOf(pulls.defaultBranch) else listOf(pull.headRefName, pulls.defaultBranch)
        val html = refs.distinct().firstNotNullOfOrNull { details.fileText(repo, path, it) } ?: return null
        return Page(path, pull.url, html)
    }

    companion object {
        /** A page directly in docs/proposals/ with a plain slug: safe to put in a URI as it is. */
        private val SAFE = Regex("""^docs/proposals/[A-Za-z0-9][A-Za-z0-9._-]*\.html$""")

        /** Open first, then merged, then closed; newest first within each. */
        private val ORDER = compareBy<PullFiles> { listOf("OPEN", "MERGED").indexOf(it.state).let { i -> if (i < 0) 2 else i } }
            .thenByDescending { it.number }

        /** The PR whose pitch to show: the first, by [ORDER], that has one. */
        fun pick(pulls: List<PullFiles>): PullFiles? = pulls.filter { pitchIn(it) != null }.sortedWith(ORDER).firstOrNull()

        /**
         * The pitch a PR adds or changes: its one page under docs/proposals/; with several, the one whose
         * `.md` it changes too, when that is one; else null, since the file list cannot tell.
         */
        fun pitchIn(pull: PullFiles): String? {
            val pages = pull.files.filter { (path, change) -> SAFE.matches(path) && change != "DELETED" }.keys
            pages.singleOrNull()?.let { return it }
            return pages.filter { it.removeSuffix(".html") + ".md" in pull.files }.singleOrNull()
        }
    }
}
