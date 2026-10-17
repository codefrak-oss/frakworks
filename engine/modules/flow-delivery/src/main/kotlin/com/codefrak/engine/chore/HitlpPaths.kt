package com.codefrak.engine.chore

/**
 * The paths whose change waits for the user's acceptance before the merge, whatever the automated review said
 * (engine/README.md, "Hitlp changes wait for the user"): the hitlp
 * module, engine/modules/hitlp/, and its Flyway migrations, a db/migration file whose name has `__hitlp`.
 */
object HitlpPaths {
    const val MODULE = "engine/modules/hitlp/"

    fun matches(paths: List<String>): Boolean = paths.any(::isHitlp)

    fun isHitlp(path: String): Boolean {
        val p = path.trim().trimStart('/')
        if (p.startsWith(MODULE)) return true
        val file = p.substringAfterLast('/')
        return p.substringBeforeLast('/', "").endsWith("db/migration") && "__hitlp" in file.lowercase()
    }
}
