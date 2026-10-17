package com.codefrak.engine.mcp

import com.codefrak.engine.accounts.ClaudeModels
import com.codefrak.engine.intake.AskRequest
import com.codefrak.engine.intake.IntakeStarted
import com.codefrak.engine.tier.TierTable
import org.springframework.http.HttpStatus

/**
 * submit_ask, on [FrakworksMcp] for both roles, built like [TicketWriteTools]/[FrictionNoteTool]: First mate's
 * `({ ask, tier?, authorize?, repo, context? })` files an intake as the caller's login; a Smith's `({ ask })` files
 * one as its run, with no submitter and never authorized, so it waits for a human's `authorized` label.
 */
class AskTool(private val engine: FrakworksEngine, private val tiers: TierTable) {
    companion object {
        const val MAX_ASK_CHARS = 20000

        /** First mate's schema. */
        val TOOL = McpTool("submit_ask",
            "File a ticket: start an engine intake with this ask (POST /intake). A refining Smith turns it " +
                "into a ticket. The ask is the user's own words verbatim, unchanged: never your paraphrase, " +
                "summary or expansion of them. Pass `tier` only if the user named one. " +
                "Pass `authorize: true` to authorize the intake as the user now (refused if they may not). " +
                "Always pass `repo` (owner/name), the target repo to file in: there is no default repo. " +
                "The answer's `ticket` is the ticket's number, known at once: reply with one line naming it as a link, " +
                "[#<ticket>](frakworks://ticket/<owner>/<name>/<ticket>) from `repo` and `ticket`, saying the refiner will " +
                "fill it in (the number changes only if it files in another repo), and stop: no intake id, no status call or polling.",
            obj(
                "ask" to prop("string", "The user's own words verbatim, unchanged; never the caller's paraphrase or expansion."),
                "tier" to prop("integer", "Only when the user named a tier (1-10, the engine's tier map: GET /tiers).", "minimum" to 1, "maximum" to 10),
                "authorize" to prop("boolean", "Authorize the intake at filing as the user (POST /intake's `authorize`)."),
                "repo" to prop("string", "The target repo (owner/name) to file the intake and its ticket in (POST /intake's `repo`)."),
                "context" to prop("string", "Your concise, factual summary of the conversation that led to the ask (the user's earlier turns, " +
                    "your findings, files and tickets discussed), filed after the quote as the session's, not the user's words. " +
                    "Include what the Smith can't see: screenshots described, where in the app the user was looking, options you " +
                    "raised and what they would cost, and anything the user said earlier that bears on the ask. Facts, not " +
                    "recommendations. Optional."),
                required = listOf("ask", "repo"),
            ))

        /** A Smith's schema: no `repo`, a run already has its own. */
        val SMITH_TOOL = McpTool("submit_ask",
            "File an ask as this Smith run (POST /intake): the engine records your run and ticket on it, and it waits " +
                "for a human to label it `authorized` before a refining Smith turns it into a ticket.",
            obj("ask" to prop("string", "The ask: what is needed and why, with the ticket it came from."), required = listOf("ask")))

        fun tierHeader(tier: Int, model: String, effort: String) = "Tier: $tier ($model $effort) — prescribed by the user; do not re-judge"
    }

    /** First mate's submit_ask: files as the caller's login, optionally authorized and tiered. */
    fun call(args: Map<String, Any?>, caller: McpCaller.Nobody): McpToolResult {
        val ask = (args["ask"] as? String)?.trim().orEmpty()
        if (ask.isEmpty()) return McpToolResult.error("an ask needs some text")
        var tier: Int? = null
        var text = ask
        if (given(args, "tier")) {
            val t = FrakworksMcp.ticketOf(args["tier"])?.takeIf { it <= 10 }?.toInt()
            if (t == null || !tiers.maps(t)) return McpToolResult.error("tier ${json(args["tier"])} is not a tier of the engine's map (1-10)")
            val resolved = tiers.resolve(t)
            val model = ClaudeModels.shortName(resolved.model) ?: resolved.model
            tier = t
            text = "${tierHeader(t, model, resolved.effort)}\n\n$ask"
        }
        if (text.length > MAX_ASK_CHARS) return McpToolResult.error("an ask is at most $MAX_ASK_CHARS characters")
        if (!given(args, "repo")) return noRepo()
        if ((args["repo"] as? String)?.let { FrakworksMcp.REPO_FULL.matches(it) } != true)
            return notRepo(args)
        val started = engine.submit(AskRequest(text, submittedBy = caller.login, authorize = args["authorize"] as? Boolean,
            repo = args["repo"] as? String, context = args["context"] as? String), caller.request)
        return McpToolResult.of(linkedMapOf("intakeId" to started.intakeId, "status" to HttpStatus.ACCEPTED.value(), "tier" to tier, "ask" to ask,
            "repo" to started.repo, "ticket" to started.ticket, "ticketUrl" to started.ticketUrl))
    }

    /** A Smith's submit_ask: the ask alone, as its run (the engine finds it by address), never as a person and never authorized. */
    fun call(args: Map<String, Any?>, caller: McpCaller.Smith): McpToolResult {
        val extra = args.keys.filter { it != "ask" }
        if (extra.isNotEmpty()) return McpToolResult.error("a Smith's submit_ask takes only \"ask\", not ${quoted(extra)}")
        val ask = (args["ask"] as? String)?.trim().orEmpty()
        if (ask.isEmpty()) return McpToolResult.error("an ask needs some text")
        if (ask.length > MAX_ASK_CHARS) return McpToolResult.error("an ask is at most $MAX_ASK_CHARS characters")
        // No repo: the engine files in the run's own repo when it is a target repo, so discovered work stays where it was found.
        val started = engine.submit(AskRequest(ask, authorize = false), caller.request)
        return McpToolResult.of(linkedMapOf("intakeId" to started.intakeId, "status" to HttpStatus.ACCEPTED.value(), "ask" to ask,
            "repo" to started.repo, "ticket" to started.ticket, "ticketUrl" to started.ticketUrl))
    }
}
