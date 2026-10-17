package com.codefrak.engine.jobs

import com.codefrak.engine.provenLogin
import com.codefrak.engine.smith.SmithSandbox
import jakarta.servlet.http.HttpServletRequest
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import tools.jackson.databind.JsonNode

/** The `GET /jobs` row and what `POST /jobs` answers: the row with its kind, signal kind and owner (`user`|`system`) as text. */
data class JobView(
    val id: String, val name: String, val kind: String, val repo: String, val config: JsonNode, val signal: JsonNode,
    val createdBy: String?, val createdAt: java.time.OffsetDateTime, val nextRunAt: java.time.OffsetDateTime?,
    val lastRunAt: java.time.OffsetDateTime?, val lastInstance: String?, val owner: String = "user",
)

/**
 * `/jobs` (engine/README.md, "Custom jobs and the SDK") over HTTP: [JobRegistrar] does the work; this places the caller,
 * a Smith sandbox by the request's source address (503 when Docker cannot be asked), else its bearer token's login.
 */
@RestController
class JobController(private val registrar: JobRegistrar, private val sandbox: SmithSandbox) {
    private val log = LoggerFactory.getLogger(JobController::class.java)

    @PostMapping("/jobs")
    fun create(@RequestBody body: JsonNode, request: HttpServletRequest?): ResponseEntity<JobView> =
        ResponseEntity.status(HttpStatus.CREATED).body(registrar.create(body, writer(request)))

    @GetMapping("/jobs")
    fun list(@RequestParam(required = false) repo: String?): List<JobView> = registrar.list(repo)

    @GetMapping("/jobs/{id}")
    fun get(@PathVariable id: String): JobView = registrar.get(id)

    @GetMapping("/jobs/{id}/runs")
    fun runs(@PathVariable id: String, @RequestParam(required = false) limit: Int?): List<JobRun> = registrar.runs(id, limit)

    @DeleteMapping("/jobs/{id}")
    fun delete(@PathVariable id: String, request: HttpServletRequest?): ResponseEntity<Unit> {
        registrar.delete(id, writer(request))
        return ResponseEntity.noContent().build()
    }

    @PostMapping("/jobs/{id}/execute")
    fun execute(@PathVariable id: String, request: HttpServletRequest?): Map<String, String> =
        mapOf("instance" to registrar.execute(id, writer(request)))

    /** The caller as [JobRegistrar] checks it: by source address, then the login its bearer token proved. */
    private fun writer(request: HttpServletRequest?): JobWriter {
        val address = request?.remoteAddr
        if (address != null) {
            val smith = try { sandbox.runAt(address) } catch (e: Exception) {
                log.warn("could not tell whether {} is a Smith sandbox: {}", address, e.message)
                return JobWriter.Unplaced("cannot tell whether the caller is a Smith sandbox")
            }
            if (smith != null) return JobWriter.Smith(smith.runId)
        }
        return JobWriter.Caller(request?.provenLogin())
    }
}
