package com.codefrak.engine.deploy

import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Component

/**
 * The engine's own deploy (#100008; engine/README.md, "Deploys"): at `ApplicationReadyEvent` it posts a deploy event
 * for the repo it was built from (`engine.build.repo`) with its build commit (`engine.build.commit`), once per commit
 * ([DeployEvents.postOnce]: a restart of the same image adds no row). Nothing is posted when either property is
 * empty (a local build) or the build repo is not a target repo.
 */
@Component
class EngineDeployAtStart(
    private val deploys: DeployEvents,
    @Value("\${engine.build.repo:}") buildRepo: String, @Value("\${engine.build.commit:}") buildCommit: String,
) {
    private val log = LoggerFactory.getLogger(EngineDeployAtStart::class.java)
    private val repo = buildRepo.trim().takeIf { it.isNotEmpty() }
    private val commit = buildCommit.trim().lowercase().takeIf { DeployEvents.SHA.matches(it) }

    companion object { const val SOURCE = "engine start" }

    @EventListener(ApplicationReadyEvent::class)
    fun started() {
        val repo = this.repo
        val commit = this.commit
        if (repo == null || commit == null) { log.info("engine deploy at start: no build repo or commit, nothing posted"); return }
        val target = runCatching { deploys.repo(repo) }.getOrNull()
            ?: run { log.info("engine deploy at start: {} is not a target repo, nothing posted", repo); return }
        try {
            val row = deploys.postOnce(target, commit, SOURCE)
            if (row != null) log.info("engine deploy at start: {} {} posted", target, commit) else log.info("engine deploy at start: {} {} already recorded", target, commit)
        } catch (e: Exception) {
            log.warn("engine deploy at start: {} {} not posted: {}", target, commit, e.message)
        }
    }
}
