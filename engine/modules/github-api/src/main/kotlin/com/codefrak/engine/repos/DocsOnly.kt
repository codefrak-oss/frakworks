package com.codefrak.engine.repos

/**
 * Whether a change touches only documentation, from the paths a push changed against the default branch
 * (engine/README.md, "Docs path"): markdown anywhere (`*.md`, `*.mdx`), anything under a `docs/` directory, and the
 * repo's `README*`, `CLAUDE.md` and `AGENTS.md`. A change with any other path, or with no paths, is not docs-only and
 * takes the full pipeline.
 */
object DocsOnly {
    private val EXTENSIONS = setOf("md", "mdx", "markdown")

    fun of(paths: List<String>): Boolean = paths.isNotEmpty() && paths.all(::isDoc)

    fun isDoc(path: String): Boolean {
        val p = path.trim().trimStart('/')
        if (p.isEmpty() || p.endsWith("/")) return false
        val file = p.substringAfterLast('/')
        if (file.substringAfterLast('.', "").lowercase() in EXTENSIONS) return true
        if (p.split('/').dropLast(1).any { it == "docs" }) return true
        return file.startsWith("README")
    }
}
