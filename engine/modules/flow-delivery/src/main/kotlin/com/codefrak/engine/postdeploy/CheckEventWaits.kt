package com.codefrak.engine.postdeploy

import com.codefrak.engine.engineJson
import com.codefrak.engine.events.EngineEvents
import com.codefrak.engine.smith.Vars
import org.operaton.bpm.engine.MismatchingMessageCorrelationException
import org.operaton.bpm.engine.OptimisticLockingException
import org.operaton.bpm.engine.RuntimeService
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import tools.jackson.module.kotlin.readValue

/** The message bpmn/post-deploy-check.bpmn waits at after a `not_yet` with an event hint (`receive-check-event`). */
const val CHECK_EVENT_MESSAGE = "check event"

/**
 * Wakes the post-deploy-check flows waiting on a bus frame (`receive-check-event`): a reader of every frame the engine
 * delivers ([EngineEvents.onDeliver]: one published here and one relayed from another replica alike). A flow of the
 * frame's repo whose `checkWakeEvent` (`<type>[:<field>=<value>]`, [WakeHint.EVENT]) [matches] the frame is
 * correlated. The listener hands each frame to a virtual thread of its own, as the delivering thread must not block;
 * [wake] does the work, and a test calls it directly. Two replicas racing on one flow correlate it once.
 */
@Component
class CheckEventWaits(private val runtime: RuntimeService, events: EngineEvents? = null) {
    private val log = LoggerFactory.getLogger(CheckEventWaits::class.java)

    init {
        events?.onDeliver { repo, frame ->
            if (type(frame) != null) Thread.ofVirtual().name("check-event-waits").start { wake(repo, frame) }
        }
    }

    /** The flows [frame] (an SSE frame, `event: <type>\ndata: <json>`) for [repo] woke, by process instance id. */
    fun wake(repo: String, frame: String): List<String> {
        val type = type(frame) ?: return emptyList()
        val waiting = runtime.createExecutionQuery().messageEventSubscriptionName(CHECK_EVENT_MESSAGE).list()
        if (waiting.isEmpty()) return emptyList()
        val data = data(frame)
        return waiting.mapNotNull { execution ->
            runCatching {
                val vars = runtime.getVariables(execution.id, listOf(Vars.REPO, Vars.CHECK_WAKE_EVENT))
                if (!(vars[Vars.REPO] as? String).equals(repo, ignoreCase = true)) return@runCatching null
                val wanted = vars[Vars.CHECK_WAKE_EVENT] as? String ?: return@runCatching null
                if (!matches(wanted, type, data)) return@runCatching null
                try {
                    runtime.createMessageCorrelation(CHECK_EVENT_MESSAGE).processInstanceId(execution.processInstanceId).correlate()
                } catch (e: MismatchingMessageCorrelationException) {
                    return@runCatching null
                } catch (e: OptimisticLockingException) {
                    return@runCatching null
                }
                log.info("{}: a {} frame woke the check of {} ({})", repo, type, execution.processInstanceId, wanted)
                execution.processInstanceId
            }.onFailure { log.warn("check event wait {}: {}", execution.processInstanceId, it.message) }.getOrNull()
        }
    }

    companion object {
        private val EVENT_LINE = Regex("""^event:\s*(\S+)\s*$""", RegexOption.MULTILINE)
        private val DATA_LINE = Regex("""^data:\s?(.*)$""", RegexOption.MULTILINE)

        /** The frame's `event:` type; null for a frame with none (a `: ping` comment). */
        fun type(frame: String): String? = EVENT_LINE.find(frame)?.groupValues?.get(1)

        /** The frame's `data:` as a JSON object; empty when it has none or it is not one. */
        fun data(frame: String): Map<String, Any?> {
            val text = DATA_LINE.findAll(frame).joinToString("\n") { it.groupValues[1] }
            return runCatching { engineJson.readValue<Map<String, Any?>>(text) }.getOrDefault(emptyMap())
        }

        /**
         * Whether [hint] (`<type>` or `<type>:<field>=<value>`) takes a frame of [type] carrying [data]: the type is
         * equal, and the field (a dotted path into nested objects) is there with that value as text.
         */
        fun matches(hint: String, type: String, data: Map<String, Any?>): Boolean {
            val wantedType = hint.substringBefore(':')
            if (wantedType != type) return false
            val condition = hint.substringAfter(':', "").takeIf { it.isNotEmpty() } ?: return true
            val field = condition.substringBefore('='); val value = condition.substringAfter('=')
            var at: Any? = data
            for (key in field.split('.')) at = (at as? Map<*, *>)?.get(key) ?: return false
            return at.toString() == value
        }
    }
}
