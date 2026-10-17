package com.codefrak.engine.jev

import com.codefrak.engine.utc
import java.time.OffsetDateTime
import java.time.ZoneOffset

/**
 * One use case over the window of `GET /jev/summary`: its calls, the no-answers among them, the median latency,
 * the picks per question (option to count), the confidence histogram in ten 0.1 buckets (`confidence[i]` counts
 * `[i/10, (i+1)/10)`, 1.0 in the last) with the `floor` in force (the latest call's threshold), the actions (their
 * variable parts dropped, [JevSummaries.actionKind]), how many calls have an outcome, how many of those
 * [JevAgreement] could judge and how many agreed, `agreement` (null when none was judged), the runs saved
 * (the two triage use cases only, else null) and the calls per UTC day.
 */
data class JevUseCaseSummary(
    val useCase: String, val calls: Int, val noAnswer: Int, val medianLatencyMs: Long?,
    val picks: Map<String, Map<String, Int>>, val confidence: List<Int>, val floor: Double?,
    val actions: Map<String, Int>, val withOutcome: Int, val judged: Int, val agreed: Int, val agreement: Double?,
    val runsSaved: Int?, val perDay: Map<String, Int>,
)

/** Every use case together, for the top strip. */
data class JevTotals(
    val calls: Int, val noAnswer: Int, val medianLatencyMs: Long?, val withOutcome: Int, val judged: Int, val agreed: Int, val agreement: Double?,
)

/** `GET /jev/summary`: the window, the totals, and each use case, the nine in [JevSummaries.USE_CASES] always there. */
data class JevSummary(val since: String, val until: String, val totals: JevTotals, val useCases: List<JevUseCaseSummary>)

/** Builds [JevSummary] from the window's rows, in Kotlin rather than SQL so H2 and Postgres answer alike. */
object JevSummaries {
    /** The use cases the engine asks Jev about, in the page's order; `acceptance` only for a feature parked before #583. */
    val USE_CASES = listOf("acceptance", "proposal-decision", "ci-triage", "refine-pick", "incident-triage", "intake-duplicate", "friction-group", "friction-two-things", "review-prediction")

    const val BUCKETS = 10

    fun of(rows: List<JevCall>, since: OffsetDateTime, until: OffsetDateTime, agreement: JevAgreement): JevSummary {
        val read = rows.map { Read(it, agreement) }
        val byUseCase = read.groupBy { it.call.useCase }
        val useCases = (USE_CASES + byUseCase.keys.sorted()).distinct().map { useCase(it, byUseCase[it].orEmpty(), agreement) }
        val judged = read.filter { it.verdict != null }
        return JevSummary(utc(since), utc(until), JevTotals(
            read.size, read.count { it.noAnswer }, median(read.mapNotNull { it.call.latencyMs }),
            read.count { it.call.outcome != null }, judged.size, judged.count { it.verdict == true }, share(judged),
        ), useCases)
    }

    private fun useCase(name: String, rows: List<Read>, agreement: JevAgreement): JevUseCaseSummary {
        val picks = linkedMapOf<String, MutableMap<String, Int>>()
        rows.flatMap { it.picks }.forEach { p -> p.pick?.let { picks.getOrPut(p.question) { linkedMapOf() }.merge(it, 1, Int::plus) } }
        val confidence = MutableList(BUCKETS) { 0 }
        rows.mapNotNull { it.confidence }.forEach { confidence[(it * BUCKETS).toInt().coerceIn(0, BUCKETS - 1)]++ }
        val actions = linkedMapOf<String, Int>()
        rows.mapNotNull { it.call.action }.forEach { actions.merge(actionKind(name, it), 1, Int::plus) }
        val judged = rows.filter { it.verdict != null }
        return JevUseCaseSummary(
            useCase = name, calls = rows.size, noAnswer = rows.count { it.noAnswer },
            medianLatencyMs = median(rows.mapNotNull { it.call.latencyMs }),
            picks = picks.mapValues { (_, counts) -> counts.entries.sortedByDescending { it.value }.associate { it.key to it.value } },
            confidence = confidence, floor = rows.lastOrNull { it.call.threshold != null }?.call?.threshold,
            actions = actions.entries.sortedByDescending { it.value }.associate { it.key to it.value },
            withOutcome = rows.count { it.call.outcome != null }, judged = judged.size, agreed = judged.count { it.verdict == true },
            agreement = share(judged),
            runsSaved = if (name in agreement.saves) rows.count { agreement.saved(name, it.call.outcome) } else null,
            perDay = rows.groupingBy { it.call.calledAt.withOffsetSameInstant(ZoneOffset.UTC).toLocalDate().toString() }.eachCount().toSortedMap(),
        )
    }

    /** One row read once: its picks, the row's confidence, whether it had no answer, and its verdict. */
    private class Read(val call: JevCall, agreement: JevAgreement) {
        val answers = JevAnswers.parse(call.response)
        val picks = JevAnswers.picks(answers)
        val confidence = JevAnswers.summary(picks).second
        val noAnswer = answers == null || call.error != null
        val verdict = agreement.verdict(call.useCase, answers, call.outcome)
    }

    private val NUMBER = Regex("""\s*\(?\b\d+(\.\d+)?\)?(/\d+\))?""")
    private val TICKET = Regex("""#\d+""")

    /**
     * An action with its variable parts dropped, so like actions count together: numbers (floors, confidences,
     * stages) and ticket numbers; refine-pick's per-field detail becomes whose pick was filed; proposal-decision's
     * verb after the colon goes.
     */
    fun actionKind(useCase: String, action: String): String = when (useCase) {
        "refine-pick" -> when {
            action.startsWith("no answer") -> "no answer: the refiner's"
            "<floor" in action -> "filed, a field below the floor"
            else -> "filed at jev's pick"
        }
        "proposal-decision" -> if (action.startsWith("took ")) "took jev's pick" else action.substringBefore(':').replace(NUMBER, "")
        else -> action.replace(TICKET, "#n").replace(NUMBER, "").replace(Regex("""\s+"""), " ").trim()
    }

    private fun share(judged: List<Read>) = if (judged.isEmpty()) null else judged.count { it.verdict == true }.toDouble() / judged.size

    fun median(values: List<Long>): Long? {
        if (values.isEmpty()) return null
        val sorted = values.sorted()
        val mid = sorted.size / 2
        return if (sorted.size % 2 == 1) sorted[mid] else (sorted[mid - 1] + sorted[mid]) / 2
    }
}
