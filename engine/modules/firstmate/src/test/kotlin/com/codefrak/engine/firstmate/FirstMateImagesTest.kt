package com.codefrak.engine.firstmate

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.web.server.ResponseStatusException
import java.util.Base64
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** [FirstMateImages] against the desktop's rules (`observability/desktop/src/first-mate-images.js`), same wording. */
class FirstMateImagesTest {
    private val png = "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNkYPhfDwAChwGA60e6kgAAAABJRU5ErkJggg=="
    private fun image(type: String, data: String = png) = mapOf("media_type" to type, "data" to data)
    private fun refusal(images: Any?): String = assertThrows<ResponseStatusException> { FirstMateImages.validate(images) }.reason!!

    @Test
    fun `none, and a valid PNG, pass`() {
        assertEquals(emptyList(), FirstMateImages.validate(null))
        assertEquals(listOf(image("image/png")), FirstMateImages.validate(listOf(image("image/png") + ("extra" to "dropped"))))
        assertNull(FirstMateImages.error(image("image/png")))
    }

    @Test
    fun `each type's magic bytes are checked`() {
        val jpeg = Base64.getEncoder().encodeToString(byteArrayOf(0xff.toByte(), 0xd8.toByte(), 0xff.toByte(), 0xe0.toByte(), 0, 16))
        val gif = Base64.getEncoder().encodeToString("GIF89a\u0001\u0000\u0001\u0000".toByteArray(Charsets.ISO_8859_1))
        val webp = Base64.getEncoder().encodeToString("RIFF\u0000\u0000\u0000\u0000WEBPVP8 ".toByteArray(Charsets.ISO_8859_1))
        assertNull(FirstMateImages.error(image("image/jpeg", jpeg)))
        assertNull(FirstMateImages.error(image("image/gif", gif)))
        assertNull(FirstMateImages.error(image("image/webp", webp)))
        assertEquals("image 1 is not a JPEG file; only PNG, JPEG, GIF and WebP are accepted", FirstMateImages.error(image("image/jpeg", png)))
        assertEquals("image 2 is not a PNG file; only PNG, JPEG, GIF and WebP are accepted", refusal(listOf(image("image/png"), image("image/png", gif))))
    }

    @Test
    fun `the list, count, type, base64 shape and size are refused with the desktop's wording`() {
        assertEquals("images must be a list", refusal("x"))
        assertEquals("6 images; at most 5 go with one message", refusal(List(6) { image("image/png") }))
        assertEquals("image 1 is text/plain; only PNG, JPEG, GIF and WebP are accepted", refusal(listOf(image("text/plain"))))
        assertEquals("image 1 is of no type; only PNG, JPEG, GIF and WebP are accepted", refusal(listOf(mapOf("data" to png))))
        assertEquals("image 1 is not an image", refusal(listOf("png")))
        assertEquals("image 1 is not base64 data", refusal(listOf(image("image/png", "not base64!"))))
        assertEquals("image 1 is not base64 data", refusal(listOf(image("image/png", ""))))
        val big = "A".repeat(((FirstMateImages.MAX_BYTES + 3) / 3 * 4).toInt() + 4)
        assertEquals("image 1 is 5.0 MB; the limit is 5.0 MB", refusal(listOf(image("image/png", big))))
        assertEquals(1L, FirstMateImages.decodedBytes("AA=="))
        assertEquals(3L, FirstMateImages.decodedBytes("AAAA"))
    }
}
