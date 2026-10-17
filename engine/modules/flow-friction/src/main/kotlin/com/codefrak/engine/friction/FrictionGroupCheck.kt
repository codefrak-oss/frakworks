package com.codefrak.engine.friction

import com.codefrak.engine.intake.DuplicateCheck
import com.codefrak.engine.jev.JevAnswer
import com.codefrak.engine.jev.JevQuestion

/**
 * Jev's look at a friction note with a new key (use case `friction-group`; engine/README.md, "Friction notes"):
 * one `choice` whose options are every existing group, newest
 * member first, as `g<id>` with its title cut to [TITLE_CHARS], plus `new`. The answer's probabilities are read over
 * [clusters] of near-duplicate groups: the winning cluster's summed probability [Verdict.beatsNew] `new`'s by the ratio
 * `engine.jev.friction-group-beats-new` and the note joins the cluster's oldest group; otherwise it founds a group. No
 * answer leaves the note for the grouper's retry. Past [DuplicateCheck.BATCH] groups the choice runs in `DuplicateCheck`'s stages ([DuplicateCheck.stages]).
 * The call's `outcome` is written later by what a user does with the group ([FrictionCorrections]: [pulledOut],
 * [merged], [kept]) or by the 30-day sweep ([FrictionGrouper.sweep]: [kept], [newKept]), which [truth] reads for
 * `GET /jev/summary`.
 */
object FrictionGroupCheck {
    const val USE_CASE = "friction-group"
    const val QUESTION = "group"
    const val NEW = "new"

    /**
     * The second question (engine/README.md, "Friction notes", "Two things"): a `noul` that rides on the call whose pick
     * counts, or alone as use case [TWO_THINGS_USE_CASE] for a note that makes no group call (an exact join, the
     * first founder). At `engine.jev.friction-two-things` or above the note is marked ([FrictionBugs]).
     */
    const val TWO_THINGS = "two-things"
    const val TWO_THINGS_USE_CASE = "friction-two-things"

    fun twoThingsQuestion(): JevQuestion = JevQuestion.noul(
        "Does this note describe two different things, each of which a different change would spare? One thing with " +
            "two symptoms, or one thing and its workaround, is no.")

    /** The outcome prefix the two-things outcomes share, on a `friction-group` call or a lone `friction-two-things` one. */
    const val TWO_THINGS_OUTCOME = "two things: "

    /** A mark still standing 30 days on (the sweep, on a lone call). */
    fun twoThingsKept() = "${TWO_THINGS_OUTCOME}kept"

    /** A mark a user cleared: a miss. */
    fun twoThingsCleared(by: String) = "${TWO_THINGS_OUTCOME}cleared by @$by"

    /** What a mark adds to the call's `action`: `; two things (0.86)`. */
    fun twoThingsAction(value: Double) = "; two things (${DuplicateCheck.pct(value)})"

    /**
     * Whether a two-things outcome bore the `two-things` answer out: `kept` agrees with a `yes` (a `noul` at 0.5 or
     * above), `cleared` with a `no`. Null for an outcome that is not one of the two, or a call without the answer.
     */
    fun twoThingsVerdict(answers: Map<String, JevAnswer>?, outcome: String): Boolean? {
        if (!outcome.startsWith(TWO_THINGS_OUTCOME)) return null
        val yes = (answers?.get(TWO_THINGS)?.noul ?: return null) >= 0.5
        return when {
            outcome == twoThingsKept() -> yes
            outcome.startsWith("${TWO_THINGS_OUTCOME}cleared by ") -> !yes
            else -> null
        }
    }

    /** Characters of each option (a group's title) and of the note's evidence in the state. */
    const val TITLE_CHARS = 240
    const val EVIDENCE_CHARS = 2000

    /** The action for a note left ungrouped, and its stage-1 suffix. */
    const val LEFT_UNGROUPED = "left ungrouped"

    /** One group as an option: `g<id>`. */
    data class Candidate(val id: Long, val title: String) {
        val key get() = name(id)
    }

    /** A group's name in an action or an outcome. */
    fun name(id: Long) = "g$id"

    fun candidates(groups: List<GroupOption>): List<Candidate> = groups.map { Candidate(it.id, it.title.take(TITLE_CHARS)) }

    fun question(candidates: List<Candidate>): JevQuestion = JevQuestion.choice(
        "The state is a friction note a Smith or First mate left: something that slowed it down. Every option but `new` " +
            "is a group of earlier notes, by its title. Is this note the same thing as one of these groups? The same thing " +
            "means one change would have spared every note in the group and this one too; the same tool, file or area " +
            "with a different cause is `new`.",
        candidates.associate { it.key to it.title } + (NEW to "none of these: a different thing, or the same tool with a different cause"),
    )

    /** The note as Jev reads it: its kind, source, repo, summary and evidence cut to [EVIDENCE_CHARS]. */
    fun state(note: FrictionNote) = mapOf(
        "kind" to note.kind, "source" to note.source, "repo" to note.repo,
        "summary" to note.summary, "evidence" to note.evidence?.take(EVIDENCE_CHARS),
    )

