package com.codefrak.engine.smith

import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.assertEquals
import java.io.File
import tools.jackson.module.kotlin.readValue

/** The prompts in smith/run-smith.sh. The image build copies no smith/, so this runs in a checkout only. */
class RunSmithPromptTest {
    private fun script(): File? =
        generateSequence(File("").absoluteFile) { it.parentFile }
            .flatMap { sequenceOf(File(it, "smith/run-smith.sh"), File(it, "engine/smith/run-smith.sh")) }
            .firstOrNull { it.isFile }

    @Test
    fun `the refine prompt names the tier rubric by path and renders the brief's tier map`() {
        val file = script()
        assumeTrue(file != null, "smith/run-smith.sh is not in this build context")
        val text = file!!.readText()
        val refine = text.substringAfter("refining exactly one intake ask").substringBefore("\nPROMPT\n")
        listOf("\${FRAMEWORK}/model-choice.md", "\${TIER_MAP}", "prescribed by the user", "\"points\"", "\${FRAMEWORK}/story-points.md")
            .forEach { assertTrue(it in refine, "$it in the refine prompt") }
        assertTrue("bots/" !in refine, "the refine prompt reads the framework docs from the image, not the clone")
        assertTrue("tiers.toml" !in text, "the tier map is the engine's, not a file")
    }

    @Test
    fun `the refine prompt files a clear spec as a chore, and a named kind or mode binds`() {
        val file = script()
        assumeTrue(file != null, "smith/run-smith.sh is not in this build context")
        val refine = file!!.readText().substringAfter("refining exactly one intake ask").substringBefore("\nPROMPT\n")
        listOf(
            "say \"chore\" when the ask states what to change and how to check it, even when that is a multi-file UI or behaviour change",
            "Reserve \"feature\" for an ask whose design is genuinely open",
            "A kind the user names in the ask binds",
            "the user's stated mode binds",
        ).forEach { assertTrue(it in refine, "$it in the refine prompt") }
        listOf("small edits", "that small and that clear").forEach { assertTrue(it !in refine, "$it no longer in the refine prompt") }
    }

    @Test
    fun `the design prompt prefers one work run to a split, and an overflow still pushes toward split`() {
        val file = script()
        assumeTrue(file != null, "smith/run-smith.sh is not in this build context")
        val text = file!!.readText()
        val design = text.substringAfter("designing exactly one feature").substringBefore("\nPROMPT\n")
        val split = text.substringAfter("else\n      SPLIT_TEXT=\"").substringBefore("\n")
        assertTrue("6 files" !in text, "the 6-files heuristic is gone")
        assertTrue("\${SPLIT_TEXT}" in design && "\${OVERFLOW_TEXT}" in design)
        listOf(
            "Prefer one work run: return \\\"planned\\\" whenever the plan fits one PR of reviewable size",
            "Return \\\"split\\\" only when one work run would overflow its context window, or when the parts are independently shippable",
        ).forEach { assertTrue(it in split, "$it in SPLIT_TEXT") }
        val overflow = text.substringAfter("OVERFLOW_TEXT=\"The previous work run").substringBefore("\n")
        assertTrue("This ticket should split; return \\\"split\\\"" in overflow, "OVERFLOW_TEXT pushes toward split")
    }

    /** The design prompt's child-mode block (engine/README.md, "Split features"): the `case` on the brief's `childMode`. */
    private fun childModeBlock(text: String): String = text.substringAfter("CHILD_MODE_TEXT=\"\"").substringBefore("    esac")

    private val parallelSentence = "prefer children that can run at the same time"

    @Test
    fun `the design prompt under serial is today's text, with no parallel steer`() {
        val file = script()
        assumeTrue(file != null, "smith/run-smith.sh is not in this build context")
        val text = file!!.readText()
        val design = text.substringAfter("designing exactly one feature").substringBefore("\nPROMPT\n")
        assertTrue("\${CHILD_MODE_TEXT}" in design, "the design prompt carries the child-mode block")
        assertTrue("jq -r '.childMode // \"serial\"'" in text, "an older brief without childMode reads as serial")
        val block = childModeBlock(text)
        assertTrue("serial)" !in block, "serial has no case arm: its text is empty")
        assertTrue("parallel|live)" in block, "only parallel and live add the paragraph")
        assertTrue(parallelSentence !in text.substringAfter("else\n      SPLIT_TEXT=\"").substringBefore("\n"), "SPLIT_TEXT (serial) does not steer toward parallel children")
    }

