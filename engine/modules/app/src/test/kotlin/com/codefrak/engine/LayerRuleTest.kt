package com.codefrak.engine

import com.codefrak.layerfixture.ApiThing
import com.codefrak.layerfixture.CommonThing
import com.codefrak.layerfixture.DoctorFlowThing
import com.codefrak.layerfixture.IntakeFlowThing
import com.tngtech.archunit.core.domain.JavaClass
import com.tngtech.archunit.core.importer.ClassFileImporter
import com.tngtech.archunit.core.importer.ImportOption
import com.tngtech.archunit.lang.ArchCondition
import com.tngtech.archunit.lang.ArchRule
import com.tngtech.archunit.lang.ConditionEvents
import com.tngtech.archunit.lang.SimpleConditionEvent
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * The layer rule (engine/README.md, "Engine modules"), over the compiled classes: a class uses classes of its own
 * module and of lower layers only, and a flow module none of another flow's. A class's module is where it was loaded
 * from (`modules/<name>/build/...`, a class directory or the module's jar), since packages span modules.
 */
class LayerRuleTest {
    @Test
    fun `every engine module keeps the layer rule`() {
        val classes = ClassFileImporter().withImportOption(ImportOption.DoNotIncludeTests()).importPackages("com.codefrak.engine")
        val seen = classes.mapNotNull { LayerRule.moduleOf(it) }.toSet()
        assertEquals(LayerRule.MODULES.toSet(), seen, "the classes of every module are on engine-app's test classpath")
        LayerRule.rule(LayerRule::moduleOf).check(classes)
    }

    @Test
    fun `the rule fails for a lower layer that uses an upper one`() {
        val classes = ClassFileImporter().importClasses(CommonThing::class.java, ApiThing::class.java)
        val error = assertFailsWith<AssertionError> { LayerRule.rule(fixtureModules).check(classes) }
        assertTrue("common uses api" in error.message.orEmpty(), error.message)
    }

    @Test
    fun `the rule fails for a flow that uses another flow`() {
        val classes = ClassFileImporter().importClasses(IntakeFlowThing::class.java, DoctorFlowThing::class.java)
        val error = assertFailsWith<AssertionError> { LayerRule.rule(fixtureModules).check(classes) }
        assertTrue("flow-intake uses flow-doctor" in error.message.orEmpty(), error.message)
    }

    @Test
    fun `the rule passes an upper layer that uses a lower one`() {
        val classes = ClassFileImporter().importClasses(ApiThing::class.java, CommonThing::class.java, DoctorFlowThing::class.java)
        LayerRule.rule { if (it.isEquivalentTo(CommonThing::class.java)) null else fixtureModules(it) }.check(classes)
    }

    /** The fixture classes (com.codefrak.layerfixture) placed in the modules they stand for. */
    private val fixtureModules: (JavaClass) -> String? = {
        when {
            it.isEquivalentTo(CommonThing::class.java) -> "common"
            it.isEquivalentTo(ApiThing::class.java) -> "api"
            it.isEquivalentTo(IntakeFlowThing::class.java) -> "flow-intake"
            it.isEquivalentTo(DoctorFlowThing::class.java) -> "flow-doctor"
            else -> null
        }
    }
}

/** The layer rule as an ArchUnit rule, with the class-to-module mapping passed in so the tests can place fixtures. */
object LayerRule {
    /** The layers, lowest first; every flow module is the one layer `flows`. `hitlp` is the HITLP requester (#2484: no server). */
    val LAYERS = listOf("common", "github", "events", "accounts", "jev", "smith", "incidents", "hitlp", "firstmate", "flows", "plugins", "api", "app")
    val FLOWS = listOf("flow-intake", "flow-feature", "flow-delivery", "flow-doctor", "flow-friction", "flow-proposal")
    val MODULES = LAYERS.filter { it != "flows" } + FLOWS

    private val LOCATION = Regex("""/modules/([a-z-]+)/(?:build|out)/""")

    /** The engine module [javaClass] was loaded from, or null for a class of no engine module (the JDK, a library, the SDK). */
    fun moduleOf(javaClass: JavaClass): String? =
        javaClass.source.orElse(null)?.uri?.toString()?.let { LOCATION.find(it)?.groupValues?.get(1) }?.takeIf { it in MODULES }

    private fun layer(module: String) = LAYERS.indexOf(if (module in FLOWS) "flows" else module)

    /** [from] may use [to]: its own module, or a lower layer. Two flows share a layer, so neither may use the other. */
    fun allowed(from: String, to: String) = from == to || layer(to) < layer(from)

    fun rule(moduleOf: (JavaClass) -> String?): ArchRule = classes().should(
        object : ArchCondition<JavaClass>("use only their own module and the layers below it, and no other flow module") {
            override fun check(item: JavaClass, events: ConditionEvents) {
                val from = moduleOf(item) ?: return
                item.directDependenciesFromSelf.forEach { dependency ->
                    val to = moduleOf(dependency.targetClass) ?: return@forEach
                    if (!allowed(from, to)) events.add(SimpleConditionEvent.violated(dependency, "$from uses $to: ${dependency.description}"))
                }
            }
        },
    ).allowEmptyShould(true)
}
