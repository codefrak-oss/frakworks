package com.codefrak.engine

import com.codefrak.engine.github.GitHubIssueApi
import com.codefrak.guardfixture.ViolatingThing
import com.tngtech.archunit.core.domain.JavaClass
import com.tngtech.archunit.core.importer.ClassFileImporter
import com.tngtech.archunit.core.importer.ImportOption
import com.tngtech.archunit.lang.ArchCondition
import com.tngtech.archunit.lang.ArchRule
import com.tngtech.archunit.lang.ConditionEvents
import com.tngtech.archunit.lang.SimpleConditionEvent
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes
import org.junit.jupiter.api.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Tickets are store work items (#100031, engine/docs/work-items.md): over the compiled classes, no class depends on the
 * GitHub issue client ([GitHubIssueApi]) but the github module's own and the contract's (github-api, where the interface and its default methods live; both found by load path, [LayerRule.moduleOf]) and
 * `GitHubWorkItemSource`, which imports an issue as an item and publishes an item's opt-in copy.
 */
class GitHubIssuesGuardTest {
    @Test
    fun `no engine class outside the github module and GitHubWorkItemSource uses the GitHub issue client`() {
        val classes = ClassFileImporter().withImportOption(ImportOption.DoNotIncludeTests())
            // testFixtures are test code too, though DoNotIncludeTests does not know their path
            .withImportOption { !it.contains("/testFixtures/") && !it.contains("-test-fixtures") }
            .importPackages("com.codefrak.engine")
        GitHubIssuesGuard.rule(LayerRule::moduleOf).check(classes)
    }

    @Test
    fun `the guard fails for a class that uses the GitHub issue client`() {
        val classes = ClassFileImporter().importClasses(ViolatingThing::class.java)
        val error = assertFailsWith<AssertionError> { GitHubIssuesGuard.rule { "app" }.check(classes) }
        assertTrue("${ViolatingThing::class.java.name} uses ${GitHubIssueApi::class.java.name}" in error.message.orEmpty(), error.message)
    }
}

/** The guard as an ArchUnit rule, with the class-to-module mapping passed in so the test can place its fixture. */
object GitHubIssuesGuard {
    private val GUARDED = setOf(GitHubIssueApi::class.java.name)
    /** Import and opt-in publish. */
    private val ALLOWED = listOf("com.codefrak.engine.workitems.GitHubWorkItemSource")

    private fun allowed(javaClass: JavaClass, moduleOf: (JavaClass) -> String?): Boolean =
        moduleOf(javaClass) in setOf("github", "github-api") || ALLOWED.any { javaClass.name == it || javaClass.name.startsWith("$it$") }

    fun rule(moduleOf: (JavaClass) -> String?): ArchRule = classes().should(object : ArchCondition<JavaClass>("not use GitHubIssueApi outside the github module and GitHubWorkItemSource") {
        override fun check(item: JavaClass, events: ConditionEvents) {
            if (allowed(item, moduleOf)) return
            item.directDependenciesFromSelf.map { it.targetClass.name }.filter { it in GUARDED }.distinct().forEach {
                events.add(SimpleConditionEvent.violated(item, "${item.name} uses $it"))
            }
        }
    })
}
