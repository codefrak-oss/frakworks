package com.codefrak.engine

import org.junit.jupiter.api.ClassDescriptor
import org.junit.jupiter.api.ClassOrderer
import org.junit.jupiter.api.ClassOrdererContext
import org.springframework.test.context.BootstrapUtils
import org.springframework.test.context.MergedContextConfiguration
import org.springframework.test.context.TestContextAnnotationUtils

/**
 * Runs the test classes that share a Spring context back to back (junit-platform.properties).
 *
 * Spring caches a context per [MergedContextConfiguration], at most a few at a time (the cache
 * size in build.gradle.kts bounds the heap). In the default order the classes of one context are
 * spread across packages, so a context the cache evicted is built again for the next class that
 * needs it: about 34 builds for 18 contexts. Grouped, each context is built once, and the cache
 * only has to hold the one in use. The groups keep the order of their first class, and classes
 * without a Spring context run first.
 */
class ContextGroupedClassOrderer : ClassOrderer {
    override fun orderClasses(context: ClassOrdererContext) {
        val keys = context.classDescriptors.associateWith { contextKey(it) }
        val firstSeen = LinkedHashMap<Any, Int>()
        context.classDescriptors.forEach { firstSeen.putIfAbsent(keys.getValue(it), firstSeen.size) }
        context.classDescriptors.sortWith(compareBy { firstSeen.getValue(keys.getValue(it)) })
    }

    /** Spring's own cache key for the class, or a shared marker for a class with no context. */
    private fun contextKey(descriptor: ClassDescriptor): Any {
        val testClass = descriptor.testClass
        if (!TestContextAnnotationUtils.hasAnnotation(testClass, org.springframework.test.context.BootstrapWith::class.java)) return NoContext
        return runCatching {
            BootstrapUtils.resolveTestContextBootstrapper(testClass).buildMergedContextConfiguration()
        }.getOrElse { testClass }
    }

    private object NoContext
}
