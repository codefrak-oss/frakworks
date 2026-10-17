package com.codefrak.engine.mcp

import com.codefrak.engine.jobs.JobRegistrar
import com.codefrak.engine.jobs.JobWriter
import org.springframework.web.server.ResponseStatusException
import tools.jackson.databind.JsonNode

/**
 * list_jobs, read_job, read_job_runs (#1653), register_job, run_job and delete_job (#1227), on [DiagnosticsMcpServer]: the `/jobs` API
 * (engine/README.md, "Custom jobs and the SDK") as tools, one per endpoint, through [JobRegistrar], the code
 * `JobController` runs, so each answers and refuses as its endpoint does. The reads answer a Smith and a placed caller
 * alike; the writes refuse a Smith and need a login with a `jobs` grant on the job's repo.
 */
class JobsTool(private val registrar: JobRegistrar) {
    companion object {
        const val LIST = "list_jobs"
        const val READ = "read_job"
        const val RUNS = "read_job_runs"
        const val REGISTER = "register_job"
        const val RUN = "run_job"
        const val DELETE = "delete_job"
        val NAMES = setOf(LIST, READ, RUNS, REGISTER, RUN, DELETE)
        /** The tools that only read. */
        val READS = setOf(LIST, READ, RUNS)

        private val ROW = "{id, name, kind, repo, config, signal, createdBy, createdAt, nextRunAt, lastRunAt, lastInstance, owner (user|system)}"
        private fun id(what: String) = linkedMapOf("type" to "string", "description" to "The job's id, $what.")
        private fun schema(properties: Map<String, Any>, required: List<String>) =
            mapOf("type" to "object", "properties" to properties, "required" to required, "additionalProperties" to false)

        val TOOLS = listOf(
            McpTool(LIST,
                "Read-only: the engine's custom jobs, oldest first (what GET /jobs, or GET /jobs?repo=, answers), each $ROW. " +
                    "`repo` (owner/name) keeps one repo's jobs.",
                schema(linkedMapOf("repo" to linkedMapOf("type" to "string", "description" to "Only this repo's jobs, owner/name.")), emptyList())),
            McpTool(READ,
                "Read-only: one custom job (what GET /jobs/{id} answers), $ROW; an error `no job <id>` for an unknown id.",
                schema(linkedMapOf("id" to id("as list_jobs gives it")), listOf("id"))),
            McpTool(RUNS,
                "Read-only: a job's run history, newest first (what GET /jobs/{id}/runs answers), each {jobId, repo, kind, instance, " +
                    "startedAt, endedAt, status (done|nothing|failed; null while it runs), outcome, summary (what it filed, or why nothing), " +
                    "scanIssue, scanEndedAt, scanOutcome, scanSummary, filed (ticket numbers), skipped ({open, oversize, overCap, malformed} counts), " +
                    "foundNothing}; the scan fields are a refactor run's, from the refactor-scan ticket it filed. `limit` is 1 to 200, default 20; " +
                    "an error `no job <id>` for an unknown id.",
                schema(linkedMapOf("id" to id("whose runs to read"),
                    "limit" to linkedMapOf("type" to "integer", "minimum" to 1, "maximum" to 200, "description" to "At most this many runs, default 20.")), listOf("id"))),
            McpTool(REGISTER,
                "Register a custom job (what POST /jobs does) and answer its row. `kind` is prompt (config {prompt}), bean " +
                    "(config {jar, bean}) or flow (config {jar, process}); a jar is a file name in /var/lib/frakworks/jobs, not a " +
                    "path, and there is no upload. `repo` is a target repo (owner/name); `signal` is {kind: execute} (started only by run_job) " +
                    "or {kind: every, every: <seconds>} or {kind: at, at: <ISO-8601 instant>} (one run at that instant, which must be in " +
                    "the future; the fired job stays as history with nextRunAt null until deleted). Kind refactor is system-owned and refused here: a repo's " +
                    "refactoring job is made by PUT /repos/{owner}/{name}/refactor. Every refusal of the endpoint is an error with its text. Needs a login " +
                    "proved by a bearer token (any login: the trusted list does not gate jobs); never a Smith.",
                schema(linkedMapOf(
                    "name" to linkedMapOf("type" to "string", "description" to "The job's name, at most 200 characters."),
                    "kind" to linkedMapOf("type" to "string", "enum" to listOf("prompt", "bean", "flow"), "description" to "The job's kind."),
                    "repo" to linkedMapOf("type" to "string", "description" to "The job's target repo, owner/name."),
                    "config" to linkedMapOf("type" to "object", "description" to "The kind's config: {prompt}, {jar, bean} or {jar, process}."),
                    "signal" to linkedMapOf("type" to "object", "description" to "What starts the job, e.g. {\"kind\": \"execute\"} or {\"kind\": \"at\", \"at\": \"2026-10-06T18:00:00Z\"}."),
                ), listOf("name", "kind", "repo", "config", "signal"))),
            McpTool(RUN,
                "Start a custom job now (what POST /jobs/{id}/execute does) and answer {instance}, the started flow. An error " +
                    "while the job already runs. Needs a named login; never a Smith.",
                schema(linkedMapOf("id" to id("to start")), listOf("id"))),
            McpTool(DELETE,
                "Delete a custom job (what DELETE /jobs/{id} does): a flow job's diagrams are undeployed and a bean job's jar " +
                    "released. An error while the job runs, and it is kept; an error for a system job (owner system, a repo's refactoring " +
                    "job: disable it with PUT /repos/{owner}/{name}/refactor). Needs a named login; never a Smith.",
                schema(linkedMapOf("id" to id("to delete")), listOf("id"))),
        )

        private val ARGS = mapOf(LIST to listOf("repo"), READ to listOf("id"), RUNS to listOf("id", "limit"), REGISTER to listOf("name", "kind", "repo", "config", "signal"),
            RUN to listOf("id"), DELETE to listOf("id"))
    }