    @Test
    fun `the design prompt under parallel steers toward children that run at the same time`() {
        val file = script()
        assumeTrue(file != null, "smith/run-smith.sh is not in this build context")
        val block = childModeBlock(file!!.readText())
        assertTrue("parallel|live)" in block)
        listOf(parallelSentence, "Keep \\\"dependsOn\\\" to true ordering constraints only", "Say in the plan's summary which children are independent")
            .forEach { assertTrue(it in block, "$it in the parallel paragraph") }
        assertTrue(designPromptFor("parallel").contains(parallelSentence), "rendered under parallel")
        assertTrue(!designPromptFor("serial").contains(parallelSentence), "absent under serial")
    }

    @Test
    fun `the design prompt under live steers the same way as parallel, naming the mode`() {
        val file = script()
        assumeTrue(file != null, "smith/run-smith.sh is not in this build context")
        val block = childModeBlock(file!!.readText())
        assertTrue("in \${CHILD_MODE} mode" in block, "the paragraph names the mode it runs under")
        val live = designPromptFor("live")
        assertTrue(live.contains(parallelSentence) && live.contains("in live mode"), "rendered under live")
    }

    @Test
    fun `the work prompt of a flaky-test brief runs the class once, never loops it, and asks for a suspected cause, and another bug's does not`() {
        val file = script()
        assumeTrue(file != null, "smith/run-smith.sh is not in this build context")
        val text = file!!.readText()
        val work = text.substringAfter("You are Agent Smith, working exactly one ticket").substringBefore("\nPROMPT\n")
        assertTrue("\${BUG_TEXT}" in work, "the work prompt carries the bug paragraph")
        val block = "  BUG_TEXT=\"\"\n" + text.substringAfter("  BUG_TEXT=\"\"\n").substringBefore("  # A feature: the design run's plan")
        fun render(brief: String): String {
            val script = "BRIEF='$brief'\n" + block + "printf '%s' \"\${BUG_TEXT}\"\n"
            val process = ProcessBuilder("bash", "-c", script).redirectErrorStream(true).start()
            val out = process.inputStream.bufferedReader().readText()
            assumeTrue(process.waitFor() == 0, "bash and jq run the block: $out")
            return out
        }
        val flaky = render("{\"flakyTest\":true}")
        listOf("cd engine && gradle :engine-<module>:test --tests <Class> --no-daemon --offline", "Run the changed test class once",
            "Never run it in a loop", "Suspected cause:", "There is no reproduction")
            .forEach { assertTrue(it in flaky, "$it in the flaky-test work text: $flaky") }
        val bug = render("{\"reproduction\":{\"rootCause\":\"x\"}}")
        assertTrue("Fix the root cause" in bug, bug)
        listOf("Suspected cause:", "Never run it in a loop", "flaky test").forEach { assertTrue(it !in bug, "$it not in another bug's text") }
        assertEquals("", render("{}"), "no bug, no text")
    }

    @Test
    fun `the replan prompt under parallel says siblings may still be open and how to steer them, serial says nothing of it`() {
        val file = script()
        assumeTrue(file != null, "smith/run-smith.sh is not in this build context")
        val text = file!!.readText()
        val replan = text.substringAfter("replanning exactly one split feature").substringBefore("\nPROMPT\n")
        assertTrue("\${REPLAN_SIBLINGS}" in replan, "the replan prompt carries the siblings paragraph")
        val block = "    REPLAN_MODE=" + text.substringAfter("    REPLAN_MODE=").substringBefore("read -r -d '' PROMPT")
        fun render(mode: String): String {
            val script = "BRIEF='{\"childMode\":\"$mode\"}'\n" + block + "printf '%s' \"\${REPLAN_SIBLINGS}\"\n"
            val process = ProcessBuilder("bash", "-c", script).redirectErrorStream(true).start()
            val out = process.inputStream.bufferedReader().readText()
            assumeTrue(process.waitFor() == 0, "bash and jq run the block: $out")
            return out
        }
        val parallel = render("parallel")
        listOf("in parallel mode", "siblings may still be open", "\"running\"", "\"steer\" it", "You are woken again as each open sibling closes")
            .forEach { assertTrue(it in parallel, "$it in the parallel replan prompt: $parallel") }
        assertTrue("in live mode" in render("live"))
        assertEquals("", render("serial"), "serial keeps today's replan prompt")
    }

