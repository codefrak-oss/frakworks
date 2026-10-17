package com.codefrak.engine.firstmate.push

import com.codefrak.engine.CallerIdentity
import com.codefrak.engine.CallerRefusal
import com.codefrak.engine.badRequest
import com.codefrak.engine.errorResponse
import com.codefrak.engine.firstmate.FirstMateSessionController
import com.codefrak.engine.provenLogin
import com.codefrak.engine.provenTokenId
import com.codefrak.engine.repos.EngineTokens
import jakarta.servlet.http.HttpServletRequest
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RestController

/**
 * The phone registers for pushes (engine/README.md, "Push notifications"): `PUT /first-mate/push/device {deviceToken,
 * platform?, environment?}` stores or updates its row (201 new, 200 updated), `DELETE /first-mate/push/device
 * {deviceToken}` removes it (204; 200 when there was none of the caller's). Only a `first-mate` token is accepted, as on
 * `/first-mate/sessions` (401 without one, 403 with another); a Smith sandbox is 403 whatever it sends.
 */
@RestController
class PushDeviceController(private val devices: PushDevices, private val tokens: EngineTokens, private val callers: CallerIdentity) {
    private val log = LoggerFactory.getLogger(PushDeviceController::class.java)

    companion object {
        /** An APNs device token is 32 bytes as hex today; Apple says not to rely on the length, so allow up to 200. */
        private val DEVICE_TOKEN = Regex("^[0-9a-fA-F]{16,200}$")
    }

    private fun caller(request: HttpServletRequest, what: String): Pair<Pair<String, String>?, ResponseEntity<Any>?> {
        CallerRefusal.smithRefusal(callers, request, log, what)?.let { (s, m) -> return null to errorResponse(s, m) }
        val login = request.provenLogin(); val id = request.provenTokenId()
        if (login == null || id == null) return null to errorResponse(HttpStatus.UNAUTHORIZED, FirstMateSessionController.NEEDS_TOKEN)
        if (tokens.get(id)?.purpose != EngineTokens.FIRST_MATE) return null to errorResponse(HttpStatus.FORBIDDEN, FirstMateSessionController.WRONG_TOKEN)
        return (login to id) to null
    }

    private fun deviceToken(body: Map<String, Any?>): String =
        (body["deviceToken"] as? String)?.trim()?.takeIf { DEVICE_TOKEN.matches(it) }?.lowercase()
            ?: throw badRequest("deviceToken is the APNs device token as hex")

    @PutMapping("/first-mate/push/device")
    fun register(@RequestBody body: Map<String, Any?>, request: HttpServletRequest): ResponseEntity<Any> {
        val (c, refusal) = caller(request, "register a push device"); c ?: return refusal!!
        val device = deviceToken(body)
        val platform = (body["platform"] ?: PushDevices.IOS).let { it as? String }?.takeIf { it == PushDevices.IOS }
            ?: throw badRequest("platform is ${PushDevices.IOS}")
        val environment = (body["environment"] ?: PushDevices.PRODUCTION).let { it as? String }?.takeIf { it in PushDevices.ENVIRONMENTS }
            ?: throw badRequest("environment is ${PushDevices.SANDBOX} or ${PushDevices.PRODUCTION}")
        val created = devices.register(device, c.first, c.second, platform, environment)
        if (created) log.info("push device {}… registered for @{} ({})", device.take(8), c.first, environment)
        val row = devices.get(device)!!
        val view = linkedMapOf<String, Any?>("deviceToken" to row.deviceToken, "login" to row.login, "platform" to row.platform,
            "environment" to row.environment, "createdAt" to row.createdAt, "updatedAt" to row.updatedAt)
        return ResponseEntity.status(if (created) HttpStatus.CREATED else HttpStatus.OK).body(view)
    }

    @DeleteMapping("/first-mate/push/device")
    fun unregister(@RequestBody body: Map<String, Any?>, request: HttpServletRequest): ResponseEntity<Any> {
        val (c, refusal) = caller(request, "unregister a push device"); c ?: return refusal!!
        val device = deviceToken(body)
        if (!devices.delete(device, c.first)) return ResponseEntity.ok().build()
        log.info("push device {}… unregistered by @{}", device.take(8), c.first)
        return ResponseEntity.noContent().build()
    }
}
