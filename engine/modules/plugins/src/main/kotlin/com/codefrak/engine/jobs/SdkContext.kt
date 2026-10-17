package com.codefrak.engine.jobs

import com.codefrak.engine.sdk.EngineFriction
import com.codefrak.engine.sdk.EngineJevs
import com.codefrak.engine.sdk.EngineMetrics
import com.codefrak.engine.sdk.EngineTickets
import com.codefrak.engine.sdk.PluginDoctors
import com.codefrak.engine.friction.FrictionLog
import jakarta.annotation.PreDestroy
import org.springframework.context.annotation.AnnotationConfigApplicationContext
import org.springframework.stereotype.Component

/**
 * The parent context of every loaded plugin (engine/README.md, "Custom jobs and the SDK"): one
 * [AnnotationConfigApplicationContext] built at engine start that holds only the `com.codefrak.engine.sdk`
 * implementations, so a plugin's beans autowire the SDK's `Tickets`, `Friction`, `Jev`, `Metrics` and the
 * [PluginDoctors] registrar and nothing else of the engine's context by injection. It stops accidental injection
 * only: code in the same JVM reaches anything by reflection or static access (the README's security statement).
 */
@Component
class SdkContext(tickets: EngineTickets, frictionLog: FrictionLog, jevs: EngineJevs, metrics: EngineMetrics, doctors: PluginDoctors) {
    val context: AnnotationConfigApplicationContext = AnnotationConfigApplicationContext().apply {
        setDisplayName("frakworks-sdk")
        beanFactory.registerSingleton("engineTickets", tickets)
        beanFactory.registerSingleton("engineFriction", EngineFriction(frictionLog, null, null, null))
        beanFactory.registerSingleton("engineJev", jevs.forJob(null))
        beanFactory.registerSingleton("engineMetrics", metrics)
        beanFactory.registerSingleton("pluginDoctors", doctors)
        refresh()
    }

    @PreDestroy fun close() = context.close()
}