    @Test
    fun `the parent prompt waits on await_children with cursors and no sleep, reads runs, transcripts and tickets, steers, and returns a replan-shaped result`() {
        val file = script()
        assumeTrue(file != null, "smith/run-smith.sh is not in this build context")
        val text = file!!.readText()
        val parent = text.substringAfter("the live parent of exactly one split feature").substringBefore("\nPROMPT\n")
        assertTrue("PARENT_POLL" !in text, "the parent no longer sleeps between polls")
        listOf("Wait with await_children", "never sleep", "Pass the cursor", "{changed: false}", "read_ticket_runs", "read_transcript", "read_ticket ",
            "steer_child({child, message})", "Steer bravely", "answer_child({child, answer})", "answer_child({child, escalate: true, note})", "Answer your children", "note_for_next_parent({text})", "parentNotes", "remainingSeconds", "Stay in the turn budget", "When the last open child has closed", "replan-shaped",
            "\"continue\"", "\"revise\"", "\"done\"", "\"ended\"", "\"steer\"", "\${CHECK}")
            .forEach { assertTrue(it in parent, "$it in the parent prompt") }
    }

    /** The design prompt's child-mode paragraph as the script renders it for a brief with `childMode` [mode]: the block, run by bash. */
    private fun designPromptFor(mode: String): String {
        val file = script()!!
        val block = childModeBlock(file.readText())
        val script = "BRIEF='{\"childMode\":\"$mode\"}'\n" +
            "CHILD_MODE=\"\$(printf '%s' \"\${BRIEF}\" | jq -r '.childMode // \"serial\"' 2>/dev/null || echo serial)\"\n" +
            "CHILD_MODE_TEXT=\"\"" + block + "    esac\nprintf '%s' \"\${CHILD_MODE_TEXT}\"\n"
        val process = ProcessBuilder("bash", "-c", script).redirectErrorStream(true).start()
        val out = process.inputStream.bufferedReader().readText()
        assumeTrue(process.waitFor() == 0, "bash and jq run the block: $out")
        return out
    }

    @Test
    fun `the design prompt lists a menu's candidates as options, and an ask about a menu is a choice block`() {
        val file = script()
        assumeTrue(file != null, "smith/run-smith.sh is not in this build context")
        val design = file!!.readText().substringAfter("designing exactly one feature").substringBefore("\nPROMPT\n")
        listOf(
            "A question whose answer is one of a small set of candidates lists them as \"options\": 2 to 5 {\"id\", \"label\"}, with short unique ids (a, b, c, d) and labels short enough for a 40-character button",
            "When the answer is not a menu, give no options.",
            "An \"ask\" question whose answer is a menu ends in a kind: choice ask block with an options: list of those candidates",
            "\"options\": array of 2 to 5 {\"id\": string, \"label\": string}",
        ).forEach { assertTrue(it in design, "$it in the design prompt") }
    }

    @Test
    fun `every result prompt states its types and runs check-result, which the image has`() {
        val file = script()
        assumeTrue(file != null, "smith/run-smith.sh is not in this build context")
        val text = file!!.readText()
        assertTrue("json.tool" !in text, "python3 -m json.tool checks syntax only; check-result checks the schema")
        val writes = Regex("""[Ww]rite \$\{OUT}/result\.json, a single JSON object[^.]*\. \$\{TYPES}""").findAll(text).count()
        // refine, design, replan, parent, accept, reproduce, verify, fix, diagnose, doctor, propose, work, and one prompt for review and security
        assertTrue(writes == 13, "every result prompt states the types: $writes")
        assertTrue(Regex("""\$\{CHECK}""").findAll(text).count() == 13, "every result prompt runs the check")
        assertTrue("check-result \${MODE} \${OUT}/result.json" in text)
        val docker = File(file.parentFile, "Dockerfile").readText()
        listOf("python3-jsonschema", "COPY schemas/ /etc/smith/schemas/", "COPY check-result.py /usr/local/bin/check-result")
            .forEach { assertTrue(it in docker, "$it in smith/Dockerfile") }
    }

