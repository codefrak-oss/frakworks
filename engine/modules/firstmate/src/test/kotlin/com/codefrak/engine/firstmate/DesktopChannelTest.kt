package com.codefrak.engine.firstmate

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Duration
import java.util.Base64
import java.util.concurrent.TimeUnit

class DesktopChannelTest {
    private val channel = DesktopChannel(Duration.ofMillis(300))

    private fun answerNext(d: DesktopChannel.Desktop, login: String, error: String? = null): String? {
        val frame = d.frames.poll(5, TimeUnit.SECONDS)!!
        return channel.answer(login, frame["requestId"] as String, Base64.getEncoder().encodeToString(byteArrayOf(1, 2)), "image/png", error)
    }

    @Test
    fun `a capture round trips through the stubbed desktop`() {
        val d = channel.connect("me", true)
        val t = Thread.ofVirtual().start { assertEquals(null, answerNext(d, "me")) }
        val c = channel.capture("me", null)
        t.join()
        assertTrue(c is DesktopChannel.Capture.Image && c.bytes.contentEquals(byteArrayOf(1, 2)), c.toString())
    }

    @Test
    fun `no desktop, the setting off, a timeout and another login's answer are refused`() {
        assertEquals(DesktopChannel.Capture.Refused(DesktopChannel.NO_DESKTOP), channel.capture("me", null))
        val d = channel.connect("me", false)
        assertEquals(DesktopChannel.Capture.Refused(DesktopChannel.SETTING_OFF), channel.capture("me", "left"))
        assertTrue(d.frames.isEmpty(), "no capture is asked with the setting off")
        assertTrue(channel.report("me", d.id, allowed = true, focused = null))
        assertFalse(channel.report("you", d.id, allowed = false, focused = null))
        val t = Thread.ofVirtual().start { assertTrue(answerNext(d, "you")!!.startsWith("no capture request")) }
        assertEquals(DesktopChannel.Capture.Refused(DesktopChannel.TIMED_OUT), channel.capture("me", "right"))
        t.join()
    }

    @Test
    fun `the most recently focused desktop answers, else the newest`() {
        val a = channel.connect("me", true)
        val b = channel.connect("me", true)
        assertEquals(b.id, channel.pick("me")!!.id)
        channel.report("me", a.id, allowed = null, focused = true)
        assertEquals(a.id, channel.pick("ME")!!.id)
        channel.disconnect(a.id)
        assertEquals(b.id, channel.pick("me")!!.id)
    }
}
