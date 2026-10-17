package com.codefrak.engine.feature

/** The two ways a feature runs (engine/README.md, "Feature"): its label is `feature:<mode>`. */
object FeatureMode {
    const val AUTO = "auto"
    const val CONFIRM = "confirm"
    val ALL = setOf(AUTO, CONFIRM)
    val LABELS = ALL.map { label(it) }.toSet()
    fun label(mode: String) = "feature:$mode"
    fun ofLabel(label: String) = label.removePrefix("feature:")
}
