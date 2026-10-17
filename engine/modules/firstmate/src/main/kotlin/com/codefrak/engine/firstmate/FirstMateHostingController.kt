package com.codefrak.engine.firstmate

import com.codefrak.engine.badRequest
import com.codefrak.engine.events.EngineEvent
import com.codefrak.engine.events.EngineEvents
import com.codefrak.engine.smith.HostingModel
import com.codefrak.engine.smith.SmithHosting
import org.slf4j.LoggerFactory
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RestController

/**
 * `GET`/`PUT /settings/first-mate-hosting {"hosting": "local"|"kubernetes"}` (engine/README.md, "First-mate sessions"),
 * the shape of `SmithHostingController`: unauthenticated like the rest of the engine's API, a 400 naming `hosting`
 * for any other key, a non-string or another value. `kubernetes` is a 400 saying why while `smith.hosting` is not
 * `kubernetes` or a Kubernetes setting is blank ([com.codefrak.engine.smith.SmithHostingSettings.missing]): a First
 * mate pod runs on those settings. `local` is always accepted. A successful PUT publishes
 * `EngineEvent.Setting("firstmate.hosting", value)`.
 */
@RestController
class FirstMateHostingController(private val store: FirstMateHosting, private val smith: SmithHosting, private val events: EngineEvents) {
    private val log = LoggerFactory.getLogger(FirstMateHostingController::class.java)

    @GetMapping("/settings/first-mate-hosting")
    fun get(): Map<String, String> = mapOf(KEY to store.get())

    @PutMapping("/settings/first-mate-hosting")
    fun set(@RequestBody body: Map<String, Any?>): Map<String, String> {
        if (body.keys != setOf(KEY)) throw badRequest("the body has $KEY")
        val value = (body[KEY] as? String)?.trim() ?: throw badRequest("$KEY is a string")
        if (value !in FirstMateHosting.VALUES) throw badRequest("$KEY is ${FirstMateHosting.LOCAL} or ${FirstMateHosting.KUBERNETES}")
        if (value == FirstMateHosting.KUBERNETES) {
            val s = smith.get()
            if (s.hosting != HostingModel.KUBERNETES.value)
                throw badRequest("$KEY ${FirstMateHosting.KUBERNETES} needs ${SmithHosting.HOSTING} set to kubernetes first (PUT /settings/smith-hosting)")
            if (s.missing().isNotEmpty())
                throw badRequest("$KEY ${FirstMateHosting.KUBERNETES} needs ${s.missing().joinToString()} set first (PUT /settings/smith-hosting)")
        }
        store.set(value)
        events.publish(EngineEvent.Setting(FirstMateHosting.HOSTING, value))
        log.info("{} set to {}", FirstMateHosting.HOSTING, value)
        return mapOf(KEY to value)
    }

    private companion object {
        const val KEY = "hosting"
    }
}
