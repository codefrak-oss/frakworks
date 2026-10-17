package com.codefrak.engine.smith

/** A reference that can move under its name and comes from a registry: what the Docker sandbox's `ensureImage` refreshes, and a pod pulls `Always`. */
fun refreshable(image: String): Boolean {
    if ('@' in image) return false
    val (repo, tag) = splitImage(image)
    if (tag.startsWith("sha-")) return false
    val first = repo.substringBefore('/', "")
    return first.isNotEmpty() && ('.' in first || ':' in first || first == "localhost")
}

/** `repo[:tag]` as repo and tag (`latest` when none); the registry's `:port` is part of the repo. */
fun splitImage(image: String): Pair<String, String> {
    val colon = image.lastIndexOf(':')
    return if (colon > image.lastIndexOf('/')) image.substring(0, colon) to image.substring(colon + 1) else image to "latest"
}