    @Test
    fun `the propose prompt drafts in the template under docs-proposals, keeps the decision log, commits and is bundled`() {
        val file = script()
        assumeTrue(file != null, "smith/run-smith.sh is not in this build context")
        val text = file!!.readText()
        val propose = text.substringAfter("drafting exactly one proposal").substringBefore("\nPROMPT\n")
        listOf(
            "Read the code first", "docs/proposals/<slug>.md", "Status:", "Decision log:", "Ticket: #\${SMITH_ISSUE}",
            "## The question", "## Proposal", "## Alternatives, and why not", "## Open questions", "## Implementation outline", "## Cost",
            "Keep every line of its \"Decision log:\" list verbatim", "\"answers\" and \"findings\" bind", "Touch nothing outside docs/proposals/",
            "Commit on \${BRANCH}", "never push", "\"path\" (string)", "\"confidence\": number 0-1", "\${CHECK}",
        ).forEach { assertTrue(it in propose, "$it in the propose prompt") }
        assertTrue("[ \"\${MODE}\" = \"propose\" ]; } && [ \"\$(git rev-list --count" in text, "a propose run's commits are bundled")
    }

    @Test
    fun `a proposal's review fetches the unpushed draft, checks the proposal checklist, and refine knows the kind`() {
        val file = script()
        assumeTrue(file != null, "smith/run-smith.sh is not in this build context")
        val text = file!!.readText()
        assertTrue("[ \"\${MODE}\" = \"security\" ] || { [ \"\${MODE}\" = \"review\" ] && [ -f \"\${BUNDLE_IN}\" ]; }" in text,
            "a review given a bundle fetches it as security does")
        val review = text.substringAfter("FOCUS=\"a correctness review").substringBefore("PASS=approved")
        listOf(".proposal.path", "NOT been pushed", "cites the file or README section", "at least one real alternative, each with why not",
            "open questions are listed", "a cost", "what changed since the last decision")
            .forEach { assertTrue(it in review, "$it in the proposal review focus") }
        val refine = text.substringAfter("refining exactly one intake ask").substringBefore("\nPROMPT\n")
        assertTrue("\"proposal\" (decide whether to build something and what" in refine, "the refine prompt offers the proposal kind")
    }

    @Test
    fun `an earlier intake resolves through the brief's intakes - the refiner writes its ticket and lists blockedBy, a work run checks the ticket`() {
        val file = script()
        assumeTrue(file != null, "smith/run-smith.sh is not in this build context")
        val text = file!!.readText()
        val refine = text.substringAfter("refining exactly one intake ask").substringBefore("\nPROMPT\n")
        assertTrue("\"blockedBy\" (array of strings, optional)" in refine, "the refine prompt states blockedBy's type")
        // Only with intakes in the brief: the guard, then the text.
        val guard = "jq -r '.intakes[0].intake // empty'"
        assertTrue(text.split(guard).size == 3, "one intakes guard for refine, one for the other modes")
        val refineText = text.substringAfter(guard).substringBefore("\nfi\n")
        listOf("\\\"intakes\\\"", "write its \\\"ticket\\\" #T in Blocked by and in Stop and ask", "never make the ticket depend on #N itself",
            "Blocked by the ticket intake #N files (the engine resolves it)", "list its intake id in \\\"blockedBy\\\"", "\\\"ended\\\" without a ticket")
            .forEach { assertTrue(it in refineText, "$it in the refine intakes text") }
        val workText = text.substringAfter(guard).substringAfter(guard).substringBefore("\n  fi\n")
        listOf("read it as that intake's \\\"ticket\\\" #T and check #T", "stop and ask")
            .forEach { assertTrue(it in workText, "$it in the shared intakes text") }
    }

