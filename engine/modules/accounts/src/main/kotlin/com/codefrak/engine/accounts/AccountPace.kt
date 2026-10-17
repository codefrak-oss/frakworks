package com.codefrak.engine.accounts

import java.time.Instant
import java.time.OffsetDateTime

/** The 7-day window's length in hours. */
const val WEEK_HOURS = 168.0

/**
 * The [weeklyPace] from which the picker counts an account's 7-day budget as
 * expiring: 1.0, an even pace, so any account whose budget an even pace would
 * not use up before the reset goes first.
 */
const val EXPIRING_PACE = 1.0

/**
 * The 5-hour utilization from which the picker counts an account's 5-hour
 * window as saturated: such an account ranks after every usable one below it,
 * ahead of the [expiringPace] order ([AiAccounts.pick]), since a run on it is
 * likely to be rejected minutes later.
 */
const val FIVE_HOUR_SATURATED = 0.95

/**
 * The pace an account must use its 7-day budget at to use up what is left
 * before the window resets, as a multiple of an even week's pace:
 * `(1 - utilization) × 168h / hours to reset`. 1 means an even pace uses it
 * up exactly at the reset; above 1, part of it would expire unused unless the
 * account is used faster than that. 0 with no utilization, no reset, a reset
 * at or before `now` (the window has rolled: a fresh week is an even pace), or
 * a utilization of 1 or more. A stale reading only overstates what is left.
 */
fun weeklyPace(sevenDayUtilization: Number?, sevenDayReset: OffsetDateTime?, now: Instant): Double {
    val used = sevenDayUtilization?.toDouble() ?: return 0.0
    if (sevenDayReset == null || used >= 1.0) return 0.0
    val millisLeft = sevenDayReset.toInstant().toEpochMilli() - now.toEpochMilli()
    if (millisLeft <= 0) return 0.0
    return (1.0 - used) * WEEK_HOURS / (millisLeft / 3_600_000.0)
}

/** [weeklyPace] when it is at least [EXPIRING_PACE] (1.0, an even pace), else 0: what the picker orders by first ([AiAccounts.pick]). */
fun expiringPace(sevenDayUtilization: Number?, sevenDayReset: OffsetDateTime?, now: Instant): Double =
    weeklyPace(sevenDayUtilization, sevenDayReset, now).takeIf { it >= EXPIRING_PACE } ?: 0.0

/** A model Anthropic caps under its separate Fable limit (engine/README.md, "The usage meter"). */
fun isFable(model: String?) = model != null && model.startsWith("claude-fable-")
