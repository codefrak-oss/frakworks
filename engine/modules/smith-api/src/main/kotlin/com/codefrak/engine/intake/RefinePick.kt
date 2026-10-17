package com.codefrak.engine.intake

import com.codefrak.engine.engineJson
import com.codefrak.engine.smith.Brief
import com.codefrak.engine.chore.ProcessFlowStarter
import com.codefrak.engine.feature.FeatureMode
import com.codefrak.engine.jev.JevAnswer
import com.codefrak.engine.jev.JevCalls
import com.codefrak.engine.jev.JevContext
import com.codefrak.engine.jev.JevDecider
import com.codefrak.engine.jev.JevQuestion
import com.codefrak.engine.jev.RefinePickQuestions
import com.codefrak.engine.smith.Vars
import com.codefrak.engine.smith.issueOrNull
import com.codefrak.engine.tier.TierTable
import org.operaton.bpm.engine.delegate.DelegateExecution
import org.operaton.bpm.engine.variable.Variables
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import tools.jackson.module.kotlin.readValue

/**
 * What the intake files a refined ticket at (engine/README.md, "Intake", "Chore or feature"): its kind,
 * a feature's mode and its tier, each Jev's pick or the refiner's choice, with the `note` the filed body
 * carries when Jev answered (its pick and confidence, so the user can relabel before authorizing) and
 * the `engine.jev_call` id (`call`) the filed ticket's trailer names. Kept as the `refinePick` variable.
 */
data class RefinePick(val kind: String, val featureMode: String?, val tier: Int, val note: String? = null, val call: String? = null) {
    fun bytes(): ByteArray = engineJson.writeValueAsBytes(this)

    companion object {
        const val VAR = "refinePick"

        /** The refiner's own choice, as filed before Jev. */
        fun refiner(result: RefineResult) = RefinePick(result.kind!!, result.featureMode.takeIf { result.kind == "feature" }, result.ticket!!.tier)

        /** The pick `read result` kept, or null when it kept none (a result from before it, or not a `file`). */
        fun of(execution: DelegateExecution): RefinePick? =
            (execution.getVariable(VAR) as? ByteArray)?.let { engineJson.readValue<RefinePick>(it) }
    }
}

/**
 * Jev's decision on a refined ticket (use case `refine-pick`; engine/README.md, "Intake", "Jev decides the kind, the
 * feature mode and the tier"):
 * given the ask and the drafted ticket, a `choice` of kind, a `choice` of feature mode and a `score` over
 * the ten tiers of the engine's tier map (`TierTable`), with the rules of bots/model-choice.md in the tier question. A field
 * the user named (the ask's `Tier:` line, or the refiner's `userNamed`) is not asked. Each pick at
 * `engine.jev.refine-floor` or above is filed; below it, or with no answer, the refiner's choice stands.
 * Called by `read result` (`ReadResultDelegate`) for a refine run's `file`.
 */
