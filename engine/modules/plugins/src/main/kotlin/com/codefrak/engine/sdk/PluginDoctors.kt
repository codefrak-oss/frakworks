package com.codefrak.engine.sdk

import com.codefrak.engine.doctor.PluginDiagnoses
import com.codefrak.engine.doctor.PluginDiagnosis
import com.codefrak.sdk.Diagnosis
import com.codefrak.sdk.Doctor
import com.codefrak.sdk.Incident
import com.codefrak.sdk.Remedy
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.ListableBeanFactory
import org.springframework.stereotype.Component
import java.util.concurrent.ConcurrentHashMap

/**
 * The plugin doctors (engine/README.md, "Custom jobs and the SDK"): every [Doctor] bean in the context, asked in
 * bean order about a stuck ticket's open incident by `Doctor.tick` before it starts a Smith doctor run. The first
 * non-null diagnosis wins, with the bean's name for the history row's `plugin:<bean>` target; one that throws is
 * logged and skipped. A loaded plugin jar's doctors are [register]ed by `PluginJars` as `<jar>:<bean>` when the jar
 * loads and removed when it closes; they are asked before the engine context's own.
 */
@Component
class PluginDoctors(private val beans: ListableBeanFactory) : PluginDiagnoses {
    private val log = LoggerFactory.getLogger(PluginDoctors::class.java)
    private val registered = ConcurrentHashMap<String, Doctor>()

    /** A loaded jar's doctor, under `<jar>:<bean>`. */
    fun register(name: String, doctor: Doctor) { registered[name] = doctor }
    fun unregister(name: String) { registered.remove(name) }

    /** The doctors by name: the registered ones first, then the context's beans, read at each call so a context that gains one is seen. */
    fun doctors(): Map<String, Doctor> = LinkedHashMap<String, Doctor>(registered.toSortedMap()).also { it += beans.getBeansOfType(Doctor::class.java) }

    /** The first answer and the bean that gave it, or null when none answers. */
    fun diagnoseWith(incident: Incident): Pair<String, Diagnosis>? {
        for ((name, doctor) in doctors()) {
            val diagnosis = try { doctor.diagnose(incident) } catch (e: Exception) {
                log.warn("plugin doctor {} failed on incident {}: {}", name, incident.id, e.message); null
            }
            if (diagnosis != null) return name to diagnosis
        }
        return null
    }

    override fun diagnose(id: String, processInstanceId: String, processKey: String, activityId: String?, type: String, message: String?,
                          jobId: String?, repo: String, issue: Long): PluginDiagnosis? =
        diagnoseWith(Incident(id, processInstanceId, processKey, activityId, type, message, jobId, repo, issue))
            ?.let { (bean, d) -> PluginDiagnosis(bean, d.summary, d.remedy == Remedy.RETRY) }
}