    @Test
    fun `a ticket that builds a proposal - the design plans from the doc, the work marks it built, the review checks both`() {
        val file = script()
        assumeTrue(file != null, "smith/run-smith.sh is not in this build context")
        val text = file!!.readText()
        assertTrue("BUILDS=\"\$(printf '%s' \"\${BRIEF}\" | jq -r '.builds // empty'" in text, "the doc comes from the brief's builds")
        val buildsText = text.substringAfter("[ -n \"\${BUILDS}\" ] && BUILDS_TEXT=\"").substringBefore("\n")
        listOf("\\\"Implementation outline\\\"", "\\\"Proposal\\\" section as binding", "only where the doc leaves gaps")
            .forEach { assertTrue(it in buildsText, "$it in BUILDS_TEXT") }
        assertTrue("\${BUILDS_TEXT}" in text.substringAfter("designing exactly one feature").substringBefore("\nPROMPT\n"), "the design prompt says it")
        val builtText = text.substringAfter("if [ -n \"\${BUILDS}\" ]; then\n    BUILT_TEXT=\"").substringBefore("\"\n  fi")
        listOf("built in #<n>", "\\\"Decision log:\\\"", "\\\"Status: built\\\"", "pr.number", "this ticket's number, #\${SMITH_ISSUE}", "Part N of M")
            .forEach { assertTrue(it in builtText, "$it in BUILT_TEXT") }
        assertTrue("\${BUG_TEXT}\${PLAN_TEXT}\${BUILT_TEXT}" in text.substringAfter("working exactly one ticket").substringBefore("\nPROMPT\n"), "the work prompt says it")
        assertTrue("\${BUG_TEXT}\${PLAN_TEXT}\${BUILT_TEXT}\${STEER_TEXT}" in text.substringAfter("working exactly one ticket").substringBefore("\nPROMPT\n"), "the work prompt carries the steer")
        val steerText = text.substringAfter("    STEER_TEXT=\"\n").substringBefore("\"\n")
        assertTrue("A steer binds like an answered question" in steerText, steerText)
        val steerCheck = text.substringAfter("    STEER_CHECK=\"").substringBefore("\"\n")
        assertTrue("one the change does not address is a finding" in steerCheck, steerCheck)
        assertTrue("FOCUS=\"\${FOCUS}\${STEER_CHECK}\"" in text, "the review and security focus carry the steer check")
        assertTrue("acceptance\", check it" in text && "mark it unchecked.\${STEER_CHECK}" in text, "the accept prompt carries the steer check")
        val review = text.substringAfter("FOCUS=\"a correctness review").substringBefore("PASS=approved")
        listOf("[ -n \"\${BUILDS}\" ]", "built in #<n>", "departure from the doc's \\\"Proposal\\\" section a finding")
            .forEach { assertTrue(it in review, "$it in the review focus") }
    }

    @Test
    fun `the correctness review asks for changes when a moved or removed setting leaves its old home`() {
        val file = script()
        assumeTrue(file != null, "smith/run-smith.sh is not in this build context")
        val review = file!!.readText().substringAfter("FOCUS=\"a correctness review").substringBefore("\n")
        listOf("A setting that moves leaves nothing behind", "engine_setting row", "@Value read", ".env.example key",
            "test that sets it", "default or fallback", "harmless when unset", "a finding (changes)")
            .forEach { assertTrue(it in review, "$it in the review focus") }
    }

