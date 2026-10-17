package com.codefrak.engine.doctor

import java.time.Duration
import java.time.OffsetDateTime

/**
 * Why a place is in on its back-off at [now], and until when, given its finished runs [there] (newest first,
 * `running` ones already filtered out) and the [backoff] duration: `failed` when its last 3 runs ended `failed`,
 * `bug` when its last sighting is lumped into a bug not yet fixed; null when it is not, or [backoff] has passed
 * since its last run, or [fixedSince] (a sighting id and the newest run's `startedAt`) says the bug is fixed since.
 */
internal fun backOff(there: List<DoctorRun>, backoff: Duration, now: OffsetDateTime, fixedSince: (Long, OffsetDateTime) -> Boolean): Pair<String, OffsetDateTime>? {
    val until = there.firstOrNull()?.startedAt?.plus(backoff) ?: return null
    if (!until.isAfter(now)) return null
    // A bug closed as fixed since the last run there lifts the place's back-off, both rules: the fix gets a look.
    val last = there.firstNotNullOfOrNull { it.sighting }
    if (last?.bug?.state == "fixed" && fixedSince(last.id, there.first().startedAt)) return null
    if (there.size >= 3 && there.take(3).all { it.outcome == "failed" }) return "failed" to until
    val bug = last?.bug
    return if (bug != null && bug.state != "fixed") "bug" to until else null
}
