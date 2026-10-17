package com.codefrak.engine.github

/** The `-<sha>` lines of a bundle's header (v2 or v3), which ends at the first empty line. */
internal fun bundlePrerequisites(bundle: ByteArray): List<String> {
    val end = (0 until bundle.size - 1).firstOrNull { bundle[it] == '\n'.code.toByte() && bundle[it + 1] == '\n'.code.toByte() } ?: bundle.size
    return String(bundle, 0, end, Charsets.UTF_8).lines().filter { it.startsWith("-") }.map { it.drop(1).substringBefore(' ') }
}