    @Test
    fun `the work prompt tells a child with a live parent to ask it where it would otherwise assume, only from the brief's liveParent, with the confidence rule unchanged`() {
        val file = script()
        assumeTrue(file != null, "smith/run-smith.sh is not in this build context")
        val text = file!!.readText()
        assertTrue(".liveParent.number" in text, "the work prompt reads the brief's liveParent")
        val block = text.substringAfter("LIVE_PARENT_TEXT=\"Your parent").substringBefore("\n\"\n")
        listOf("is live", "the engine puts your question to the parent first", "answers it from the whole split's view or hands it to the user",
            "\\\"answers\\\" with \\\"by\\\" \\\"#\${LIVE_PARENT}\\\"", "where you would otherwise assume", "ask the parent instead", "unchanged", "only who answers it changed")
            .forEach { assertTrue(it in block, "$it in LIVE_PARENT_TEXT") }
        assertTrue("[ -n \"\${LIVE_PARENT}\" ] && LIVE_PARENT_TEXT=" in text, "the paragraph is rendered only with a liveParent")
        assertTrue("\${STEER_TEXT}\${LIVE_PARENT_TEXT}" in text, "the paragraph sits in the work prompt")
        assertTrue("confidence below 0.7" in text, "the design and propose confidence rule is as it was")
    }

    @Test
    fun `the work prompt gives the Allowed scope as guidance, with no fence and no rescope`() {
        val file = script()
        assumeTrue(file != null, "smith/run-smith.sh is not in this build context")
        val text = file!!.readText()
        val rule = text.substringAfter("WORK_SCOPE_RULE=\"").substringBefore("\n")
        listOf("Allowed scope is guidance, not a gate", "the reviewer checks what you changed", "return \\\"ask\\\"")
            .forEach { assertTrue(it in rule, "$it in WORK_SCOPE_RULE") }
        assertTrue("rescope" !in text, "no rescope outcome, mode or prompt")
    }

