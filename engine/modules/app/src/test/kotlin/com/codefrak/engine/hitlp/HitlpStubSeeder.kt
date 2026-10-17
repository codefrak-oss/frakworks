package com.codefrak.engine.hitlp

import org.springframework.beans.factory.ObjectProvider
import org.springframework.beans.factory.SmartInitializingSingleton
import org.springframework.core.env.Environment

/**
 * Writes the test's HITLP stub into the engine's settings row (#2500: the server is a setting, not a property) from
 * `test.hitlp.url` and `test.hitlp.token`, which [SharedHitlpStub] and [StubHitlpServer.register] set. Runs once every
 * singleton is made, so after Flyway and before `ApplicationReadyEvent` starts the lease stream
 * ([HitlpTaskStream.atStartup]), which then finds the stub. A blank `test.hitlp.url` clears the row. A context with no
 * [HitlpServerSettings] (the bean-less application of `UnknownEnginePropertiesTest`, which the initializer reaches too)
 * is left alone.
 */
class HitlpStubSeeder(private val settings: ObjectProvider<HitlpServerSettings>, private val environment: Environment) : SmartInitializingSingleton {
    override fun afterSingletonsInstantiated() { settings.ifAvailable?.let { seed(it) } }

    fun seed() = seed(settings.`object`)

    private fun seed(settings: HitlpServerSettings) {
        val url = environment.getProperty("test.hitlp.url").orEmpty()
        val token = environment.getProperty("test.hitlp.token").orEmpty()
        settings.set(mcpUrl = HitlpServerSettings.Field.Set(url.takeIf { it.isNotBlank() }),
            requesterToken = HitlpServerSettings.Field.Set(token.takeIf { it.isNotBlank() }))
    }
}
