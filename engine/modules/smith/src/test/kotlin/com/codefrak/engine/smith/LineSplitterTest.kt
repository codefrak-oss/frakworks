package com.codefrak.engine.smith

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

class LineSplitterTest {
    @Test
    fun `lines are reassembled from frames cut anywhere`() {
        val out = mutableListOf<String>()
        val split = LineSplitter { out += it }
        split.accept("{\"a\":1}\n{\"b\"".toByteArray())
        split.accept(":2}\r\n\n{\"é\":".toByteArray())
        split.accept("3}".toByteArray())
        split.flush()
        assertEquals(listOf("{\"a\":1}", "{\"b\":2}", "", "{\"é\":3}"), out)
    }
}
