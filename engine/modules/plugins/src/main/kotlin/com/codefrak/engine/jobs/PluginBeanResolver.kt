package com.codefrak.engine.jobs

import jakarta.el.CompositeELResolver
import jakarta.el.ELContext
import jakarta.el.ELResolver
import org.operaton.bpm.engine.ProcessEngine
import org.operaton.bpm.engine.impl.cfg.ProcessEngineConfigurationImpl
import org.operaton.bpm.engine.impl.cfg.ProcessEnginePlugin
import org.operaton.bpm.engine.impl.context.Context
import org.operaton.bpm.engine.spring.SpringExpressionManager
import org.slf4j.LoggerFactory
import org.springframework.context.ApplicationContext
import org.springframework.stereotype.Component
import java.util.concurrent.ConcurrentHashMap

/**
 * Resolves `${name}` in a plugin flow against the jar of the job it belongs to (engine/README.md, "Custom jobs and
 * the SDK": flow jobs). Asked last, after the engine's own resolvers (`PluginFlowsPlugin`), so a name the engine's
 * context has is the engine's bean whatever a jar holds; `FlowRules` refuses a plugin diagram for such a name. For a
 * name nothing else resolved: the executing definition's deployment (`Context.getBpmnExecutionContext()`), its name
 * `job:<id>` ([PluginFlows.deploymentName]), and the local beans of that job's loaded jar, loaded from the job's row
 * (`PluginJars.acquireContext`) when nothing holds it yet: an in-flight instance after an engine restart. Any other
 * deployment, or no execution at hand, resolves nothing here.
 */
class PluginBeanResolver(private val engine: ApplicationContext) : ELResolver() {
    private val log = LoggerFactory.getLogger(PluginBeanResolver::class.java)
    /** Deployment id to job id (or "" for a deployment that is no job's); a deployment never changes its name. */
    private val jobs = ConcurrentHashMap<String, String>()

    override fun getValue(context: ELContext, base: Any?, property: Any?): Any? {
        if (base != null || property !is String) return null
        val pluginContext = contextOfExecutingJob() ?: return null
        if (!pluginContext.containsLocalBean(property)) return null
        context.isPropertyResolved = true
        return pluginContext.getBean(property)
    }

    override fun getType(context: ELContext, base: Any?, property: Any?): Class<*>? = getValue(context, base, property)?.let { Any::class.java }
    override fun setValue(context: ELContext, base: Any?, property: Any?, value: Any?) {
        if (base == null && property is String && contextOfExecutingJob()?.containsLocalBean(property) == true) throw jakarta.el.PropertyNotWritableException("$property is a plugin bean")
    }
    override fun isReadOnly(context: ELContext, base: Any?, property: Any?): Boolean = true
    override fun getCommonPropertyType(context: ELContext, base: Any?): Class<*>? = if (base == null) String::class.java else null

    /** The loaded context of the job whose `job:<id>` deployment the executing definition is in, or null. */
    private fun contextOfExecutingJob(): org.springframework.context.ApplicationContext? {
        val execution = runCatching { Context.getBpmnExecutionContext()?.execution }.getOrNull() ?: return null
        val deploymentId = execution.processDefinition?.deploymentId ?: return null
        val jobId = jobs.getOrPut(deploymentId) {
            val name = Context.getCommandContext()?.deploymentManager?.findDeploymentById(deploymentId)?.name
            PluginFlows.jobIdOf(name) ?: ""
        }
        if (jobId.isEmpty()) return null
        val jars = engine.getBean(PluginJars::class.java)
        jars.entryOf(jobId)?.let { return it.context }
        val job = engine.getBean(JobStore::class.java).get(jobId) ?: return null
        return try {
            jars.acquireContext(job.configNode()["jar"].asString(), jobId).context
        } catch (e: PluginLoadException) {
            log.warn("job {}: its jar cannot be loaded for a running flow: {}", jobId, e.message)
            null
        }
    }
}

/**
 * Installs [PluginBeanResolver] (engine/README.md, "Custom jobs and the SDK"): the Spring starter's factory bean gives the
 * configuration a `SpringExpressionManager` before `init()`, and this plugin's `preInit` replaces it with one whose EL
 * resolver is the engine's own composite (the variable scope, the application context, the rest) followed by the plugin
 * resolver, so the engine's beans always win and a jar's `JavaDelegate`s and listeners bind only in its own flows. No
 * engine resolver is dropped and no second expression manager is kept.
 */
@Component
class PluginFlowsPlugin(private val applicationContext: ApplicationContext) : ProcessEnginePlugin {
    override fun preInit(configuration: ProcessEngineConfigurationImpl) {
        configuration.setExpressionManager(PluginAwareExpressionManager(applicationContext, configuration.beans, PluginBeanResolver(applicationContext)))
    }
    override fun postInit(configuration: ProcessEngineConfigurationImpl) {}
    override fun postProcessEngineBuild(engine: ProcessEngine) {}
}

/** The engine's `SpringExpressionManager` with [plugins] appended after everything it resolves itself. */
class PluginAwareExpressionManager(applicationContext: ApplicationContext, beans: Map<Any, Any>?, private val plugins: ELResolver) :
    SpringExpressionManager(applicationContext, beans) {
    override fun createElResolver(): ELResolver = CompositeELResolver().apply {
        add(super.createElResolver())
        add(plugins)
    }
}