    /**
     * Groups whose titles are the same problem: the Jaccard similarity of their [words] at [CLUSTER_SIMILARITY] or
     * above links two, and a cluster is everything linked, directly or through others (single link). A group near no
     * other is a cluster of one. Deterministic, in the engine, with no second question on the call.
     */
    const val CLUSTER_SIMILARITY = 0.5

    /** Words that carry no problem of their own, left out of [words]. */
    private val STOP = setOf("a", "an", "the", "is", "are", "be", "of", "in", "on", "to", "and", "or", "it", "its", "as", "at", "by", "for", "with", "from", "this", "that")

    /** A title's words for [similarity]: lower-case runs of letters, digits and `/._-`, without [STOP]. */
    fun words(title: String): Set<String> =
        Regex("""[a-z0-9][a-z0-9/._-]*""").findAll(title.lowercase()).map { it.value.trimEnd('.', '-', '_', '/') }.filter { it.isNotEmpty() && it !in STOP }.toSet()

    /** The Jaccard similarity of two titles' [words]; 0 when both are empty. */
    fun similarity(a: String, b: String): Double = jaccard(words(a), words(b))

    private fun jaccard(x: Set<String>, y: Set<String>): Double {
        val union = (x + y).size
        return if (union == 0) 0.0 else (x intersect y).size.toDouble() / union
    }

    /**
     * A cluster of near-duplicate groups: its [members], oldest (lowest id) first, and their summed [probability]. The
     * note joins [best], the oldest group: the one the problem was first filed under, and the one a user's merges keep
     * (the merge target is the older group), so one rule holds whether the cluster is big or small.
     */
    data class Cluster(val members: List<Candidate>, val probability: Double) {
        val best: Candidate get() = members.first()
    }

    /** [candidates] in clusters by [CLUSTER_SIMILARITY], each with its members' probabilities in [answer] summed. */
    fun clusters(candidates: List<Candidate>, answer: JevAnswer): List<Cluster> {
        val parent = candidates.indices.toMutableList()
        fun root(i: Int): Int { var r = i; while (parent[r] != r) r = parent[r]; return r }
        val words = candidates.map { words(it.title) }
        for (i in candidates.indices) for (j in i + 1 until candidates.size)
            if (jaccard(words[i], words[j]) >= CLUSTER_SIMILARITY) parent[root(j)] = root(i)
        return candidates.indices.groupBy(::root).values.map { ix ->
            val members = ix.map { candidates[it] }.sortedBy { it.id }
            Cluster(members, members.sumOf { probability(answer, it.key) })
        }
    }

    /** An option's probability: its entry, or the answer's confidence for its own choice when the map leaves it out. */
    fun probability(answer: JevAnswer, option: String): Double =
        answer.probabilities[option] ?: if (answer.choice == option) answer.confidence ?: 0.0 else 0.0

    /** How many members a cluster's action names before `+N`, so the action stays within 100 characters. */
    const val CLUSTER_NAMES = 4

    /**
     * What the check reached: the [call] whose pick counts, its [answer] (null for no answer), the group it picked
     * ([pick], null for `new`, an option that is no group, or no answer), the [finalists] the answer was over, whether
     * it was a stage-2 call, and whether the stages already [recorded] the call's action (a stage-1 no answer, or every
     * batch out).
     */
    data class Verdict(val call: String, val answer: JevAnswer?, val pick: Candidate?, val stage2: Boolean, val recorded: Boolean,
                       val finalists: List<Candidate> = emptyList()) {
        val noAnswer: Boolean get() = answer == null

        /** The cluster with the most probability, null with no answer or none above 0. */
        val winner: Cluster? by lazy {
            answer?.let { a -> clusters(finalists, a).filter { it.probability > 0.0 }.maxByOrNull { it.probability } }
        }

        /** `new`'s probability. */
        val newProbability: Double get() = answer?.let { probability(it, NEW) } ?: 0.0

        /**
         * The one rule: the [winner] clearly beats `new` when its summed probability is above `new`'s and at least
         * [ratio] times it (`engine.jev.friction-group-beats-new`).
         */
        fun beatsNew(ratio: Double): Boolean = winner?.let { it.probability > newProbability && it.probability >= ratio * newProbability } ?: false

        /** The group the note joins: the winning cluster's [Cluster.best], when it [beatsNew]. */
        fun joins(ratio: Double): Candidate? = winner?.takeIf { beatsNew(ratio) }?.best

        /** `g9 cluster{g2,g9,g27,g32}` for a cluster of several (at most [CLUSTER_NAMES] named, then `+N`), `g12` for one. */
        private fun named(c: Cluster): String =
            if (c.members.size == 1) c.best.key
            else c.best.key + " cluster{" + c.members.take(CLUSTER_NAMES).joinToString(",") { it.key } +
                (if (c.members.size > CLUSTER_NAMES) ",+${c.members.size - CLUSTER_NAMES}" else "") + "}"

        /**
         * The call's `action`, in the doctor's shape: `g12 (0.91 vs new 0.09): joined`, `g2 cluster{g2,g9,g27,g32}
         * (0.73 vs new 0.11): joined`, `g12 (0.64 vs new 0.36): new group g31` (a winner that does not clearly beat
         * `new`), `new (0.88): new group g31` (`new` on top, or nothing in the options), `no answer: left ungrouped` (or
         * `no answer: new group g31` when the note founded its own as `fallback` or `no-jev`); `stage 2` first on a
         * staged check's final call. [newGroup] is the group the note founded, if any. Null when the stages recorded the
         * action already.
         */
        fun action(ratio: Double, newGroup: Long?): String? {
            if (recorded) return null
            val founded = "new group ${newGroup?.let { name(it) } ?: "?"}"
            val w = winner
            val text = when {
                answer == null -> "no answer: ${if (newGroup == null) LEFT_UNGROUPED else founded}"
                w == null || w.probability <= newProbability -> "${answer.choice} (${DuplicateCheck.pct(answer.confidence)}): $founded"
                else -> "${named(w)} (${DuplicateCheck.pct(w.probability)} vs new ${DuplicateCheck.pct(newProbability)}): " +
                    if (beatsNew(ratio)) "joined" else founded
            }
            return (if (stage2) "stage 2 " else "") + text
        }
    }