    @Test
    fun `a propose round on an unpushed draft with no PR bundles from the clone's commit, not the draft's tip`() {
        // #1322: round 1 ended in ask, so its draft was never pushed; round 2's bundle took the draft's tip as its base.
        val file = script()
        assumeTrue(file != null, "smith/run-smith.sh is not in this build context")
        val text = file!!.readText()
        val block = "  PR_BRANCH=\"\"\n" + text.substringAfter("\n  PR_BRANCH=\"\"\n").substringBefore("\n  echo \"smith: cloned")
        assertTrue(block.trimEnd().endsWith("BASE=\"\$(git rev-parse \"\${DRAFT_BASE:-HEAD}\")\""), "the extracted block ends at BASE")
        val dir = java.nio.file.Files.createTempDirectory("propose-base").toFile()
        try {
            val setup = """
                set -e
                g() { git -c user.name=a -c user.email=a@b "${'$'}@"; }
                git init -q -b main origin; g -C origin commit -q --allow-empty -m main1
                git clone -q origin r1; g -C r1 checkout -q -b smith/issue-1
                g -C r1 commit -q --allow-empty -m "round-1 draft, never pushed"
                git -C r1 bundle create -q ../draft.bundle main..smith/issue-1
                git clone -q origin r2
            """.trimIndent()
            val round = "set -e\ncd r2\nMODE=propose BRIEF= BRANCH=smith/issue-1 BUNDLE_IN=../draft.bundle\n" + block + "\necho \"BASE=\${BASE}\""
            fun bash(script: String): String {
                val p = ProcessBuilder("bash", "-c", script).directory(dir).redirectErrorStream(true).start()
                val out = p.inputStream.bufferedReader().readText()
                assertTrue(p.waitFor() == 0, out)
                return out
            }
            bash(setup)
            val base = bash(round).lines().first { it.startsWith("BASE=") }.removePrefix("BASE=")
            val main = bash("git -C origin rev-parse main").trim()
            assertTrue(base == main, "the bundle base $base is the remote's main $main, not the unpushed draft")
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `the security prompt reviews a merge entry against its first parent, and the script parses`() {
        val file = script()
        assumeTrue(file != null, "smith/run-smith.sh is not in this build context")
        val security = file!!.readText().substringAfter("if [ \"\${MODE}\" = \"security\" ]; then\n      FOCUS=").substringBefore("PASS=passed")
        listOf(".merge.merge", "the brief's \\\"merge\\\"", "first parent", "git diff HEAD^1 HEAD", "already-merged commits")
            .forEach { assertTrue(it in security, "$it in the security prompt") }
        val check = ProcessBuilder("bash", "-n", file.path).redirectErrorStream(true).start()
        val out = check.inputStream.bufferedReader().readText()
        assertTrue(check.waitFor() == 0, "bash -n: $out")
    }

    @Test
    fun `frakworks smiths run the test class they change, never the full build, and every prompt carries it`() {
        val file = script()
        assumeTrue(file != null, "smith/run-smith.sh is not in this build context")
        val text = file!!.readText()
        val frakworks = text.substringAfter("if [ \"\${SMITH_REPO}\" != \"\${FRAMEWORK_REPO}\" ]; then\n")
            .substringAfter("\n  else\n").substringBefore("\n  fi")
        listOf("gradle :engine-<module>:test --tests <Class> --no-daemon --offline", "before you end each round",
            "Never a full gradle build or gradle test", "the engine check", "\\\"ci\\\" finding")
            .forEach { assertTrue(it in frakworks, "$it in the frakworks build text") }
        assertTrue("SMITH_LOCAL_BUILD" !in text, "no local-build switch remains")
        assertTrue("\${BUILD_TEXT}" in text.substringAfter("COMMON="), "the shared prompt says it")
    }

    @Test
    fun `the fix prompt offers the engine's remedy menu, and the diagnose prompt changes nothing and keeps to 40 characters`() {
        val file = script()
        assumeTrue(file != null, "smith/run-smith.sh is not in this build context")
        val text = file!!.readText()
        listOf("\"retry\"", "\"unfixable\"", "fix failed", "cannot touch the engine").forEach { assertTrue(it in text, it) }
        assertTrue("40 characters or fewer" in text)
        assertTrue("diagnose-only run: change nothing anywhere" in text)
        assertTrue(Regex("""fix\)\s+echo '\{"outcome":"unfixable"""").containsMatchIn(text), "a dry fix run is unfixable")
        assertTrue(Regex("""diagnose\)\s+echo '\{"outcome":"proposed"""").containsMatchIn(text), "a dry diagnose run proposes")
    }

    @Test
    fun `the doctor prompt offers the doctor's remedy menu, changes nothing, and a dry doctor run is healthy`() {
        val file = script()
        assumeTrue(file != null, "smith/run-smith.sh is not in this build context")
        val text = file!!.readText()
        val doctor = text.substringAfter("elif [ \"\${MODE}\" = \"doctor\" ]; then").substringBefore("\nPROMPT\n")
        listOf("\"healthy\"", "\"unfixable\"", "\"retry\"", "\"collect-exit\"", "\"run-job\"", "\"target\"", "the brief's \"stuck\"",
            "You cannot touch the engine, its database or Operaton", "\\\$ENGINE_URL").forEach { assertTrue(it in doctor, it) }
        assertTrue(Regex("""doctor\)\s+echo '\{"outcome":"healthy"""").containsMatchIn(text), "a dry doctor run is healthy")
        assertTrue(Regex("""reproduce\|verify\|design\|replan\|accept\|fix\|diagnose\|doctor\) ;;""").containsMatchIn(text),
            "a doctor run is on the default branch")
    }

    @Test
    fun `the shared prompt tells a Smith to use ENGINE_URL for engine checks, with no port and only when it is set`() {
        val file = script()
        assumeTrue(file != null, "smith/run-smith.sh is not in this build context")
        val text = file!!.readText()
        val engineUrlText = text.substringAfter("ENGINE_URL_TEXT=\"\"\n  if [ -n \"\${ENGINE_URL:-}\" ]; then\n    ENGINE_URL_TEXT=\"")
            .substringBefore("\"\n  fi")
        assertTrue("\\\$ENGINE_URL" in engineUrlText, "the instruction names ENGINE_URL: $engineUrlText")
        listOf("engine", "hardcoded host or port").forEach { assertTrue(it in engineUrlText, "$it in the ENGINE_URL instruction") }
        assertTrue(!Regex("""808[0-9]|8090""").containsMatchIn(engineUrlText), "no port number in the ENGINE_URL instruction")
        assertTrue("\${ENGINE_URL_TEXT}" in text.substringAfter("COMMON=\"You were started by the engine").substringBefore("\"\n"),
            "the shared COMMON prompt carries the ENGINE_URL instruction")
    }

    @Test
    fun `with ENGINE_URL the claude call gets the engine's frakworks, frakworks-engine-diagnostics and hitlp servers over HTTP, with no credential`() {
        val file = script()
        assumeTrue(file != null, "smith/run-smith.sh is not in this build context")
        val text = file!!.readText()
        val call = text.substringAfter("status=0\nclaude -p").substringBefore("|| status=$?")
        assertTrue("\"\${MCP_FLAGS[@]}\"" in call, "the claude call passes MCP_FLAGS: $call")
        assertTrue("MCP_FLAGS=(--mcp-config \"\${HOME}/frakworks-mcp.json\" --strict-mcp-config)" in text)
        assertTrue("if [ -n \"\${ENGINE_URL:-}\" ]; then" in text, "no ENGINE_URL, no MCP flags")
        val docker = File(file.parentFile, "Dockerfile").readText()
        assertTrue("COPY frakworks-mcp-config.sh /usr/local/bin/frakworks-mcp-config" in docker, "the config script in smith/Dockerfile")
        assertTrue("desktop-src" !in docker, "the server is the engine's: nothing of the desktop is copied in")

        val config = File(file.parentFile, "frakworks-mcp-config.sh")
        val jq = runCatching { ProcessBuilder("jq", "--version").start().waitFor() == 0 }.getOrDefault(false)
        assumeTrue(jq, "jq is not on PATH")
        val pb = ProcessBuilder("bash", config.path).redirectErrorStream(true)
        pb.environment().apply {
            clear(); put("PATH", System.getenv("PATH")); put("ENGINE_URL", "http://engine:8080/"); put("SMITH_ID", "run-1")
            put("SMITH_ISSUE", "7"); put("SMITH_REPO", "acme/widgets"); put("GH_TOKEN", "gh-secret"); put("CLAUDE_CODE_OAUTH_TOKEN", "oauth-secret")
        }
        val p = pb.start()
        val out = p.inputStream.readAllBytes().decodeToString()
        assertTrue(p.waitFor() == 0, out)
        val json: Map<String, Map<String, Map<String, Any>>> = tools.jackson.module.kotlin.jacksonObjectMapper().readValue(out)
        assertTrue(json.getValue("mcpServers").keys == setOf("frakworks", "frakworks-engine-diagnostics"), out)
        val server = json.getValue("mcpServers").getValue("frakworks")
        assertTrue(server == mapOf("type" to "http", "url" to "http://engine:8080/mcp/frakworks"), out)
        // The engine's own server (engine/README.md, "The diagnostics server"): http at ENGINE_URL, no header, no env.
        val diagnostics = json.getValue("mcpServers").getValue("frakworks-engine-diagnostics")
        assertTrue(diagnostics == mapOf("type" to "http", "url" to "http://engine:8080/mcp/frakworks-engine-diagnostics"), out)
        // #2484: the engine serves no HITLP, so there is no hitlp server.
        assertTrue("hitlp" !in out, out)
        assertTrue("secret" !in out, "no credential in the MCP config")
        assertTrue(listOf("token", "authorization", "header", "password").none { it in out.lowercase() }, "no credential-shaped key: $out")

        val settings: Map<String, Map<String, Any>> =
            tools.jackson.module.kotlin.jacksonObjectMapper().readValue(File(file.parentFile, "settings.json").readText())
        val allow = settings.getValue("permissions").getValue("allow") as List<*>
        assertTrue("mcp__frakworks" in allow && "mcp__frakworks-engine-diagnostics" in allow, "settings.json allows the two servers: $allow")
        assertTrue(allow.none { it.toString().startsWith("mcp__hitlp") }, "settings.json allows no mcp__hitlp: $allow")
    }

    @Test
    fun `a bundle the Smith wrote itself is removed before the script decides whether to bundle`() {
        val file = script()
        assumeTrue(file != null, "smith/run-smith.sh is not in this checkout")
        val text = file!!.readText()
        val rm = text.indexOf("rm -f \"\${OUT}/commits.bundle\"")
        val create = text.indexOf("git bundle create --quiet \"\${OUT}/commits.bundle\"")
        assertTrue(rm in 0 until create, "the stale bundle is removed before the work/propose bundle is made")
    }
}
