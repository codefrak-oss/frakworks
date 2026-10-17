package com.codefrak.engine.events

import com.codefrak.engine.board.BoardIssue
import com.codefrak.engine.repos.SetupReport
import com.fasterxml.jackson.annotation.JsonIgnore
import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.annotation.JsonProperty
import java.time.Instant

/**
 * One event on the engine's stream (engine/README.md, "Events").
 * Each subclass is one `event:` type and its `data` object: Boot's Jackson mapper serializes exactly its
 * fields, so the frame's shape is the class. Every event carries `repo` (`owner/name`, or `*` for a
 * fleet-wide one) and `at`; one about an issue carries `number`.
 */
@JsonIgnoreProperties("event")
sealed class EngineEvent {
    /** The `event:` line; not part of `data`. */
    @get:JsonIgnore abstract val event: String
    abstract val repo: String
    abstract val at: Instant

    /**
     * A step of an intake; `issue` carries the ask item, `filed` and `triage` the ticket, `question` the question's
     * text, and `answered` and `cancelled` who did it (`by`).
     */
    data class Intake(
        override val repo: String, val intakeId: String, val state: String, val number: Long? = null, val issueUrl: String? = null,
        val ticket: Long? = null, val ticketUrl: String? = null, val kind: String? = null, val tier: Int? = null, val question: String? = null,
        val by: String? = null, override val at: Instant = Instant.now(),
    ) : EngineEvent() { override val event get() = "intake" }

    /**
     * GitHub changed the issue (the webhook's delivery). `issue` is its board row as `GET /issues` lists it, read by
     * number (GitHub's list lags a new issue), for an `issues` delivery whose issue is open; null for a closed issue,
     * an `issue_comment` or `pull_request` delivery, and when the row could not be read in time.
     */
    data class Ticket(
        override val repo: String, val number: Long, val action: String, val label: String? = null, val prNumber: Long? = null,
        val issue: BoardIssue? = null, override val at: Instant = Instant.now(),
    ) : EngineEvent() { override val event get() = "ticket" }

    /**
     * The engine filed an issue (an ask item, a ticket, a split's child, a proposal's build), published the moment
     * GitHub answered the create and not after the command commits ([CreatedEvents]). `issue` is its board row, read
     * by number, null when GitHub could not be read for it.
     */
    data class Created(
        override val repo: String, val number: Long, val url: String, val issue: BoardIssue? = null,
        override val at: Instant = Instant.now(),
    ) : EngineEvent() { override val event get() = "created" }

    /**
     * GitHub delivered a push (the webhook's delivery): `ref`, `before` and `after` as the payload has them; `default`
     * says whether `ref` is the repository's default branch. What First mate's fresh clones follow;
     * it wakes no scan.
     */
    data class Push(
        override val repo: String, val ref: String, val before: String, val after: String, val default: Boolean,
        override val at: Instant = Instant.now(),
    ) : EngineEvent() { override val event get() = "push" }

    /**
     * A root flow started, ended or was cancelled, or moved to another activity (`step`: the innermost one, `stage`:
     * the root's, in the board's words). `issue` is the issue's board row as `GET /issues` lists it, null when
     * GitHub could not be read for it.
     */
    data class Flow(
        override val repo: String, val number: Long, val instance: String, val root: String, val kind: String, val state: String,
        val step: String? = null, val stage: String? = null, val prUrl: String? = null, val outcome: String? = null,
        val issue: BoardIssue? = null, override val at: Instant = Instant.now(),
    ) : EngineEvent() { override val event get() = "flow" }

    /** An Operaton incident appeared (`open`), was retried (`resolved`) or went with its instance (`deleted`). */
    data class Incident(
        override val repo: String, val number: Long, val incidentId: String, val instance: String, val root: String, val state: String,
        val activityId: String?, val type: String, val message: String? = null, val fix: Map<String, String>? = null,
        override val at: Instant = Instant.now(),
    ) : EngineEvent() { override val event get() = "incident" }

    /**
     * A child's question under a running live parent (#100049; engine/docs/feature.md, "Children ask the parent"):
     * `asked` of the parent (`askedOf`, `#<n>`) with the question's `text`, from `post the smith's question`;
     * `answered` by the parent (`by`) through answer_child; `escalated` to the user by answer_child or by `note the
     * live parent ended`. `instance` is the child's flow. A question asked of the user publishes no `question` frame:
     * its `needs` frame is the wake, as before.
     */
    data class Question(
        override val repo: String, val number: Long, val instance: String, val state: String, val text: String,
        val askedOf: String? = null, val by: String? = null, override val at: Instant = Instant.now(),
    ) : EngineEvent() { override val event get() = "question" }