    /**
     * Runs the check with `ask` making each call and `act` recording a stage-1 call's action ([DuplicateCheck.stages]).
     * [candidates] is non-empty: with no groups the note founds one without a call.
     */
    fun run(candidates: List<Candidate>, ask: (List<Candidate>, Boolean) -> DuplicateCheck.Asked, act: (String, String) -> Unit): Verdict {
        require(candidates.isNotEmpty()) { "no groups: nothing to ask" }
        return when (val staged = DuplicateCheck.stages(candidates, { it.key }, LEFT_UNGROUPED, ask, act)) {
            is DuplicateCheck.Staged.NoAnswer -> Verdict(staged.call, null, null, stage2 = false, recorded = true)
            is DuplicateCheck.Staged.AllOut -> Verdict(staged.call, JevAnswer("choice", choice = NEW), null, stage2 = false, recorded = true)
            is DuplicateCheck.Staged.Final -> {
                val answer = staged.asked.answer
                val pick = answer?.choice?.let { c -> staged.finalists.firstOrNull { it.key == c } }
                Verdict(staged.asked.call, answer, pick, staged.staged, recorded = false, staged.finalists)
            }
        }
    }

    /** A Jev join into [group] still intact 30 days on, or that another group was merged into. */
    fun kept(group: String) = "joined $group: kept"

    /** A Jev join into [from] a user pulled out, to the new group [to]: a miss. */
    fun pulledOut(from: String, to: String, by: String) = "joined $from: pulled out to $to by @$by"

    /** A `new` whose group [founded] stands 30 days on. */
    fun newKept(founded: String) = "new $founded: kept"

    /** A `new` whose group [founded] a user merged into [into]: a miss. */
    fun merged(founded: String, into: String, by: String) = "new $founded: merged into $into by @$by"

    private val JOINED_ACTION = Regex("""(g\d+)(?: cluster\{[^}]*\})? \([^)]*\): joined(?:; two things \([^)]*\))?$""")
    private val FOUNDED_ACTION = Regex("""new group (g\d+)(?:; two things \([^)]*\))?$""")

    /**
     * The group a call's `action` says the note was put in: the group of `g12 (0.91 vs new 0.09): joined` or `g2 cluster{…} (…): joined` (a `stage 2` one too),
     * or the group founded by `… new group g31`. Null for `left ungrouped`, no action, or any other text. The 30-day
     * sweep compares it with the note's group now: a note moved since (a merge) is not `kept`.
     */
    fun placed(action: String?): String? = action?.let { a ->
        FOUNDED_ACTION.find(a)?.groupValues?.get(1) ?: JOINED_ACTION.find(a)?.groupValues?.get(1)
    }

    private val KEPT = Regex("""^joined (g\d+): kept""")
    private val PULLED = Regex("""^joined g\d+: pulled out to g\d+ by """)
    private val NEW_KEPT = Regex("""^new g\d+: kept""")
    private val MERGED = Regex("""^new g\d+: merged into (g\d+) by """)

    /**
     * The option an outcome shows was right, for `GET /jev/summary`: the group a join was kept on; `new` for a note
     * pulled out of its group (it was a different thing) and for a founded group kept; the group a founded group was
     * merged into. Null for any other outcome.
     */
    fun truth(outcome: String): String? {
        KEPT.find(outcome)?.let { return it.groupValues[1] }
        if (PULLED.containsMatchIn(outcome) || NEW_KEPT.containsMatchIn(outcome)) return NEW
        MERGED.find(outcome)?.let { return it.groupValues[1] }
        return null
    }
}
