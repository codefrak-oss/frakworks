package com.codefrak.engine.settings

import com.codefrak.engine.engineSetting
import com.codefrak.engine.upsertEngineSetting
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository
import java.time.Duration

/**
 * How long a live parent's await_children waits for a child to change before it answers `{changed: false}`
 * (engine/README.md, "Live parent"; #2571): row `parent.await-cap-seconds` of `engine.engine_setting`, seeded 900 (15
 * minutes) by V90. Set through `PUT /settings/parent-await-cap`; nothing outside the database holds it.
 */
@Repository
class ParentAwaitCap(private val jdbc: JdbcClient) {
    /** The cap; [MIN] when the row is missing or unreadable, so a broken row never makes a parent wait unbounded. */
    fun get(): Duration = jdbc.engineSetting(NAME)?.toLongOrNull()?.takeIf { it in RANGE }?.let(Duration::ofSeconds) ?: MIN

    fun set(seconds: Long) {
        require(seconds in RANGE) { problem(seconds)!! }
        jdbc.upsertEngineSetting(NAME, seconds.toString())
    }

    companion object {
        const val NAME = "parent.await-cap-seconds"
        /** 1 minute to 1 hour: a parent that waits longer than its own run can is no use. */
        val RANGE = 60L..3600L
        val MIN: Duration = Duration.ofSeconds(RANGE.first)

        fun problem(seconds: Long): String? =
            if (seconds in RANGE) null else "seconds is ${RANGE.first} to ${RANGE.last}, not $seconds"
    }
}
