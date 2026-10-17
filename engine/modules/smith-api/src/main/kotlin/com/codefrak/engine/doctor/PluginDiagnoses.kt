package com.codefrak.engine.doctor

/** A plugin doctor's answer ([PluginDiagnoses]): the bean that gave it, its summary, and whether its remedy is a retry (else none). */
data class PluginDiagnosis(val bean: String, val summary: String, val retry: Boolean)

/**
 * The plugin doctors as the doctor asks them (`PluginDoctors`, in the root, over the SDK's `Doctor` beans; engine/README.md,
 * "Custom jobs and the SDK"), so the doctor module does not depend on the SDK.
 */
fun interface PluginDiagnoses {
    /** The first plugin doctor's answer about a stuck ticket's ([repo]#[issue]) open incident, or null when none answers. */
    fun diagnose(id: String, processInstanceId: String, processKey: String, activityId: String?, type: String, message: String?,
                 jobId: String?, repo: String, issue: Long): PluginDiagnosis?
}
