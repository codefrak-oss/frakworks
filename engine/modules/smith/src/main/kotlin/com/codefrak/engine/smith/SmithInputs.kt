package com.codefrak.engine.smith

import com.codefrak.engine.tier.TierRow
import org.operaton.bpm.engine.delegate.DelegateExecution

/**
 * What a run gets in /work/in: the brief (for a refine, the ask's snapshot and the tier map), and for a security
 * review the round's bundle, which `open/update pr` has already pushed (the engine check runs first:
 * engine/README.md, "Why the engine check runs before the reviews"). A proposal's review gets the
 * draft's bundle the same way, unpushed: the proposal flow has no security review, and pushes only what passed.
 * Every propose run after the first gets the latest draft's bundle too, pushed or not, and checks it out, so a
 * redraft after findings, `revise:`, a conflict or an answer starts from the draft the brief describes.
 */
internal fun inputs(mode: String, execution: DelegateExecution, intakes: List<IntakeRef>, tiers: List<TierRow>,
                   privateDocs: List<Map<String, Any?>> = emptyList(), attachments: List<Map<String, Any?>> = emptyList(),
                   docsFirst: Boolean? = null, lineage: Lineage? = null, childMode: String? = null,
                   workItem: Map<String, Any?>? = null, parentNotes: List<Map<String, Any?>>? = null,
                   deadline: String? = null, liveParent: Long? = null): Map<String, ByteArray> = buildMap {
    Brief.of(execution, intakes, tiers, privateDocs, attachments, docsFirst, lineage, childMode, workItem, parentNotes, deadline, liveParent)?.let { put(BRIEF_FILE, it) }
    if (mode == "work" || mode == "verify") reproInputs(execution).forEach { (name, file) -> put(name, file.data) }
    if (mode == "security") put(BUNDLE_FILE, execution.getVariable(Vars.BUNDLE) as? ByteArray ?: error("no bundle to review"))
    if (mode == "review" && execution.getVariable(Vars.PROPOSAL) != null)
        put(BUNDLE_FILE, execution.getVariable(Vars.BUNDLE) as? ByteArray ?: error("no draft to review"))
    if (mode == "propose" && execution.getVariable(Vars.PROPOSAL) != null)
        (execution.getVariable(Vars.BUNDLE) as? ByteArray)?.let { put(BUNDLE_FILE, it) }
}

/** A bug's reproduce run's files ([Vars.REPRO_FILES]) as inputs: `repro/<path>`, with their modes; none outside a bug flow. */
fun reproInputs(execution: DelegateExecution): Map<String, TarFile> {
    val tar = execution.getVariable(Vars.REPRO_FILES) as? ByteArray ?: return emptyMap()
    val dir = REPRO_DIR.substringAfterLast('/')
    return tarFiles(tar).filterKeys { it.startsWith("$dir/") }.mapKeys { "$REPRO_INPUT/${it.key.removePrefix("$dir/")}" }
}