    /** Runs [tool] for [by]: null for a read, which needs no writer. A refusal is an error result, never a throw. */
    fun call(tool: String, args: Map<String, Any?>, by: JobWriter?): McpToolResult {
        val allowed = ARGS.getValue(tool)
        val extra = args.keys.filter { it !in allowed }
        if (extra.isNotEmpty()) return McpToolResult.error("$tool takes only ${allowed.joinToString(", ") { json(it) }}, not ${extra.joinToString(", ") { json(it) }}")
        badTypes(tool, args)?.let { return McpToolResult.error(it) }
        val id = args["id"] as String?
        return try {
            when (tool) {
                LIST -> McpToolResult.of(registrar.list(args["repo"] as String?))
                READ -> McpToolResult.of(registrar.get(id!!))
                RUNS -> McpToolResult.of(registrar.runs(id!!, (args["limit"] as Number?)?.toInt()))
                REGISTER -> McpToolResult.of(registrar.create(mcpJson.valueToTree<JsonNode>(args), by!!))
                RUN -> McpToolResult.of(mapOf("instance" to registrar.execute(id!!, by!!)))
                else -> { registrar.delete(id!!, by!!); McpToolResult.of(mapOf("deleted" to id)) }
            }
        } catch (e: ResponseStatusException) {
            McpToolResult.error(e.reason ?: e.message, mapOf("status" to e.statusCode.value()))
        }
    }

    /** An argument of the wrong type, refused before anything is read, or null. */
    private fun badTypes(tool: String, args: Map<String, Any?>): String? {
        if (tool in setOf(READ, RUNS, RUN, DELETE)) {
            val id = args["id"]
            if (id !is String || id.isBlank()) return "$tool takes \"id\", a job id, not ${json(id)}"
        }
        args["limit"]?.let { if (it !is Number || it.toDouble() != Math.floor(it.toDouble())) return "\"limit\" is a whole number, not ${json(it)}" }
        for (key in listOf("repo", "name", "kind")) {
            val v = args[key]
            if (v != null && v !is String) return "\"$key\" is text, not ${json(v)}"
        }
        for (key in listOf("config", "signal")) {
            val v = args[key]
            if (v != null && v !is Map<*, *>) return "\"$key\" is an object, not ${json(v)}"
        }
        return null
    }
}
