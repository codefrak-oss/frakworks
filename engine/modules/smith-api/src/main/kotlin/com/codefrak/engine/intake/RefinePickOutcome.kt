package com.codefrak.engine.intake

import com.codefrak.engine.chore.ProcessFlowStarter
import com.codefrak.engine.feature.FeatureMode
import com.codefrak.engine.jev.JevAnswer
import com.codefrak.engine.jev.JevAnswers
import com.codefrak.engine.jev.JevCalls
import com.codefrak.engine.smith.Vars

/** Where a refined ticket stands on the three fields: its kind, a feature's mode (null for none or unknown), its tier (null for unknown). */
data class Placement(val kind: String?, val mode: String?, val tier: Int?) {
    override fun toString() = "${kind ?: "-"}${mode?.let { " $it" }.orEmpty()} tier ${tier ?: "-"}"
}

/** What a flow's start records back against the pick [RefinePicker] made at intake. */
object RefinePickOutcome {
    /** The `engine.jev_call` id a filed ticket's trailer names (`· jev <id>`), or null. */
    private val JEV_TRAILER = Regex("""<!-- engine: intake [^>]*?·\s*jev\s+([0-9a-f-]{36})""")
    fun callOf(body: String): String? = JEV_TRAILER.find(body)?.groupValues?.get(1)

    private val STARTED = Regex("""^started as (\S+)(?: (${FeatureMode.ALL.joinToString("|")}))? tier (\d+|-)""")
    private val FILED = Regex("""^filed: kind ([^\s,]+)[^,]*, mode ([^\s,]+)[^,]*(?:, tier (\d+))?""")
    private val FILED_NO_ANSWER = Regex("""^no answer: filed at the refiner's (\S+)(?: (${FeatureMode.ALL.joinToString("|")}))? tier (\d+)""")

    /** What a flow started as, from the `started as …` prefix [started] writes (the older rows' whole outcome); null when it is not one. */
    fun startedAs(outcome: String?): Placement? = outcome?.let { STARTED.find(it) }?.destructured?.let { (kind, mode, tier) ->
        Placement(kind, mode.ifEmpty { null }, tier.toIntOrNull())
    }

    /** What the intake filed, from the call's [RefinePicker.action]; null when the action says neither. */
    fun filedAs(action: String?): Placement? {
        if (action == null) return null
        FILED.find(action)?.destructured?.let { (kind, mode, tier) -> return Placement(kind, mode.takeIf { it in FeatureMode.ALL }, tier.toIntOrNull()) }
        return FILED_NO_ANSWER.find(action)?.destructured?.let { (kind, mode, tier) -> Placement(kind, mode.ifEmpty { null }, tier.toIntOrNull()) }
    }

    /** Jev's pick on each field it answered, whatever its confidence; null fields were not asked or not answered. */
    fun jevPicked(answers: Map<String, JevAnswer>?): Placement? {
        if (answers.isNullOrEmpty()) return null
        val picked = Placement(answers[RefinePicker.KIND]?.choice, answers[RefinePicker.MODE]?.choice?.takeIf { it in FeatureMode.ALL },
            answers[RefinePicker.TIER]?.let { RefinePicker.tierOf(it) })
        return picked.takeIf { it.kind != null || it.mode != null || it.tier != null }
    }

    /**
     * The fields on which `now` differs from `picked`, each with `picked`'s value, and how many fields could be
     * compared: a field `picked` has and `now` knows; the mode only when `now` is a feature.
     */
    fun differences(picked: Placement, now: Placement): Pair<List<String>, Int> {
        val diffs = mutableListOf<String>()
        var compared = 0
        if (picked.kind != null && now.kind != null) { compared++; if (picked.kind != now.kind) diffs += "kind ${picked.kind}" }
        if (picked.mode != null && now.kind == "feature" && now.mode != null) { compared++; if (picked.mode != now.mode) diffs += "mode ${picked.mode}" }
        if (picked.tier != null && now.tier != null) { compared++; if (picked.tier != now.tier) diffs += "tier ${picked.tier}" }
        return diffs to compared
    }

    /**
     * Whether the ticket started as Jev picked, on every field Jev answered, whatever its confidence (a field below
     * the floor that started otherwise counts against); null when nothing could be compared. Read from the
     * `started as` prefix alone, so rows written before the suffix are judged the same.
     */
    fun agreed(answers: Map<String, JevAnswer>?, outcome: String?): Boolean? {
        val picked = jevPicked(answers) ?: return null
        val now = startedAs(outcome) ?: return null
        val (diffs, compared) = differences(picked, now)
        return if (compared == 0) null else diffs.isEmpty()
    }

    /**
     * The outcome a start records: `started as <flow>[ <mode>] tier <n>`, then `; jev: agreed` or `; jev: differs
     * (picked …)` against Jev's answers, and `, relabelled from <kind>[ <mode>] tier <n>` when it differs from
     * what was filed (the call's action). Each part only when it can be told.
     */
    fun outcome(now: Placement, answers: Map<String, JevAnswer>?, action: String?): String = buildString {
        append("started as $now")
        val picked = jevPicked(answers)
        val jev = picked?.let { differences(it, now) }?.takeIf { it.second > 0 }?.let { (diffs, _) ->
            if (diffs.isEmpty()) "jev: agreed" else "jev: differs (picked ${diffs.joinToString(", ")})"
        }
        jev?.let { append("; $it") }
        val filed = filedAs(action)
        if (filed != null && differences(filed, now).first.isNotEmpty()) append(if (jev != null) ", " else "; ").append("relabelled from $filed")
    }

    /**
     * When a flow starts on a ticket whose trailer names a refine-pick call: the kind, mode and tier it
     * started at are the call's outcome, with whether that is Jev's pick and whether the user relabelled
     * what was filed ([outcome]), read from the call's own row: no GitHub call.
     */
    fun started(calls: JevCalls, flow: String, body: String, tier: Int?, extra: Map<String, Any>) {
        val call = callOf(body) ?: return
        val t = tier ?: ProcessFlowStarter.parseTierFromBody(body)
        val now = Placement(flow, extra[Vars.FEATURE_MODE] as? String, t)
        val row = calls.get(call)
        calls.recordOutcome(call, outcome(now, JevAnswers.parse(row?.response), row?.action))
    }
}
