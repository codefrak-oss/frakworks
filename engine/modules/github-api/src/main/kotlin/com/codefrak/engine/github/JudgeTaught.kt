package com.codefrak.engine.github

/** Published when a judge teach committed a line under a rubric's `## Decisions` in [repo] (`JudgeTeachController`). */
data class JudgeTaught(val repo: String)
