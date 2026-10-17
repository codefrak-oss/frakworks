package com.codefrak.engine.hitlp

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.security.MessageDigest

/**
 * The vendored HITLP spec files (`src/main/resources/hitlp/`) are the ones `hitlp/SHA256SUMS` lists, byte for byte:
 * an upgrade is a deliberate copy, a new digest list and a new `HITLP_SPEC_COMMIT`. Nothing is fetched.
 */
class HitlpVendoredFilesTest {
    private fun resource(path: String): ByteArray? = javaClass.getResourceAsStream("/hitlp/$path")?.use { it.readBytes() }

    private fun sha256(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    @Test
    fun `every vendored schema and example matches its digest in SHA256SUMS`() {
        val sums = String(requireNotNull(resource("SHA256SUMS")) { "hitlp/SHA256SUMS is vendored" }).lines().filter { it.isNotBlank() }
            .associate { line -> line.substringAfter("  ").trim() to line.substringBefore(" ").trim() }
        assertEquals(10, sums.size, "six schemas and four examples: $sums")
        assertEquals(HitlpSchemas.NAMES.map { "schemas/$it.schema.json" }.toSet(), sums.keys.filter { it.startsWith("schemas/") }.toSet())
        assertEquals(setOf("examples/ask.request.json", "examples/ask.response.json", "examples/approve.request.json", "examples/approve.response.json"),
            sums.keys.filter { it.startsWith("examples/") }.toSet())
        for ((path, digest) in sums) {
            val bytes = requireNotNull(resource(path)) { "hitlp/$path is vendored" }
            assertEquals(digest, sha256(bytes), "hitlp/$path differs from SHA256SUMS: copy it again from the spec commit and update the list")
        }
    }

    @Test
    fun `HITLP_SPEC_COMMIT names the spec commit the files came from`() {
        val commit = String(requireNotNull(resource("HITLP_SPEC_COMMIT")) { "hitlp/HITLP_SPEC_COMMIT is vendored" }).trim()
        assertTrue(Regex("[0-9a-f]{7,40}").matches(commit), commit)
        // 0.1 was ba0a272 (hitlp-support part 1); #2447 moved to 0.2, `main` of codefrak-oss/hitlp on 2026-10-11.
        assertTrue(commit.startsWith("aa3dde4"), "#2447 vendored 0.2 at aa3dde4: $commit")
    }
}
