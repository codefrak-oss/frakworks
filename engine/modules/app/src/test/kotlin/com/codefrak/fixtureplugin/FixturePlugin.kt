package com.codefrak.fixtureplugin

import com.codefrak.sdk.Diagnosis
import com.codefrak.sdk.Doctor
import com.codefrak.sdk.FrakJob
import com.codefrak.sdk.Incident
import com.codefrak.sdk.JobContext
import com.codefrak.sdk.Metrics
import org.operaton.bpm.engine.delegate.DelegateExecution
import org.operaton.bpm.engine.delegate.ExecutionListener
import org.operaton.bpm.engine.delegate.JavaDelegate
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.util.concurrent.CopyOnWriteArrayList

/**
 * A plugin as an operator would write one (engine/README.md, "Custom jobs and the SDK"), compiled with the tests and
 * packed into a jar by `PluginJarFixture`. It lives outside `com.codefrak.engine`, so the engine's own component scan
 * never sees it: only a loaded jar's context does. Not a `@Configuration` proxy, so no CGLIB subclass is made in the
 * plugin loader.
 */
@Configuration(proxyBeanMethods = false)
open class FixturePlugin {
    /** The SDK's [Metrics] comes from the loader's parent context, the engine's `SdkContext`. */
    @Bean("recorder") open fun recorder(metrics: Metrics) = RecordingJob(metrics)
    @Bean("thrower") open fun thrower() = FailingJob()
    @Bean("plain") open fun plain() = NotAJob()
    @Bean("fixtureDoctor") open fun doctor() = FixtureDoctor()
    /** The delegate and listener `src/test/resources/plugin-flows/fixture-flow.bpmn.xml` binds (`${fixtureDelegate}`, `${fixtureListener}`). */
    @Bean("fixtureDelegate") open fun delegate() = FixtureDelegate()
    @Bean("fixtureListener") open fun listener() = FixtureListener()
}

/** A plugin flow's service task: records the `result` callable-smith left and the job's id, and sets `flowRan`. */
class FixtureDelegate : JavaDelegate {
    override fun execute(execution: DelegateExecution) {
        seen += (execution.getVariable("jobId") as? String) to (execution.getVariable("result") as? String)
        execution.setVariable("flowRan", true)
    }

    companion object { val seen = CopyOnWriteArrayList<Pair<String?, String?>>() }
}

/** A plugin flow's execution listener: records the events it was notified of, as `<activity>:<event>`. */
class FixtureListener : ExecutionListener {
    override fun notify(execution: DelegateExecution) { events += "${execution.currentActivityId}:${execution.eventName}" }

    companion object { val events = CopyOnWriteArrayList<String>() }
}

/** One run as [RecordingJob] saw it. */
data class RecordedRun(val jobId: String, val repo: String, val config: Map<String, Any?>, val kindVariable: Any?)

/** Records each run in [runs] (static, so the test reads it across the loader), and sets the variable `beanRan`. */
class RecordingJob(private val metrics: Metrics) : FrakJob {
    override fun execute(ctx: JobContext) {
        metrics.counter("fixture_runs").increment()
        runs += RecordedRun(ctx.jobId, ctx.repo, ctx.config, ctx.getVariable("kind"))
        ctx.setVariable("beanRan", true)
    }

    companion object { val runs = CopyOnWriteArrayList<RecordedRun>() }
}

/** Throws `boom`. */
class FailingJob : FrakJob {
    override fun execute(ctx: JobContext) { throw IllegalStateException("boom") }
}

/** A bean that is not a `FrakJob`. */
class NotAJob

/** A plugin doctor that answers nothing; listed by `PluginDoctors` while the jar is loaded. */
class FixtureDoctor : Doctor {
    override fun diagnose(incident: Incident): Diagnosis? = null
}
