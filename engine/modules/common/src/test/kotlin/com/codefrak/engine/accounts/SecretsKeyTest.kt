package com.codefrak.engine.accounts

import org.junit.jupiter.api.Test
import java.util.Base64
import javax.crypto.AEADBadTagException
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse

/** [SecretsKey]: `seal`/`open` as a pure AES-256-GCM round trip, and the unconfigured case. */
class SecretsKeyTest {
    private val key = SecretsKey(Base64.getEncoder().encodeToString(ByteArray(32)))

    @Test
    fun `seal then open with the same aad returns the original plaintext`() {
        val sealed = key.seal("secret", "row-1")
        assertEquals("secret", key.open(sealed, "row-1"))
    }

    @Test
    fun `open with a different aad than it was sealed with throws`() {
        val sealed = key.seal("secret", "row-1")
        assertFailsWith<AEADBadTagException> { key.open(sealed, "row-2") }
    }

    @Test
    fun `an unconfigured key is not configured, and seal and open both throw`() {
        val unconfigured = SecretsKey("")
        assertFalse(unconfigured.configured)
        assertFailsWith<IllegalStateException> { unconfigured.seal("secret", "row-1") }
        assertFailsWith<IllegalStateException> { unconfigured.open(byteArrayOf(), "row-1") }
    }
}
