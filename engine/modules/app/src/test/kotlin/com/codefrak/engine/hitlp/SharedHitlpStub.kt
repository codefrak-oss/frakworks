package com.codefrak.engine.hitlp

import org.springframework.context.ApplicationContextInitializer
import org.springframework.context.ConfigurableApplicationContext
import org.springframework.beans.factory.config.BeanDefinitionCustomizer
import org.springframework.context.support.GenericApplicationContext
import org.springframework.core.env.MapPropertySource

/**
 * Every test's engine files its ask waits on a HITLP server (#2462), so each test context gets one: [STUB], one
 * [StubHitlpServer] for the JVM, set as the test-only `test.hitlp.url` (token [StubHitlpServer.TOKEN]) just above the
 * application's config files, below a class's own `@DynamicPropertySource` (its own stub), and written into the engine's
 * settings row by a [HitlpStubSeeder] registered on the context (#2500: the server is a setting, never a property).
 * Registered in META-INF/spring.factories. Nothing decides its tasks unless a test calls [StubHitlpServer.decide].
 */
class SharedHitlpStub : ApplicationContextInitializer<ConfigurableApplicationContext> {
    companion object {
        val STUB: StubHitlpServer by lazy { StubHitlpServer() }
        const val SOURCE = "sharedHitlpStub"
        const val URL_PROPERTY = "test.hitlp.url"
        const val TOKEN_PROPERTY = "test.hitlp.token"
    }

    override fun initialize(context: ConfigurableApplicationContext) {
        val sources = context.environment.propertySources
        if (sources.contains(SOURCE)) return
        val source = MapPropertySource(SOURCE, mapOf(URL_PROPERTY to STUB.url, TOKEN_PROPERTY to StubHitlpServer.TOKEN))
        val config = sources.firstOrNull { it.name.startsWith("Config resource") || it.name.startsWith("applicationConfig") }
        if (config != null) sources.addBefore(config.name, source) else sources.addLast(source)
        (context as GenericApplicationContext).registerBean(HitlpStubSeeder::class.java, *emptyArray<BeanDefinitionCustomizer>())
    }
}
