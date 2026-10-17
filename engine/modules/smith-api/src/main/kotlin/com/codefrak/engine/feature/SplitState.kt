package com.codefrak.engine.feature

import com.codefrak.engine.engineJson
import tools.jackson.module.kotlin.readValue
import com.codefrak.engine.smith.Vars
import org.operaton.bpm.engine.delegate.DelegateExecution

/**
 * One child the engine filed: its draft index, number and url, kind, and where it is: `open`,
 * `merged` (with its PR), `satisfied` (its work found nothing to do and closed it, no PR), or
 * `split` (its work split it, and its drafts took its place: engine/README.md, "A child that splits"); and the root
 * instance of the flow the engine started on it, when it started one (the replan brief reads its live state). On the
 * work-item path ([WorkItemSplitting]) [item] is the child's work item id, and [number] is 0 with no url.
 */
data class FiledChild(val index: Int, val title: String, val number: Long, val url: String, val kind: String,
                      val state: String = "open", val prNumber: Long? = null, val instance: String? = null,
                      val item: String? = null)

/**
 * A split feature's flow state (engine/README.md, "Split features"): the drafts not
 * filed yet, oldest first, the children filed so far, and the drafts a replan dropped.
 * Kept as JSON bytes in `Vars.SPLIT` on the parent's instance; the board reads it too.
 */
data class SplitState(
    val drafts: List<ChildDraft> = emptyList(),
    val filed: List<FiledChild> = emptyList(),
    val dropped: List<String> = emptyList(),
) {
    /** Parts over the split's life so far: filed and still to file. */
    val total get() = filed.size + drafts.size

    /**
     * The next draft to file: the first whose `dependsOn` are all merged or satisfied. Drafts are
     * indexed over the whole split (filed first), so a dependency names a filed child.
     */
    fun next(): ChildDraft? = ready().firstOrNull()

    /** Every draft whose `dependsOn` are all merged or satisfied, in order: what a parallel split files in one pass. */
    fun ready(): List<ChildDraft> = drafts.filter { d -> d.dependsOn.all { i -> filed.any { it.index == i && it.state in DONE } } }

    /** The filed children still open: what a parallel split's parent is parked on. */
    fun open(): List<FiledChild> = filed.filter { it.state == OPEN }

    fun filed(draft: ChildDraft, number: Long, url: String, kind: String, instance: String? = null): SplitState =
        copy(drafts = drafts - draft, filed = filed + FiledChild(filed.size, draft.title, number, url, kind, instance = instance))

    /** Records [draft] filed as work item [item] (the work-item path: no issue number). */
    fun filedItem(draft: ChildDraft, item: String, kind: String): SplitState =
        copy(drafts = drafts - draft, filed = filed + FiledChild(filed.size, draft.title, 0, "", kind, item = item))

    /** Records the flow started on filed child [number]. */
    fun started(number: Long, instance: String): SplitState =
        copy(filed = filed.map { if (it.number == number) it.copy(instance = instance) else it })

    /**
     * Records the child's close: merged with its PR, or without one satisfied. The scan wakes the
     * parent only for a close of the engine's own (FlowStarter.mergedClose), so no PR means the
     * child's work found nothing to do.
     */
    fun closed(number: Long, pr: Long?): SplitState = closedWhere({ it.number == number }, pr)

    /** [closed], or with the drafts its work run split it into, [splitInto]. */
    fun closed(number: Long, pr: Long?, split: List<ChildDraft>?): SplitState =
        if (split.isNullOrEmpty()) closed(number, pr) else splitInto(number, split)

    /** [closed] for the child filed as work item [item] (the work-item path). */
    fun closedItem(item: String, pr: Long?, split: List<ChildDraft>?): SplitState =
        if (split.isNullOrEmpty()) closedWhere({ it.item == item }, pr) else splitWhere({ it.item == item }, split)

    private fun closedWhere(match: (FiledChild) -> Boolean, pr: Long?): SplitState =
        copy(filed = filed.map { if (match(it)) it.copy(state = if (pr != null) MERGED else SATISFIED, prNumber = pr) else it })

    /**
     * The filed child [number] split by its work run (engine/README.md, "A child that splits"): it is recorded
     * `split`, neither merged nor satisfied, and [parts] go in its place, ahead of the drafts left, in order.
     * Their `dependsOn` (indexes among [parts]) become indexes over the whole split; a later draft's index moves
     * past them, and a later draft that waited on the split child waits on the last of its parts instead.
     */
    fun splitInto(number: Long, parts: List<ChildDraft>): SplitState = splitWhere({ it.number == number }, parts, "#$number")

    private fun splitWhere(match: (FiledChild) -> Boolean, parts: List<ChildDraft>, name: String = "the item"): SplitState {
        val child = filed.firstOrNull(match) ?: error("$name is not a filed child of this split")
        val base = filed.size; val k = parts.size
        val placed = parts.map { d -> d.copy(dependsOn = d.dependsOn.map { base + it }) }
        val moved = drafts.map { d ->
            d.copy(dependsOn = d.dependsOn.map { i -> if (i == child.index) base + k - 1 else if (i >= base) i + k else i }.distinct())
        }
        return copy(drafts = placed + moved, filed = filed.map { if (match(it)) it.copy(state = SPLIT) else it })
    }

    fun revised(children: List<ChildDraft>): SplitState =
        copy(drafts = children, dropped = dropped + drafts.map { it.title }.filter { t -> children.none { it.title == t } })

    fun done(): SplitState = copy(drafts = emptyList(), dropped = dropped + drafts.map { it.title })

    fun merged(): List<FiledChild> = filed.filter { it.state == MERGED }

    fun bytes(): ByteArray = engineJson.writeValueAsBytes(this)

    companion object {
        const val OPEN = "open"
        const val MERGED = "merged"
        const val SATISFIED = "satisfied"
        const val SPLIT = "split"
        /** The states a later draft's `dependsOn` accepts. */
        val DONE = setOf(MERGED, SATISFIED)
        fun parse(bytes: ByteArray): SplitState = engineJson.readValue(bytes)
        fun of(bytes: ByteArray?): SplitState? = bytes?.let { parse(it) }
    }
}

/**
 * `Merged #7`, or for a split parent `Merged children #61 (PR #7), #62 (PR #8)`, with `; closed with
 * nothing to do: #63` for its satisfied children: what the acceptance and the close say was merged.
 */
fun DelegateExecution.mergedText(): String {
    val filed = SplitState.of(getVariable(Vars.SPLIT) as? ByteArray)?.filed.orEmpty()
    val merged = filed.filter { it.state == SplitState.MERGED }
    val satisfied = filed.filter { it.state == SplitState.SATISFIED }
    if (merged.isEmpty() && satisfied.isEmpty()) return "Merged #${getVariable(Vars.PR)}"
    return listOfNotNull(
        merged.takeIf { it.isNotEmpty() }?.let { c -> "Merged children " + c.joinToString { "#${it.number} (PR #${it.prNumber})" } },
        satisfied.takeIf { it.isNotEmpty() }?.let { c -> "closed with nothing to do: " + c.joinToString { "#${it.number}" } },
    ).joinToString("; ").replaceFirstChar { it.uppercase() }
}
