package com.codefrak.engine

/** A Prometheus text-format label value, with `\`, `"` and newline escaped. */
fun promLabel(v: String): String = v.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n")
