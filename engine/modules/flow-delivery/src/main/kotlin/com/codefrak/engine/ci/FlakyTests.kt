package com.codefrak.engine.ci

import com.codefrak.engine.chore.CloseTicketDelegate
import com.codefrak.engine.github.IssueSnapshot
import com.codefrak.engine.intake.AuthorizedStart
import com.codefrak.engine.repos.TargetRepos
import com.codefrak.engine.workitems.NewWorkItem
import com.codefrak.engine.workitems.Tickets
import com.codefrak.engine.workitems.WorkItemSourceRef
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

/**
 * `main`'s build's retried tests, filed as flaky-test tickets (engine/README.md, "Flaky tests"); [MainBuildFlakes] reads
 * the builds. Only the repo flagged `engineDefects` (the engine's own, codefrak-oss/frakworks) is read: its build is
 * the one that prints `FLAKY-TEST:` lines. A pull request's check files nothing: its lines go to the reviewer. One ticket per test: a bug titled `Flaky test: <Class>.<method>`;
 * a later sighting while it is open only comments on it (no new ticket, no start), and one after it closed files a new
 * ticket (the flake came back) whose body names the closed one and its suspected cause ([fixedBefore]). A new ticket starts its bug flow on its own through [AuthorizedStart], authorized by the
 * engine's flaky-test filer ([BY]) with `authorized` as the visible record; a start that fails is logged and leaves the
 * ticket filed, for a trusted login to start. Every start carries `flakyTest` ([com.codefrak.engine.smith.Vars.FLAKY_TEST]):
 * the bug flow fixes it optimistically, with no reproduce or verify run.
 */
@Component
class FlakyTests(private val tickets: Tickets, private val repos: TargetRepos, private val start: AuthorizedStart) {
    /** Whether [repo]'s builds are read for flaky tests: the `engineDefects` repo alone. */
    fun reads(repo: String): Boolean = repos.engineDefectsRepo()?.equals(repo, ignoreCase = true) == true

    /** Files or comments a ticket for each `FLAKY-TEST:` line of [logText], seen on `main`'s build at [where]; the tickets' ids, in order. */
    fun record(repo: String, logText: String, where: String, checkUrl: String, sha: String): List<String> =
        file(tickets, start, repo, CiLog.flaky(CiLog.clean(logText)), where, checkUrl, sha).also {
            if (it.isNotEmpty()) log.info("{}: {} flaky test(s) on {}: {}", repo, it.size, sha.take(12), it)
        }

    companion object {
        const val TITLE_PREFIX = "Flaky test: "
        const val AUTHOR = "engine"
        /** Who authorizes the start, in words: a system actor, never a person's login. */
        const val BY = "the engine's flaky-test filer"
        /** The started bug's tier: the rubric's default (bots/model-choice.md), as no refiner judges these tickets. */
        const val TIER = 5
        private val log = LoggerFactory.getLogger(FlakyTests::class.java)

        fun title(test: CiLog.FlakyTest) = "$TITLE_PREFIX${test.cls}.${test.method}"

        /** [where]: the build, in words, e.g. "on `main`'s build (images run 12)"; [sha] its commit, [checkUrl] its job. */
        fun sighting(test: CiLog.FlakyTest, where: String, checkUrl: String, sha: String) = buildString {
            append("`${test.cls} > ${test.method}()` failed and then passed on a retry")
            append(where.takeIf { it.isNotBlank() }?.let { " $it" }.orEmpty())
            append(", commit ${sha.take(12)}")
            append(checkUrl.takeIf { it.isNotBlank() }?.let { ", job $it" }.orEmpty())
            append(".")
        }

        /** The one command a work run runs the test's class with, once: [CiLog.FlakyTest.module] from the build's log, else a placeholder. */
        fun command(test: CiLog.FlakyTest) =
            "cd engine && gradle :${test.module ?: "engine-<module>"}:test --tests ${test.cls} --no-daemon --offline"

        /**
         * The line a repeat sighting's body carries when [repo]'s newest ticket titled [title] is closed: its number, the
         * cause its `Done.` comment named ([CloseTicketDelegate.SUSPECTED_CAUSE]) and the sighting's UTC date. Null for a
         * first sighting, or when that ticket is open.
         */
        fun fixedBefore(tickets: Tickets, repo: String, title: String, now: Instant): String? {
            val closed = tickets.newestTitled(repo, title)?.takeIf { !it.open } ?: return null
            val cause = tickets.conversation(closed.id).asReversed().firstNotNullOfOrNull { CloseTicketDelegate.suspectedCause(it.body) } ?: "unknown cause"
            return "Fixed before in #${closed.ticket} (${cause.removeSuffix(".")}), seen again on ${LocalDate.ofInstant(now, ZoneOffset.UTC)}."
        }

        fun file(tickets: Tickets, start: AuthorizedStart, repo: String, tests: List<CiLog.FlakyTest>, where: String, checkUrl: String, sha: String,
                 now: Instant = Instant.now()): List<String> =
            tests.map { test ->
                val title = title(test)
                val open = tickets.open(repo).firstOrNull { it.title == title }
                if (open != null) {
                    tickets.comment(open.id, AUTHOR, "Seen again: ${sighting(test, where, checkUrl, sha)}\n\n— engine")
                    open.id
                } else {
                    val before = fixedBefore(tickets, repo, title, now)?.let { "|\n|$it\n" }.orEmpty()
                    val body = """
                        |## Goal
                        |
                        |Make `${test.cls} > ${test.method}()` pass every time. ${sighting(test, where, checkUrl, sha)}
                        |The build retries a failed test (engine/README.md, "Flaky tests"), so this did not fail the check;
                        |this ticket is the record that it needed the retry. Later sightings are commented here.
                        $before|
                        |The fix is optimistic: read the test and the code it touches, fix what looks wrong, and run the class once,
                        |in the foreground, with `${command(test)}`. Never loop it, run it again to prove the flake is gone, or
                        |background it: `main`'s build files a new ticket if the flake comes back.
                        |
                        |## Acceptance criteria
                        |
                        |- [ ] The cause of the flake is found and fixed, or the test is rewritten so it no longer depends on it.
                    """.trimMargin()
                    val created = tickets.create(NewWorkItem(repo, "# $title\n\n$body", "bug", WorkItemSourceRef(WorkItemSourceRef.RAISED, checkUrl.ifBlank { null }),
                        author = AUTHOR))
                    val detail = "authorized by $BY" + checkUrl.takeIf { it.isNotBlank() }?.let { ", job $it" }.orEmpty()
                    runCatching {
                        start.start(repo, "bug", IssueSnapshot(created.ticket!!, title, body, AUTHOR), BY, detail, tier = TIER, mode = null,
                            dryRun = false, label = "authorized", flakyTest = true)
                    }.onFailure { log.warn("{}: flaky-test ticket {} filed but not started: {}", repo, created.ticket, it.message) }
                    created.id
                }
            }
    }
}
