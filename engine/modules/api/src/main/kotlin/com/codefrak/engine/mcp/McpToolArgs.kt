package com.codefrak.engine.mcp

import com.codefrak.engine.github.GITHUB_LOGIN
import java.math.BigInteger
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter

/** A tool's `inputSchema` object: [props] as `properties`, [required] when given, never extra properties. */
internal fun obj(vararg props: Pair<String, Any?>, required: List<String>? = null): Map<String, Any?> = buildMap {
    put("type", "object"); put("properties", mapOf(*props))
    required?.let { put("required", it) }
    put("additionalProperties", false)
}

/** One `properties` entry: [type] and [description], with any [extra] JSON Schema keywords (`minimum`, `enum`, ...). */
internal fun prop(type: String, description: String, vararg extra: Pair<String, Any?>): Map<String, Any?> =
    linkedMapOf<String, Any?>("type" to type).apply { putAll(extra); put("description", description) }

/** An MCP argument as a whole `Long` (a whole float too, as JavaScript's `Number.isInteger` takes it), else null. */
internal fun wholeNumberOf(v: Any?): Long? = when (v) {
    is Int, is Long, is Short, is Byte -> (v as Number).toLong()
    is BigInteger -> if (v.bitLength() < 63) v.toLong() else null
    is Double, is Float -> (v as Number).toDouble().takeIf { it == Math.floor(it) && !it.isInfinite() && Math.abs(it) < 9e15 }?.toLong()
    else -> null
}

/** Whether [key] was given at all among [args]. */
internal fun given(args: Map<String, Any?>, key: String) = args[key] != null

/** [args] keys outside [allowed] as a "takes only ..." error result, else null. */
internal fun refuseOthers(tool: String, args: Map<String, Any?>, allowed: List<String>): McpToolResult? {
    val extra = args.keys.filter { it !in allowed }
    return if (extra.isEmpty()) null
        else McpToolResult.error("$tool takes ${if (allowed.isEmpty()) "no arguments" else "only ${quoted(allowed)}"}, not ${quoted(extra)}")
}

/** There is no default repo: a person's call names the target repo it is about. */
internal fun noRepo() = McpToolResult.error("\"repo\" is required (owner/name): the target repo this is about; there is no default repo")

/** There is no default repo: [args]'s "repo" resolved against [engine]'s target repos, or the refusal naming them. */
internal fun requiredTargetRepo(engine: FrakworksEngine, args: Map<String, Any?>): Pair<String?, McpToolResult?> {
    val asked = args["repo"] as? String
    val repo = asked?.let { engine.targetRepo(it) }
        ?: return null to McpToolResult.error((if (asked == null) "\"repo\" is required (owner/name): there is no default repo" else "$asked is not a target repo") +
            " (the target repos: ${engine.repos().joinToString(", ") { it.fullName }})")
    return repo to null
}

/** owner/name; neither part only dots. */
internal val REPO_FULL = Regex("""^(?!\.+/)[\w.-]+/(?!\.+$)[\w.-]+$""")

/** `args["ticket"]` is not a positive integer, as an error result. */
internal fun noTicket(args: Map<String, Any?>) =
    McpToolResult.error("not a ticket number: ${json(args["ticket"])} (a positive integer)")

/** `args["repo"]` is not an owner/name repo, as an error result. */
internal fun notRepo(args: Map<String, Any?>) =
    McpToolResult.error("not an owner/name repo: ${json(args["repo"])}")

/** A bad `ticket` or `repo` filter as an error result, else null. */
internal fun badFilter(args: Map<String, Any?>): McpToolResult? {
    if (given(args, "ticket") && FrakworksMcp.ticketOf(args["ticket"]) == null)
        return noTicket(args)
    if (given(args, "repo") && (args["repo"] as? String)?.let { REPO_FULL.matches(it) } != true)
        return notRepo(args)
    return null
}

/** The caller's login, when it is one GitHub could have; else null and the tool refuses. */
internal fun login(caller: McpCaller.Nobody) = caller.login?.takeIf { GITHUB_LOGIN.matches(it) }

/** An [OffsetDateTime] as the engine's MCP tools answer it: [DateTimeFormatter.ISO_OFFSET_DATE_TIME], or null for a null [t]. */
internal fun iso(t: OffsetDateTime?) = t?.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME)

internal fun noLogin(verb: String) =
    McpToolResult.error("no GitHub login to $verb as (a bearer token the engine issued, `Authorization: Bearer frk_...`)")