    /** A row of `GET /needs` appeared or went; `for` (JSON `for`) whom it is for, as the row's, null when for everyone or unknown. */
    data class Needs(
        override val repo: String, val kind: String, val state: String, val number: Long? = null, val actionId: String? = null,
        override val at: Instant = Instant.now(), @get:JsonProperty("for") val forLogin: String? = null,
        /** On a `fired` HITLP approve (a standing approval decided it): the rule's label and the request's action; else null and left out. */
        @get:JsonInclude(JsonInclude.Include.NON_NULL) val rule: String? = null,
        @get:JsonInclude(JsonInclude.Include.NON_NULL) val action: String? = null,
    ) : EngineEvent() { override val event get() = "needs" }

    /** A Smith sandbox started or its exit was collected. */
    data class Session(
        /** The run's ticket; null for a mode `job` run, which carries [jobId] instead. */
        override val repo: String, val number: Long?, val runId: String, val instance: String, val mode: String, val model: String?,
        val tier: Int?, val account: String?, val state: String, val filename: String?, val host: String?,
        val exitCode: Long? = null, val seconds: Long? = null, val peakMiB: Long? = null, override val at: Instant = Instant.now(),
        val jobId: String? = null,
    ) : EngineEvent() { override val event get() = "session" }

    /**
     * A custom job's run (engine/README.md, "Custom jobs and the SDK"): `started` when its `job` instance begins, `ended`
     * with `outcome` (`done` or `failed`) when it reaches its end. `jobId` is the `engine.job` row, `instance` the run.
     */
    data class Job(
        override val repo: String, val jobId: String, val name: String, val kind: String, val instance: String, val state: String,
        val outcome: String? = null, val owner: String = "user", override val at: Instant = Instant.now(),
    ) : EngineEvent() { override val event get() = "job" }

    /**
     * One stream-json line a first-mate session pod wrote (engine/README.md, "First-mate sessions"): `sessionId`, the
     * transcript row's `seq`, and the line as `claude -p` wrote it (parsed JSON, or the raw text when it was not JSON).
     */
    data class FirstMate(
        override val repo: String, val sessionId: String, val seq: Int, val line: Any?, override val at: Instant = Instant.now(),
    ) : EngineEvent() { override val event get() = "first-mate" }

    /**
     * A deploy the engine was told of (`POST /repos/{owner}/{name}/deploys`, or its own start for its build repo;
     * engine/README.md, "Deploys"): `sha`, the commit now in production for `repo`, and `source`, who said so (null
     * when the caller named none).
     */
    data class Deploy(
        override val repo: String, val sha: String, val source: String? = null, override val at: Instant = Instant.now(),
    ) : EngineEvent() { override val event get() = "deploy" }

    /** A target repo row changed. */
    data class Repo(
        override val repo: String, val state: String, val fields: List<String>? = null, override val at: Instant = Instant.now(),
    ) : EngineEvent() { override val event get() = "repo" }

    /**
     * A requested check of the repo's GitHub-side setup (`POST /repos/setup/check`): `started` when the background read
     * begins, `done` with the [SetupReport] as `GET /repos/{o}/{n}/setup` serializes it and its `checkedAt` (a fresh kept
     * report goes out as one `done` with its original `checkedAt`), `failed` with GitHub's error and no report.
     */
    data class Setup(
        override val repo: String, val state: String, val checkedAt: Instant? = null, val report: SetupReport? = null,
        val error: String? = null, override val at: Instant = Instant.now(),
    ) : EngineEvent() { override val event get() = "setup" }

    /**
     * A fleet-wide setting changed; always `repo: "*"`, so only a `*` subscription gets it. `name` is the setting's:
     * `engine.public-url` (`value` the URL or null) or `engine.hitlp` (the HITLP server, #2500; `value` is
     * `{changed: [field names]}`, never a value: a token is among them).
     */
    data class Setting(
        val name: String, val value: Any? = null, override val at: Instant = Instant.now(),
    ) : EngineEvent() { override val repo get() = FLEET; override val event get() = "setting" }

    /** The doctor's tick: the counts the dashboard header shows. Fleet-wide. */
    data class Heartbeat(
        val running: Int, val waiting: Int, val incidents: Int, override val at: Instant = Instant.now(),
    ) : EngineEvent() { override val repo get() = FLEET; override val event get() = "heartbeat" }

    companion object {
        /** The `repo` of a fleet-wide event, and the subscription that receives every event. */
        const val FLEET = "*"
    }
}
