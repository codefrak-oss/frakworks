package com.codefrak.engine.events

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Component

/**
 * Carries the event bus's frames between engine replicas (engine/README.md, "Replicas and the lease"): [EngineEvents]
 * offers every frame to its own subscribers and [send]s it, and hands what [listen] receives from the other replicas
 * to its own subscribers, so an SSE client connected to any replica sees an event committed on any of them.
 * [PostgresEventRelay] is the engine's; [NoopEventRelay] is the tests' (H2 has no NOTIFY), picked by
 * `engine.events.relay=noop` in src/test/resources/config, never by a running engine.
 */
interface EventRelay {
    /** Sends a frame this instance published, for `repo`, to the other replicas. Never blocks or throws. */
    fun send(repo: String, frame: String)

    /** Where a frame another replica published goes: `handler(repo, frame)`. */
    fun listen(handler: (String, String) -> Unit)

    /** Whether frames from the other replicas arrive now (the readiness check). */
    fun listening(): Boolean
}

/** The tests' relay: one engine, nothing to carry. */
@Component
@ConditionalOnProperty("engine.events.relay", havingValue = "noop")
class NoopEventRelay : EventRelay {
    override fun send(repo: String, frame: String) {}
    override fun listen(handler: (String, String) -> Unit) {}
    override fun listening() = true
}
