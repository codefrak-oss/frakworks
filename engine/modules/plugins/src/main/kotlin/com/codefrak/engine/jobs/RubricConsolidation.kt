package com.codefrak.engine.jobs

import com.codefrak.engine.engineJson
import com.codefrak.engine.github.JudgeTaught
import com.codefrak.engine.repos.TargetRepos
import org.slf4j.LoggerFactory
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Component

/**
 * The weekly judge-rubric consolidation job (engine/README.md, "Rubric consolidation"; #2439): a system-owned prompt job
 * on every target repo the judge has been taught in, signal every [EVERY] seconds. A teach that committed under a
 * rubric's `## Decisions` (`JudgeTeachController`, [JudgeTaught]) registers it on its repo when there is no job of
 * [NAME] there yet, so nothing is set by hand and nothing is registered twice; the job row itself is the record. Its
 * run reads the rubric's `## Decisions` (lines the judge teach wrote, `RubricTeacher`) and opens one PR moving each into
 * Allow, Escalate or Never, leaving `## Decisions` empty; with no line it ends `nothing` and opens no PR. Deleted only
 * with its repo (a system job: `DELETE /jobs/{id}` is 409).
 */
@Component
class RubricConsolidation(private val store: JobStore, private val repos: TargetRepos) {
    private val log = LoggerFactory.getLogger(RubricConsolidation::class.java)

    @EventListener
    fun onTaught(event: JudgeTaught) = runCatching { register(event.repo) }
        .onFailure { log.warn("rubric consolidation job not registered on {}: {}", event.repo, it.message) }.getOrNull()

    /** The job on [repo], registered now or found; null when [repo] is no target repo. */
    fun register(repo: String): Job? {
        val target = repos.list().firstOrNull { it.fullName.equals(repo, ignoreCase = true) }?.fullName ?: return null
        store.list(target).firstOrNull { it.name == NAME }?.let { return it }
        val job = store.insert(NAME, JobKind.PROMPT, target, engineJson.valueToTree(mapOf("prompt" to PROMPT)), Signal.Every(EVERY),
            null, owner = JobOwner.SYSTEM)
        log.info("job {} ({}) registered on {}: prompt every {}s", job.name, job.id, target, EVERY)
        return job
    }

    companion object {
        const val NAME = "judge rubric consolidation"
        const val EVERY = 604_800L
        val PROMPT = """
            Consolidate the SSH judge's rubric in this repo (the Markdown file with the sections `## Principles`, `## Allow`,
            `## Escalate`, `## Never` and `## Decisions`; find it by those headings).

            1. Read the lines under `## Decisions`. Each is a decision a human taught the judge:
               `- YYYY-MM-DD allow|deny `<command>` on <host> — <why>; judge: <verdict> (<confidence>), call <callId>[; note]`.
            2. If `## Decisions` holds no line, change nothing, open no PR, and end with outcome `nothing` and the summary
               `filed nothing: ## Decisions is empty`.
            3. Otherwise, on one new branch, move every line out of `## Decisions` into the section it belongs in, rewritten
               as a general rule in that section's style: an `allow` the rubric should keep allowing goes to `## Allow`; an
               `allow` that should still be asked each time goes to `## Escalate`; a `deny` is a hard rule in `## Never`.
               Merge a line into an existing rule it repeats rather than adding a duplicate. Leave the `## Decisions`
               heading with nothing under it, and change nothing else in the file.
            4. Commit, push the branch and open exactly one PR on this repo listing each moved line and the section it
               went to. Do not merge it. End with outcome `done` and the PR's link.
        """.trimIndent()
    }
}
