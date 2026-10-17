package com.codefrak.engine.smith

import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers.anyString
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import org.operaton.bpm.engine.delegate.DelegateExecution
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** `read result`'s dispatch of a flow's own modes to the [ResultHandler] its package registers (engine/README.md, "Engine modules"). */
class ResultHandlerDispatchTest {
    /** Answers `outcome` of its result, or breaks the rules when the result says `broken`; records what it was handed. */
    private class Handler(override val modes: Set<String>) : ResultHandler {
        val handled = mutableListOf<Pair<String, String>>()
        override fun handle(execution: DelegateExecution, mode: String, bytes: ByteArray, enforce: (List<String>) -> Unit): String {
            val text = String(bytes)
            handled += mode to text
            enforce(listOfNotNull("it is broken".takeIf { text == "broken" }))
            return "done-$mode"
        }
    }

    private fun execution(mode: String, output: String): Pair<DelegateExecution, MutableMap<String, Any?>> {
        val vars = mapOf(Vars.RESULT to "success", Vars.MODE to mode, Vars.OUTPUT to output.toByteArray())
        val set = mutableMapOf<String, Any?>()
        val execution = mock(DelegateExecution::class.java)
        `when`(execution.processInstanceId).thenReturn("dispatch-1")
        `when`(execution.getVariable(anyString())).thenAnswer { vars[it.getArgument<String>(0)] ?: set[it.getArgument<String>(0)] }
        `when`(execution.setVariable(anyString(), org.mockito.ArgumentMatchers.any())).thenAnswer { set[it.getArgument<String>(0)] = it.getArgument(1); null }
        return execution to set
    }

    @Test
    fun `a mode goes to the handler that registered it, and its answer is the outcome`() {
        val first = Handler(setOf("test-one"))
        val second = Handler(setOf("test-two", "test-three"))
        val read = ReadResultDelegate(listOf(first, second))

        val (one, oneSet) = execution("test-one", "a")
        read.execute(one)
        assertEquals("done-test-one", oneSet["outcome"])
        val (three, threeSet) = execution("test-three", "b")
        read.execute(three)
        assertEquals("done-test-three", threeSet["outcome"])

        assertEquals(listOf("test-one" to "a"), first.handled)
        assertEquals(listOf("test-three" to "b"), second.handled)
    }

    @Test
    fun `a handler's broken rule is the run's incident`() {
        val read = ReadResultDelegate(listOf(Handler(setOf("test-one"))))
        val (broken, set) = execution("test-one", "broken")
        val e = assertFailsWith<IllegalStateException> { read.execute(broken) }
        assertEquals("the smith's result breaks the rules: it is broken", e.message)
        assertEquals(null, set["outcome"])
    }

    @Test
    fun `a mode no handler registered has no result rules`() {
        val read = ReadResultDelegate(listOf(Handler(setOf("test-one"))))
        val e = assertFailsWith<IllegalStateException> { read.execute(execution("test-unknown", "a").first) }
        assertEquals("no result rules for mode 'test-unknown'", e.message)
    }

    @Test
    fun `two handlers claiming one mode, or a smith-owned one, are refused`() {
        val twice = assertFailsWith<IllegalArgumentException> { ReadResultDelegate(listOf(Handler(setOf("test-one")), Handler(setOf("test-one", "test-two")))) }
        assertTrue("test-one" in twice.message!!, twice.message)
        val owned = assertFailsWith<IllegalArgumentException> { ReadResultDelegate(listOf(Handler(setOf("work")))) }
        assertTrue("work" in owned.message!!, owned.message)
    }
}
