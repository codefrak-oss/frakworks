package com.codefrak.engine.firstmate.push

import com.codefrak.engine.CallerIdentity
import com.codefrak.engine.CallerRefusal
import com.codefrak.engine.badRequest
import com.codefrak.engine.errorResponse
import com.codefrak.engine.provenLogin
import jakarta.servlet.http.HttpServletRequest
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RestController

/**
 * The APNs credentials (engine/README.md, "Push notifications"): `PUT /push/apns {p8, keyId, teamId, bundleId,
 * environment}` seals and stores them (204; 503 while `ENGINE_SECRETS_KEY` is not set, as `PUT
 * /repos/{owner}/{name}/token`; 400 for a key that is no EC private key), `GET /push/apns` answers `{set, setAt}` and
 * never the key, `DELETE /push/apns` clears them (204; 200 when none were set). A Smith sandbox is 403.
 */
@RestController
class ApnsSettingsController(private val settings: ApnsSettings, private val callers: CallerIdentity) {
    private val log = LoggerFactory.getLogger(ApnsSettingsController::class.java)

    companion object {
        const val NO_KEY = "ENGINE_SECRETS_KEY is not set, so the engine cannot store the APNs key (engine/README.md, \"AI accounts\")"
        private val APPLE_ID = Regex("^[A-Z0-9]{10}$")
        private val BUNDLE_ID = Regex("^[A-Za-z0-9.-]{1,155}$")
    }

    private fun refuse(request: HttpServletRequest, what: String): ResponseEntity<Any>? =
        CallerRefusal.smithRefusal(callers, request, log, what)?.let { (s, m) -> errorResponse(s, m) }

    @GetMapping("/push/apns")
    fun get(request: HttpServletRequest): ResponseEntity<Any> {
        refuse(request, "read the APNs settings")?.let { return it }
        val at = settings.setAt()
        return ResponseEntity.ok(linkedMapOf("set" to (at != null), "setAt" to at))
    }

    @PutMapping("/push/apns")
    fun set(@RequestBody body: Map<String, Any?>, request: HttpServletRequest): ResponseEntity<Any> {
        refuse(request, "set the APNs key")?.let { return it }
        if (!settings.keyConfigured) return errorResponse(HttpStatus.SERVICE_UNAVAILABLE, NO_KEY)
        fun text(name: String) = (body[name] as? String)?.trim()?.takeIf { it.isNotEmpty() }
        val p8 = text("p8") ?: throw badRequest("p8 is the APNs auth key's .p8 file, PEM text")
        try { ApnsJwt.privateKey(p8) } catch (e: Exception) { throw badRequest("p8 is not an EC private key in PEM (PKCS#8): ${e.javaClass.simpleName}") }
        val keyId = text("keyId")?.takeIf { APPLE_ID.matches(it) } ?: throw badRequest("keyId is the key's 10-character Key ID")
        val teamId = text("teamId")?.takeIf { APPLE_ID.matches(it) } ?: throw badRequest("teamId is the 10-character Apple Developer Team ID")
        val bundleId = text("bundleId")?.takeIf { BUNDLE_ID.matches(it) } ?: throw badRequest("bundleId is the app's bundle id, the APNs topic")
        val environment = text("environment")?.takeIf { it in PushDevices.ENVIRONMENTS }
            ?: throw badRequest("environment is ${PushDevices.SANDBOX} or ${PushDevices.PRODUCTION}")
        settings.set(ApnsCredentials(p8, keyId, teamId, bundleId, environment))
        log.info("APNs credentials set (key {}, team {}, {}, {}) by @{}", keyId, teamId, bundleId, environment, request.provenLogin() ?: "an unproven caller")
        return ResponseEntity.noContent().build()
    }

    @DeleteMapping("/push/apns")
    fun clear(request: HttpServletRequest): ResponseEntity<Any> {
        refuse(request, "clear the APNs key")?.let { return it }
        if (!settings.clear()) return ResponseEntity.ok().build()
        log.info("APNs credentials cleared by @{}", request.provenLogin() ?: "an unproven caller")
        return ResponseEntity.noContent().build()
    }
}
