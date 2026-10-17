package com.codefrak.engine.feature

import com.codefrak.engine.engineJson
import com.codefrak.engine.smith.SplitDraft
import tools.jackson.module.kotlin.readValue

/**
 * One child ticket a `split` design drafts (engine/README.md, "Split features"):
 * a ticket in the refiner's shape, its kind (a feature child takes its parent's mode:
 * [featureMode] is a splitter's leftover, ignored when filing), and the
 * indexes of the earlier children it must wait on, and its story [points] (bots/story-points.md), at most 13:
 * filed into the child's trailer and `engine.handled_issue.points` as an intake-filed ticket's are.
 */
data class ChildDraft(
    val title: String,
    val body: String,
    val kind: String,
    val featureMode: String? = null,
    val dependsOn: List<Int>,
    val points: Int? = null,
) {
    companion object {
        /** The drafts a split child's work handed back, as `child closed` carries them (`Vars.CLOSED_CHILD_SPLIT`); null for none. */
        fun parse(bytes: ByteArray?): List<ChildDraft>? = bytes?.let { engineJson.readValue<List<ChildDraft>>(it) }
        fun bytes(drafts: List<ChildDraft>): ByteArray = engineJson.writeValueAsBytes(drafts)
        /** A split work result's draft (the smith layer's [SplitDraft]) as a design run's. */
        fun of(d: SplitDraft) = ChildDraft(d.title, d.body, d.kind, d.featureMode, d.dependsOn, d.points)

        val KINDS = setOf("chore", "bug", "feature")
        /** The most parts a split may have over its whole life: filed children plus remaining drafts, after any revise. */
        const val MAX = 16
        const val MIN = 2
        /** A child's story-point scale: no more than a chore or a bug takes, as a child is not split again (bots/story-points.md). */
        val POINTS = com.codefrak.engine.intake.RefineResult.POINTS.filter { it <= com.codefrak.engine.intake.RefineResult.MAX_POINTS_UNSPLIT }.toSet()

        /** The rules every draft list obeys, `index` numbering from `offset` (the children filed before a revise). */
        fun problems(children: List<ChildDraft>, offset: Int = 0): List<String> = buildList {
            children.forEachIndexed { i, c ->
                val n = offset + i
                if (c.title.isBlank()) add("children[$n] has no title")
                if (c.body.isBlank()) add("children[$n] has no body")
                if (c.kind !in KINDS) add("children[$n].kind '${c.kind}' is not one of $KINDS")
                if (c.points !in POINTS) add("children[$n].points ${c.points} is not one of $POINTS: point each child by bots/story-points.md")
                if (c.dependsOn.any { it < 0 || it >= n }) add("children[$n].dependsOn ${c.dependsOn} must name earlier children only (0 to ${n - 1})")
                if (com.codefrak.engine.smith.AllowedScope.grantsPushToMain(c.body)) add("children[$n] allows pushing to main")
            }
        }

        /** GitHub's own limit on an issue body; a longer draft could not be filed, and is not scanned. */
        const val MAX_BODY = 65536
        private val FILED_AS = Regex("""(?i)\bfiled as #(\d+)""")
        private val MERGED_IN = Regex("""(?i)\bmerged in PR #(\d+)""")
        private val WORD = Regex("""\p{L}""")
        private val LIST_ITEM = Regex("""^(?:[-*+]|\d{1,9}[.)])\s+\S""")

        /**
         * The replan's extra rules for the drafts it leaves to file (engine/README.md, "Split features"):
         * each is a real ticket, a Goal with content and an Acceptance criteria section with at least one
         * item, and not a [filed] child restated: its title, or a body that is its record.
         *
         * The heuristic for a record (#751): "Filed as #n" / "merged in PR #x" naming a filed child is the
         * child's record only when the draft has no work of its own, that is when it lacks an Acceptance
         * criteria item or its Goal has no words once those phrases are taken out ("Filed as #204, merged in
         * PR #209."). A draft with both that names a merged sibling ("builds on the loader merged in PR #761")
         * names a dependency, and is kept.
         */
        fun replanProblems(children: List<ChildDraft>, filed: List<FiledChild>, offset: Int): List<String> = buildList {
            val numbers = filed.map { it.number }.toSet()
            val prs = filed.mapNotNull { it.prNumber }.toSet()
            children.forEachIndexed { i, c ->
                val n = offset + i
                filed.firstOrNull { it.title.trim().equals(c.title.trim(), ignoreCase = true) }
                    ?.let { add("children[$n] has the title of filed child #${it.number}; a draft is work still to do, not a filed child restated") }
                if (c.body.length > MAX_BODY) { add("children[$n].body is over $MAX_BODY characters"); return@forEachIndexed }
                val sections = sections(c.body)
                val goal = sections["goal"].orEmpty()
                val criteria = sections["acceptance criteria"].orEmpty().any { LIST_ITEM.containsMatchIn(it.trim()) }
                if (goal.none { it.isNotBlank() }) add("children[$n] has no Goal section with content")
                if (!criteria) add("children[$n] has no Acceptance criteria section with at least one item")
                val work = criteria && goal.any { line -> WORD.containsMatchIn(MERGED_IN.replace(FILED_AS.replace(line, ""), "")) }
                if (work) return@forEachIndexed
                FILED_AS.findAll(c.body).map { it.groupValues[1].toLong() }.firstOrNull { it in numbers }
                    ?.let { add("children[$n] records filed child #$it (\"Filed as #$it\"); a draft is work still to do") }
                MERGED_IN.findAll(c.body).map { it.groupValues[1].toLong() }.firstOrNull { it in prs }
                    ?.let { add("children[$n] records a filed child's merge (\"merged in PR #$it\"); a draft is work still to do") }
            }
        }

        /** A markdown body's sections: each ATX heading's name, lowercased, to the lines under it up to the next heading. */
        fun sections(body: String): Map<String, List<String>> {
            val out = LinkedHashMap<String, MutableList<String>>()
            var current: MutableList<String>? = null
            body.lineSequence().forEach { line ->
                val name = heading(line)
                if (name != null) current = out.getOrPut(name.lowercase()) { mutableListOf() }
                else current?.add(line)
            }
            return out
        }

        /** The name of an ATX heading line (`## Goal`, `### Acceptance criteria ##`), or null. Linear: no regex. */
        private fun heading(line: String): String? {
            val trimmed = line.trimStart()
            if (line.length - trimmed.length > 3 || !trimmed.startsWith('#')) return null
            val hashes = trimmed.takeWhile { it == '#' }.length
            val rest = trimmed.substring(hashes)
            if (hashes > 6 || (rest.isNotEmpty() && !rest[0].isWhitespace())) return null
            return rest.trim().trimEnd('#').trimEnd()
        }
    }
}
