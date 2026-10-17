package com.codefrak.engine.jobs

import com.codefrak.engine.badRequest
import com.codefrak.engine.engineJson
import com.codefrak.engine.repos.TargetRepos
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Component
import org.springframework.web.server.ResponseStatusException
import tools.jackson.databind.JsonNode
import java.time.Instant

/**
 * Who asks for a job write, as the engine placed them: a Smith sandbox, a caller it [Unplaced] ([why] is the refusal's
 * text), or a caller that is no Smith with its bearer token's login.
 */
sealed interface JobWriter {
    data class Smith(val runId: String) : JobWriter
    data class Unplaced(val why: String) : JobWriter
    data class Caller(val login: String?) : JobWriter
}

/**
 * The job registrar behind `/jobs` ([JobController]) and the diagnostics server's job tools (`JobsTool`): one code path,
 * so both answer and refuse alike (engine/README.md, "Custom jobs and the SDK"). Reads are open like every read; a write
 * (`POST`, `DELETE`, `execute`) needs the login the caller's bearer token proved (any login: the trusted list does not gate jobs), and is refused from a Smith sandbox, told by the request's source address as the other
 * write endpoints refuse it. Every job names its repo: there is no default. A bean job's jar is loaded by [PluginJars]
 * before its row is inserted, so a jar that cannot be loaded is a 400 with the reason and no row; its `DELETE` releases it.
 * A flow job's jar is loaded, its diagrams validated and deployed as `job:<id>` with the row ([PluginFlows]): a refusal
 * is a 400 listing every problem, one per line, and leaves nothing; its `DELETE` undeploys them, 409 while one runs.
 */