@Component
class RefinePicker(
    private val jev: JevDecider,
    private val calls: JevCalls,
    private val tiers: TierTable,
    @Value("\${engine.jev.refine-floor:0.8}") private val floor: Double = 0.8,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /** Sets `refinePick` for a `file`, clears it otherwise; never fails the run for Jev's sake. */
    fun pick(execution: DelegateExecution, result: RefineResult) {
        if (result.outcome != "file") { execution.setVariable(RefinePick.VAR, null); return }
        val refiner = RefinePick.refiner(result)
        val ask = Brief.text(execution.getVariable("ask"))
        val toAsk = questions(refiner, bound(result, ask))
        val pick = if (toAsk.isEmpty()) refiner else runCatching {
            val repo = execution.getVariable(Vars.REPO) as? String
            val intake = runCatching { execution.processInstance?.superExecution?.processInstanceId }.getOrNull() ?: execution.processInstanceId
            val call = jev.decide(USE_CASE, JevContext(intake, repo, execution.issueOrNull()),
                state(ask, result), toAsk, floor)
            val picked = read(refiner, call.answers, floor, call.callId)
            // The audit is not the decision: a failed write must not throw the pick away.
            runCatching { calls.setAction(call.callId, action(refiner, picked, call.answers)) }
                .onFailure { log.warn("jev refine-pick {}: action not recorded: {}", call.callId, it.message) }
            log.info("intake {}: jev picked {}; filing {} {} tier {}", intake, call.answers?.let { summary(it) } ?: "nothing",
                picked.kind, picked.featureMode ?: "", picked.tier)
            picked
        }.onFailure { log.warn("jev refine-pick failed; the refiner's choice stands: {}", it.message) }.getOrDefault(refiner)
        execution.setVariable(RefinePick.VAR, Variables.byteArrayValue(pick.bytes()))
    }

    /** The questions for the fields the user left open: kind only over [KINDS]' choices, mode only when the kind can be a feature. */
    fun questions(refiner: RefinePick, bound: Set<String>): Map<String, JevQuestion> = buildMap {
        val kindOpen = KIND !in bound && refiner.kind in KINDS
        if (kindOpen) put(KIND, KIND_QUESTION)
        if (MODE !in bound && (kindOpen || refiner.kind == "feature")) put(MODE, MODE_QUESTION)
        if (TIER !in bound) put(TIER, tierQuestion())
    }

    /** The ten tiers as levels, in order, each by its model and effort. */
    fun tierQuestion(): JevQuestion = JevQuestion.score(TIER_INSTRUCTIONS,
        tiers.all().map { "Tier ${it.tier}: ${it.model} at ${it.effort} effort" })

    companion object {
        const val USE_CASE = "refine-pick"
        const val KIND = RefinePickQuestions.KIND; const val MODE = RefinePickQuestions.MODE; const val TIER = RefinePickQuestions.TIER

        /** The kinds Jev chooses from; a refiner's `research` or `incident` stands as it is. */
        val KINDS_BY_NAME = mapOf(
            "chore" to "A change the ask specifies: maintenance, docs, or new behaviour whose what and how-to-check are stated, " +
                "even across several files. One work run, a review and a merge",
            "feature" to "New behaviour whose design is genuinely open: several defensible approaches, or unclear scope. " +
                "Adds a design run and an acceptance check after the merge, maybe a split into child tickets",
            "bug" to "Broken behaviour: something that worked, or should work, does not",
            "proposal" to "Decide whether to build something and what, as a doc the user decides on, before anyone builds it",
        )
        val KINDS = KINDS_BY_NAME.keys
        val KIND_QUESTION = JevQuestion.choice(
            "The `ask` is what the user asked for; the `ticket` is the ticket drafted from it. What kind of work is the ticket? " +
                "A kind the user names in the ask binds.", KINDS_BY_NAME)
        val MODE_QUESTION = JevQuestion.choice(
            "If the `ticket` is filed as a feature, which mode should its flow run in? The mode the user names in the ask binds.",
            mapOf(FeatureMode.AUTO to "Every assumption the design makes stands, and a Smith checks acceptance; the default",
                FeatureMode.CONFIRM to "The design's important open questions go to the user before the work; after the merge a Smith checks acceptance, as in auto"))

        /** bots/model-choice.md, "The rules", trimmed to what a pick uses. */
        const val TIER_INSTRUCTIONS = "Which tier should a Smith run the `ticket` at? Tiers are ordered by judgement, not cost. " +
            "The center of the distribution is opus at medium effort (tiers 5 to 7): most tickets live there. " +
            "Low effort (tier 4) for a tightly scoped ticket with a clear checklist. Sonnet (tiers 2 and 3) for mechanical work: " +
            "ports, renames, test scaffolding, documentation sweeps, anything where reasoning is not the bottleneck; haiku (tier 1) " +
            "for the most trivial of it. Fable (tiers 8 to 10) and high effort are the tail, spent on purpose: a design or research " +
            "ticket, a security review, a run that came back thin at the default. A tier the user names in the ask binds."

        /** Characters of the ask and of the ticket's body that go to Jev: well under its 32k-token state limit. */
        const val STATE_CHARS = 24_000

        /** The fields the user named: the refiner's `userNamed`, and the tier when the ask carries a `Tier:` line. */
        fun bound(result: RefineResult, ask: String?): Set<String> =
            result.userNamed.orEmpty().toSet() + setOfNotNull(TIER.takeIf { ask != null && ProcessFlowStarter.hasTierLine(ask) })

        fun state(ask: String?, result: RefineResult): Map<String, Any?> = mapOf(
            "ask" to ask?.take(STATE_CHARS),
            "ticket" to mapOf("title" to result.ticket!!.title, "body" to result.ticket.body.take(STATE_CHARS)),
        )

        /** A score's level, 1-based: its most probable level index, else its score rounded; null when it has neither. */
        fun tierOf(answer: JevAnswer): Int? = RefinePickQuestions.tierOf(answer)

        /** Whether an answer clears the floor. */
        private fun sure(answer: JevAnswer?, floor: Double) = answer?.confidence.let { it != null && it >= floor }

        /**
         * The rule on Jev's answers (null: no answer): each field at the floor or above is Jev's pick, else
         * the refiner's. A feature Jev made of another kind takes Jev's mode when sure, else `confirm`, the
         * one that puts the design before the user. A kind that is not a feature carries no mode.
         */
        fun read(refiner: RefinePick, answers: Map<String, JevAnswer>?, floor: Double, callId: String?): RefinePick {
            if (answers.isNullOrEmpty()) return refiner
            val kindAnswer = answers[KIND]?.takeIf { it.choice in KINDS }
            val kind = kindAnswer?.takeIf { sure(it, floor) }?.choice ?: refiner.kind
            val modeAnswer = answers[MODE]?.takeIf { it.choice in FeatureMode.ALL }
            val mode = when {
                kind != "feature" -> null
                sure(modeAnswer, floor) -> modeAnswer!!.choice
                else -> refiner.featureMode ?: FeatureMode.CONFIRM
            }
            val tierAnswer = answers[TIER]
            val jevTier = tierAnswer?.let { tierOf(it) }
            val tier = jevTier?.takeIf { sure(tierAnswer, floor) } ?: refiner.tier
            val picked = RefinePick(kind, mode, tier)
            return picked.copy(note = note(refiner, picked, kindAnswer, modeAnswer, tierAnswer?.takeIf { jevTier != null }, floor), call = callId)
        }

        /** The lines the filed body ends with: what Jev picked, what the refiner chose, and what was filed. */
        fun note(refiner: RefinePick, picked: RefinePick, kind: JevAnswer?, mode: JevAnswer?, tier: JevAnswer?, floor: Double): String? {
            val parts = listOfNotNull(
                kind?.let { "kind `${it.choice}` (${DuplicateCheck.pct(it.confidence)})" },
                mode?.takeIf { picked.kind == "feature" }?.let { "feature mode `${it.choice}` (${DuplicateCheck.pct(it.confidence)})" },
                tier?.let { "tier ${tierOf(it)} (${DuplicateCheck.pct(it.confidence)})" },
            )
            if (parts.isEmpty()) return null
            val refinerSaid = "`${refiner.kind}`${refiner.featureMode?.let { " (`$it`)" }.orEmpty()}, tier ${refiner.tier}"
            val filed = "`${picked.kind}`${picked.featureMode?.let { " (`$it`)" }.orEmpty()}, tier ${picked.tier}"
            return "---\n\n_Jev (TypeSafe's decision model) picked ${parts.joinToString(", ")}; the refiner chose $refinerSaid. " +
                "Filed as $filed: a pick is taken at confidence $floor or above, else the refiner's choice stands. " +
                "If the kind or tier is wrong, relabel it, or put a `Tier:` line in the body, before authorizing._"
        }

        /**
         * The call's `action`: what was filed, and from whose choice, per field (`jev`, `refiner`: the refiner's
         * choice and Jev's the same, `jev X<floor`: the refiner's, Jev's X below the floor, `not asked`).
         */
        fun action(refiner: RefinePick, picked: RefinePick, answers: Map<String, JevAnswer>?): String {
            if (answers.isNullOrEmpty()) return "no answer: filed at the refiner's ${refiner.kind}${refiner.featureMode?.let { " $it" }.orEmpty()} tier ${refiner.tier}"
            fun from(field: String, jevValue: Any?, filed: Any?, refinerValue: Any?) = when {
                field == MODE && picked.kind != "feature" -> "-"
                field !in answers -> "$filed (not asked)"
                jevValue == filed && filed != refinerValue -> "$filed (jev)"
                jevValue == filed -> "$filed (jev=refiner)"
                else -> "$filed (jev $jevValue<floor)"
            }
            return ("filed: kind ${from(KIND, answers[KIND]?.choice, picked.kind, refiner.kind)}, " +
                "mode ${from(MODE, answers[MODE]?.choice, picked.featureMode ?: "-", refiner.featureMode ?: "-")}, " +
                "tier ${from(TIER, answers[TIER]?.let { tierOf(it) }, picked.tier, refiner.tier)}")
        }

        private fun summary(answers: Map<String, JevAnswer>) = answers.entries.joinToString { (k, a) ->
            "$k=${a.choice ?: a.score?.let { s -> "%.2f".format(s) }}@${DuplicateCheck.pct(a.confidence)}"
        }
    }
}
