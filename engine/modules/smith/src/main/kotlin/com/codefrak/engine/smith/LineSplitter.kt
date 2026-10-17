package com.codefrak.engine.smith

import java.io.ByteArrayOutputStream

/**
 * Reassembles lines from a byte stream cut anywhere (Docker's log frames):
 * each complete line, UTF-8, without `\n` or a trailing `\r`, goes to
 * `onLine`; [flush] hands over a last line with no newline.
 */
class LineSplitter(private val onLine: (String) -> Unit) {
    private val buffer = ByteArrayOutputStream()

    @Synchronized fun accept(bytes: ByteArray) {
        var start = 0
        for (i in bytes.indices) if (bytes[i] == '\n'.code.toByte()) {
            buffer.write(bytes, start, i - start)
            emit()
            start = i + 1
        }
        buffer.write(bytes, start, bytes.size - start)
    }

    @Synchronized fun flush() { if (buffer.size() > 0) emit() }

    private fun emit() {
        val line = buffer.toString(Charsets.UTF_8).removeSuffix("\r")
        buffer.reset()
        onLine(line)
    }
}
