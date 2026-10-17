package com.codefrak.engine.chore

import org.junit.jupiter.api.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** [HitlpPaths]: the hitlp module and its migrations wait for the user; nothing else does. */
class HitlpPathsTest {
    @Test
    fun `a file in the hitlp module or a hitlp migration matches`() {
        assertTrue(HitlpPaths.isHitlp("engine/modules/hitlp/src/main/kotlin/com/codefrak/engine/hitlp/HitlpTasks.kt"))
        assertTrue(HitlpPaths.isHitlp("engine/modules/hitlp/build.gradle.kts"))
        assertTrue(HitlpPaths.isHitlp("engine/modules/app/src/main/resources/db/migration/V68__hitlp_task.sql"))
        assertTrue(HitlpPaths.matches(listOf("README.md", "engine/modules/hitlp/README.md")))
    }

    @Test
    fun `other modules, other migrations and docs do not`() {
        assertFalse(HitlpPaths.isHitlp("engine/modules/api/src/main/kotlin/com/codefrak/engine/board/Needs.kt"))
        assertFalse(HitlpPaths.isHitlp("engine/modules/app/src/main/resources/db/migration/V67__target_repo.sql"))
        assertFalse(HitlpPaths.isHitlp("docs/proposals/hitlp-standing-approvals.md"))
        assertFalse(HitlpPaths.isHitlp("engine/modules/hitlpish/x.kt"))
        assertFalse(HitlpPaths.matches(emptyList()))
    }
}
