package com.codefrak.engine.github

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class RepoTokensTest {
    @Test
    fun `splits owner and name`() = assertEquals("owner" to "name", splitRepo("owner/name"))

    @Test
    fun `no slash throws`() {
        val error = assertFailsWith<IllegalArgumentException> { splitRepo("ownername") }
        assertTrue(error.message!!.contains("owner/name"))
    }

    @Test
    fun `extra slash stays in name, since split uses limit 2`() =
        assertEquals("owner" to "name/with/slash", splitRepo("owner/name/with/slash"))
}
