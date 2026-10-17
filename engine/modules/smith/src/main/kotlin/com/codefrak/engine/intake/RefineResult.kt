package com.codefrak.engine.intake

import com.codefrak.engine.engineJson
import com.codefrak.engine.feature.FeatureMode
import com.codefrak.engine.github.TokenScopes
import com.codefrak.engine.smith.AllowedScope
import com.codefrak.engine.smith.PassAlong
import com.codefrak.engine.smith.Vars
import org.operaton.bpm.engine.delegate.DelegateExecution
import tools.jackson.module.kotlin.readValue

/**
 * `points`: the ticket's story-point estimate (bots/story-points.md), one of [RefineResult.POINTS]; required to file,
 * recorded in the trailer and `engine.handled_issue.points`, and never read to schedule or pick a tier.
 */
data class DraftTicket(val title: String, val body: String, val tier: Int, val priority: String, val points: Int? = null)

/**
 * What a refining Smith writes to `/work/out/result.json` (smith/run-smith.sh,
 * refine mode). The engine does every GitHub write from it; the Smith does none.
 */
data class RefineResult(
    val outcome: String,
    val ticket: DraftTicket? = null,
    @PassAlong val reason: String? = null,
    /** For `insufficient-permissions`: the blocking challenge in 20 words or fewer. Optional (results before #443 have none); the timeline derives one from [reason]. */
    val blocker: String? = null,
    @PassAlong val question: String? = null,
    /** What the work is; filed as the ticket's kind label. A `chore` from an authorized intake starts at once. */
    val kind: String? = null,
    /** For a feature: `auto` or `confirm` (`FeatureMode`), filed as a second label; required, since the flow needs one. */
    val featureMode: String? = null,
    /**
     * The fields the user named in the ask or its answers, of `kind`, `featureMode`, `tier` and `points`: they bind, and Jev
     * is not asked about them ([RefinePicker]). Optional: null or empty leaves every field to Jev's pick.
     */
    val userNamed: List<String>? = null,
    /**
     * What the filed ticket waits on, each `#n` (an issue in the intake's repo, or an earlier intake's ask item) or an
     * earlier intake's id. The engine resolves an intake to the ticket it filed and records GitHub's "blocked by"
     * relation (engine/README.md, "Blocked by", "An earlier intake"). Optional: null or empty records none.
     */
    val blockedBy: List<String>? = null,
    /**
     * For `file` and `insufficient-permissions`: the target repo (`owner/name`) the ticket belongs in, when that is not the
     * intake's own. Null (or the intake's repo) rewrites the ask item in place; another registered target repo gets
     * a new work item there, and the ask item is closed with a link to it. One that is not a target repo is refused
     * ([RefineResultHandler]).
     */
    val repo: String? = null,
) {
    /**
     * Every rule the engine enforces before acting; empty when the result can be acted on. [scopes] are the
     * repo's token's classic scopes (`TokenIdentity.scopes`), null when unknown: a `file` whose Allowed scope
     * reaches `.github/workflows/` is refused only when they are known and lack `workflow`.
     */
    fun problems(scopes: Set<String>? = null): List<String> = buildList {
        if (outcome !in OUTCOMES) add("outcome '$outcome' is not one of $OUTCOMES")
        if (outcome in setOf("file", "insufficient-permissions")) {
            if (kind !in KINDS) add("kind '$kind' is not one of $KINDS")
            if (kind == "feature" && featureMode !in FeatureMode.ALL) add("kind 'feature' needs featureMode, one of ${FeatureMode.ALL}")
            if (ticket == null) add("outcome '$outcome' needs a ticket")
            ticket?.let {
                if (it.title.isBlank()) add("ticket.title is blank")
                if (it.body.isBlank()) add("ticket.body is blank")
                if (it.tier !in 1..10) add("ticket.tier ${it.tier} is not 1-10")
                if (outcome == "file" && it.points !in POINTS) add("ticket.points ${it.points} is not one of $POINTS (bots/story-points.md)")
                else if (it.points != null && it.points > MAX_POINTS_UNSPLIT && kind != "feature")
                    add("ticket.points ${it.points} is above $MAX_POINTS_UNSPLIT, which only a feature takes: file it as a feature (bots/story-points.md)")
                if (it.priority !in PRIORITIES) add("ticket.priority '${it.priority}' is not one of $PRIORITIES (the intake cap)")
                if (AllowedScope.grantsPushToMain(it.body)) add("ticket allows pushing to main")
                if (outcome == "file" && scopes != null && TokenScopes.WORKFLOW !in scopes && AllowedScope.allowsWorkflows(it.body))
                    add("ticket's Allowed scope includes .github/workflows/, but the repo's token lacks the '${TokenScopes.WORKFLOW}' scope " +
                        "(it has ${scopes.sorted().ifEmpty { listOf("none") }.joinToString(", ")}): the push would fail; return 'insufficient-permissions' " +
                        "with a blocker naming '${TokenScopes.WORKFLOW}'")
            }
        }
        if (repo != null && !REPO.matches(repo.trim())) add("repo '$repo' is not owner/name")
        if (repo != null && outcome == "ask") add("repo is only for outcome 'file' or 'insufficient-permissions'")
        blockedBy.orEmpty().filter { !BLOCKED_BY.matches(it.trim()) }
            .forEach { add("blockedBy '$it' is neither '#n' nor an intake id") }
        userNamed.orEmpty().filter { it !in NAMEABLE }.forEach { add("userNamed '$it' is not one of $NAMEABLE") }
        if (outcome == "insufficient-permissions" && reason.isNullOrBlank()) add("outcome 'insufficient-permissions' needs a reason")
        if (outcome == "ask" && question.isNullOrBlank()) add("outcome 'ask' needs a question")
        if (outcome == "withdrawn") {
            if (reason.isNullOrBlank()) add("outcome 'withdrawn' needs a reason")
            if (ticket != null) add("outcome 'withdrawn' files nothing: drop the ticket")
            if (kind != null) add("outcome 'withdrawn' files nothing: drop the kind")
            if (repo != null) add("outcome 'withdrawn' files nothing: drop the repo")
            if (!blockedBy.isNullOrEmpty()) add("outcome 'withdrawn' files nothing: drop the blockedBy")
        }
    }

    companion object {
        val OUTCOMES = setOf("file", "insufficient-permissions", "ask", "withdrawn")
        /** The kinds a refiner may file; chore, bug and feature have flows. */
        val KINDS = setOf("feature", "bug", "chore", "proposal", "research", "incident")
        val PRIORITIES = setOf("p2", "p3", "p4", "p5")
        /** The fields a user can name in an ask, which Jev then leaves alone. */
        val NAMEABLE = setOf("kind", "featureMode", "tier", "points")
        /** The story-point scale (bots/story-points.md). */
        val POINTS = setOf(1, 2, 3, 5, 8, 13, 21, 34, 55)
        /** The most points a ticket that is not a feature takes: a feature's design step splits it, nothing else's does. */
        const val MAX_POINTS_UNSPLIT = 13
        /** A `blockedBy` entry: `#n`, or an intake id (a process instance id). */
        private val BLOCKED_BY = Regex("""#\d+|[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}""")

        /** A `repo`: `owner/name`. */
        private val REPO = Regex("""[A-Za-z0-9._-]+/[A-Za-z0-9._-]+""")

        /**
         * The problem with [repo] given the engine's [targets] (`TargetRepos`' full names), or null: a repo not among them is
         * refused, naming them (there is no default repo).
         */
        fun repoProblem(repo: String?, targets: List<String>): String? =
            repo?.trim()?.takeIf { r -> targets.none { it.equals(r, ignoreCase = true) } }
                ?.let { "repo '$it' is not a target repo; the target repos are ${targets.sorted().joinToString(", ").ifEmpty { "none" }}" }

        fun parse(bytes: ByteArray): RefineResult = engineJson.readValue(bytes)

        /** The run's result, from the `output` variable `collect result` kept. */
        fun of(execution: DelegateExecution): RefineResult {
            val bytes = execution.getVariable(Vars.OUTPUT) as? ByteArray
                ?: error("the smith wrote no ${com.codefrak.engine.smith.OUTPUT_PATH}; see its log")
            return parse(bytes)
        }
    }
}