@Component
class JobRegistrar(
    private val store: JobStore, private val starter: JobStarter, private val repos: TargetRepos,
    private val pluginJars: PluginJars, private val pluginFlows: PluginFlows, private val runs: JobRuns,
) {
    private val log = LoggerFactory.getLogger(JobRegistrar::class.java)

    /** `POST /jobs`: the created job's row, or a [ResponseStatusException] with the endpoint's status and text. */
    fun create(body: JsonNode, by: JobWriter): JobView {
        if (!body.isObject) throw badRequest("the body is an object with name, kind, repo, config and signal")
        val extra = body.propertyNames().toSet() - setOf("name", "kind", "repo", "config", "signal")
        if (extra.isNotEmpty()) throw badRequest("a job takes name, kind, repo, config and signal, not ${extra.sorted().joinToString()}")
        val name = body["name"]?.takeIf { it.isString }?.asString()?.trim()?.takeIf { it.isNotEmpty() && it.length <= 200 }
            ?: throw badRequest("name is a non-blank string of at most 200 characters")
        val kind = try { JobKind.of(body["kind"]?.takeIf { it.isString }?.asString()) } catch (e: IllegalArgumentException) { throw badRequest(e.message!!) }
        if (kind == JobKind.REFACTOR) throw badRequest(REFACTOR_IS_SYSTEM)
        val repo = targetRepo(body["repo"]?.takeIf { it.isString }?.asString())
        val config = body["config"]
        try { kind.check(config) } catch (e: IllegalArgumentException) { throw badRequest(e.message!!) }
        val signal = try { Signal.parse(body["signal"]) } catch (e: IllegalArgumentException) { throw badRequest(e.message!!) }
        if (signal is Signal.At && !signal.at.isAfter(Instant.now())) throw badRequest("at ${signal.at} is in the past")
        if (kind != JobKind.PROMPT) try { PluginJars.checkName(config!!["jar"].asString()) } catch (e: IllegalArgumentException) { throw badRequest(e.message!!) }
        val login = writer(by, "register a job")
        val id = JobStore.newId()
        if (kind == JobKind.FLOW) {
            val job = try { pluginFlows.register(name, repo, config!!, signal, login, id) } catch (e: PluginLoadException) {
                log.warn("job {} on {}: {}", name, repo, e.message)
                throw badRequest(e.message!!)
            } catch (e: PluginFlowException) {
                log.warn("job {} on {}: {} problem(s) in {}: {}", name, repo, e.problems.size, config!!["jar"].asString(), e.problems.joinToString(" | "))
                throw badRequest(e.message!!)
            }
            log.info("job {} ({}) registered on {} by {}: flow {} of {} on {}", job.name, job.id, repo, login, config!!["process"].asString(), config["jar"].asString(), signal.kind)
            return job.view()
        }
        if (kind == JobKind.BEAN) {
            try { pluginJars.acquire(config!!["jar"].asString(), config["bean"].asString(), id) } catch (e: PluginLoadException) {
                log.warn("job {} on {}: {}", name, repo, e.message)
                throw badRequest(e.message!!)
            }
        }
        val job = try { store.insert(name, kind, repo, config!!, signal, login, id = id) } catch (e: Exception) {
            if (kind == JobKind.BEAN) pluginJars.release(id)
            throw e
        }
        log.info("job {} ({}) registered on {} by {}: {} on {}", job.name, job.id, repo, login, kind.id, signal.kind)
        return job.view()
    }

    /** `GET /jobs`, `GET /jobs?repo=`. */
    fun list(repo: String?): List<JobView> = store.list(repo?.trim()?.takeIf { it.isNotEmpty() }).map { it.view() }

    /** `GET /jobs/{id}`. */
    fun get(id: String): JobView = known(id).view()

    /** `GET /jobs/{id}/runs?limit=`: the job's runs ([JobRuns]), newest first; 404 for an unknown job, 400 for a limit outside 1..[MAX_RUNS]. */
    fun runs(id: String, limit: Int?): List<JobRun> {
        val n = limit ?: DEFAULT_RUNS
        if (n !in 1..MAX_RUNS) throw badRequest("limit is 1 to $MAX_RUNS, not $n")
        known(id)
        return runs.list(id, n)
    }

    /**
     * `DELETE /jobs/{id}`. A system job (a repo's refactoring job, "Refactoring jobs") is 409: it goes with its config,
     * `PUT /repos/{owner}/{name}/refactor` `{"enabled": false}`; the diagnostics server's `delete_job` refuses the same way.
     */
    fun delete(id: String, by: JobWriter) {
        val job = known(id)
        if (job.owner == JobOwner.SYSTEM) throw ResponseStatusException(HttpStatus.CONFLICT,
            if (job.kind == JobKind.REFACTOR) "job ${job.id} is system-owned (refactor on ${job.repo}): disable refactoring with PUT /repos/${job.repo}/refactor"
            else "job ${job.id} is system-owned (${job.name} on ${job.repo}): it goes with its repo")
        writer(by, "delete a job")
        notRunning(job)
        if (job.kind == JobKind.FLOW) pluginFlows.undeploy(job)
        store.delete(id)
        if (job.kind != JobKind.PROMPT) pluginJars.release(id)
        log.info("job {} ({}) on {} deleted", job.name, job.id, job.repo)
    }

    /** `POST /jobs/{id}/execute`: the started instance's id. */
    fun execute(id: String, by: JobWriter): String {
        val job = known(id)
        writer(by, "execute a job")
        notRunning(job)
        return starter.start(job)
    }

    private fun known(id: String): Job = store.get(id) ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "no job $id")

    private fun notRunning(job: Job) {
        starter.running(job)?.let { throw ResponseStatusException(HttpStatus.CONFLICT, "job ${job.id} is running in $it") }
    }

    private fun targetRepo(repo: String?): String {
        val targets = repos.list().map { it.fullName }
        if (repo.isNullOrBlank()) throw badRequest("repo is required (owner/name): there is no default repo; the target repos are ${targets.joinToString()}")
        return targets.firstOrNull { it.equals(repo.trim(), ignoreCase = true) }
            ?: throw badRequest("${repo.trim()} is not a target repo; the target repos are ${targets.joinToString()}")
    }

    /**
     * The write check: 403 for a Smith sandbox, 503 when the engine could not tell (as `TargetRepoController` answers),
     * then 403 without a login. The trusted list is not consulted. Returns the login.
     */
    private fun writer(by: JobWriter, what: String): String {
        val login = when (by) {
            is JobWriter.Smith -> throw ResponseStatusException(HttpStatus.FORBIDDEN, "a Smith sandbox (run ${by.runId}) may not $what")
            is JobWriter.Unplaced -> throw ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, by.why)
            is JobWriter.Caller -> by.login?.trim()?.takeIf { it.isNotEmpty() }
                ?: throw ResponseStatusException(HttpStatus.FORBIDDEN, "a bearer token the engine issued names the GitHub login to $what as")
        }
        return login
    }

    private fun Job.view() = JobView(id, name, kind.id, repo, configNode(), engineJson.readTree(signal), createdBy, createdAt, nextRunAt, lastRunAt, lastInstance, owner.id)

    companion object {
        /** `GET /jobs/{id}/runs`'s default and largest `limit`. */
        const val DEFAULT_RUNS = 20
        const val MAX_RUNS = 200
        /** `POST /jobs` with kind `refactor`. */
        const val REFACTOR_IS_SYSTEM = "a refactor job is system-owned: enable it with PUT /repos/{owner}/{name}/refactor"
    }
}
