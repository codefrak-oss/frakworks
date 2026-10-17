package com.codefrak.engine

import org.springframework.beans.factory.ObjectProvider
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.info.BuildProperties
import org.springframework.stereotype.Component
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RestController

/**
 * `version`: the engine's build version, null when the build did not record one; `commit`: the git commit the
 * image was built from (`engine.build.commit`, the image's `ENGINE_BUILD_COMMIT`), null for a build that was not
 * given one.
 */
data class VersionResponse(val version: String?, val commit: String?)

/**
 * Which engine this is: Spring Boot's [BuildProperties] version and the `engine.build.commit` property, read once
 * here for `GET /version` ([VersionController]) and the frakworks server's read_version ([com.codefrak.engine.mcp.VersionTool]).
 */
@Component
class EngineBuild(
    private val build: ObjectProvider<BuildProperties>,
    @Value("\${engine.build.commit:}") commit: String,
) {
    private val commit: String? = commit.trim().ifEmpty { null }

    fun version(): VersionResponse = VersionResponse(build.ifAvailable?.version, commit)
}

/** Unauthenticated: which engine this is and which commit it was built from. */
@RestController
class VersionController(private val build: EngineBuild) {
    @GetMapping("/version")
    fun version(): VersionResponse = build.version()
}
