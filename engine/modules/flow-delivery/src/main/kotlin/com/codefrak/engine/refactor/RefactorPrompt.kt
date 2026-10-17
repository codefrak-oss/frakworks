package com.codefrak.engine.refactor

/**
 * The prompt a refactoring run's job-mode Smith gets (engine/README.md, "Refactoring jobs"): the repo's values verbatim,
 * the caps, the keys already open (not to be proposed again), and the exact shape of the `summary` it writes. A pure
 * function, so its test needs no Spring.
 */
object RefactorPrompt {
    const val VALUES_HEADING = "## The repo's refactoring values"
    const val OPEN_HEADING = "## Do not propose these keys"
    const val SHAPE_HEADING = "## What you write"

    fun build(values: String, maxTickets: Int, maxLines: Int, openKeys: Collection<String>): String = buildString {
        append("# Refactoring inspection\n\n")
        append("You are inspecting this repo's code against its refactoring values, below, and proposing at most $maxTickets small, ")
        append("self-contained refactorings. You change nothing: your product is the proposals in `result.json`. Each proposal is one ")
        append("PR-sized change of at most $maxLines changed lines (added plus removed; code moved verbatim, as in a file split, counts its moved lines once, not as both added and removed, plus every other line of the new file: its KDoc, package line and imports), with a stable `key`: a slug of the module or path it ")
        append("touches (letters, digits, dots, slashes, dashes and underscores), the same for the same work on any later run, so the engine ")
        append("can tell it from work already filed. Prefer the changes that move the code furthest toward the values for their size; read ")
        append("the repo's own conventions (CLAUDE.md, AGENTS.md, READMEs) first and propose nothing that goes against them. ")
        append("Before you report code as untested, search the test sources for it (by the function or class name): a subagent's ")
        append("survey is a lead, not a finding, and a gap it names is yours to confirm.\n\n")
        append(VALUES_HEADING).append("\n\n").append(values.trimEnd()).append("\n\n")
        append(OPEN_HEADING).append("\n\n")
        if (openKeys.isEmpty()) append("None: no refactoring ticket from an earlier run is open.\n\n")
        else {
            append("These keys are on open tickets from earlier runs; a proposal with one of them is dropped unread:\n\n")
            openKeys.sorted().forEach { append("- `").append(it).append("`\n") }
            append('\n')
        }
        append(SHAPE_HEADING).append("\n\n")
        append("`outcome` is `done`, and `summary` is exactly this object (nothing else is read):\n\n")
        append("```json\n{\"proposals\": [{\"key\": \"<slug>\", \"title\": \"<ticket title>\", \"body\": \"<ticket body, markdown>\", \"lines\": <estimated changed lines>}]}\n```\n\n")
        append("At most $maxTickets proposals, each `lines` at most $maxLines (a bigger one is dropped), no two with the same `key`. ")
        append("Each `body` has these sections, in this order: `## Goal` (what is true when done); `## Acceptance criteria` (a checklist, ")
        append("whose first item is always `at most $maxLines changed lines`); `## Allowed scope` (the paths it touches, and that it may not ")
        append("change behaviour); `## Context` (how this change moves the code toward the values above, naming the values it serves; the ")
        append("engine quotes the values after it). Say in the body what you saw, with paths, so the Smith that builds it does not look again. ")
        append("With nothing worth proposing, `summary` is `{\"proposals\": [], \"inspected\": [\"<path>\", ...]}`, naming every file or ")
        append("directory you read against the values, and `outcome` is `done`; an empty scan without `inspected` is recorded as failed, ")
        append("so survey the code before you conclude there is nothing.\n")
    }
}
