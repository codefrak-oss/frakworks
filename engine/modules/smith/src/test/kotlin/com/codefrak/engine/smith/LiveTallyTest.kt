package com.codefrak.engine.smith

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** The timeline's live turns and tokens, from a run's stream-json lines as they arrive. */
class LiveTallyTest {
    private fun assistant(id: String, input: Int, output: Int, read: Int, write: Int, parent: String? = null, block: String = "text") =
        """{"type":"assistant","message":{"id":"$id","model":"claude-opus-5-5","content":[{"type":"$block"}],""" +
            """"usage":{"input_tokens":$input,"output_tokens":$output,"cache_read_input_tokens":$read,"cache_creation_input_tokens":$write}},""" +
            """"parent_tool_use_id":${parent?.let { "\"$it\"" } ?: "null"},"session_id":"s"}"""

    @Test
    fun `turns and tokens after each event, a message split over blocks counted once, a subagent's tokens but not its turn, the result's num_turns last`() {
        val tally = LiveTally()
        val seen = mutableListOf<LiveFigures>()
        val lines = listOf(
            """{"type":"system","subtype":"init","model":"claude-opus-5-5"}""",
            assistant("msg_1", 10, 100, 1000, 500),
            assistant("msg_2", 3, 40, 1500, 200, block = "text"),
            assistant("msg_2", 3, 40, 1500, 200, block = "tool_use"),
            """{"type":"user","message":{"content":[{"type":"tool_result","content":"ok"}]}}""",
            assistant("msg_sub", 7, 70, 700, 70, parent = "toolu_1"),
            assistant("msg_3", 1, 5, 2000, 0),
            """{"type":"result","subtype":"success","total_cost_usd":0.42,"num_turns":4,"duration_ms":1000}""",
        )
        for (line in lines) { tally.feed(line); seen += tally.figures() }

        assertEquals(listOf(null, 1L, 2L, 2L, 2L, 2L, 3L, 4L), seen.map { it.turns }, "one per distinct main-thread message, then num_turns")
        assertNull(seen[0].tokens, "no tokens before the first assistant event")
        assertEquals(LiveTokens(10, 100, 1000, 500), seen[1].tokens)
        assertEquals(LiveTokens(13, 140, 2500, 700), seen[2].tokens)
        assertEquals(seen[2].tokens, seen[3].tokens, "the second block of msg_2 repeats its usage: not counted twice")
        assertEquals(LiveTokens(20, 210, 3200, 770), seen[5].tokens, "a subagent's message adds tokens")
        assertEquals(2L, seen[5].turns, "but not a turn")
        assertEquals(LiveTokens(21, 215, 5200, 770), seen[6].tokens)
        assertEquals(LiveFigures(4, LiveTokens(21, 215, 5200, 770), 3), seen[7], "the result overrides the turns; the tally's own count stays readable")
    }

    @Test
    fun `lines that are not Claude events, or are garbled or cut, are ignored`() {
        val tally = LiveTally.of(listOf(
            "smith: run=r mode=work",
            "",
            """{"type":"assistant","message":{"id":"msg_x","usage":{"input_tokens":""",
            """{"type":"assistant" not json""",
            """{"type":"assistant","message":{"usage":{"input_tokens":5}}}""",
            """{"type":"item.completed","item":{"type":"agent_message","text":"assistant result"}}""",
            """["assistant"]""",
        ))
        assertEquals(LiveFigures(null, null, null), tally.figures())
    }

    @Test
    fun `feeding the same lines again changes nothing, so a rebuilt tally may overlap the live one`() {
        val lines = listOf(assistant("msg_1", 1, 2, 3, 4), assistant("msg_2", 5, 6, 7, 8))
        val tally = LiveTally.of(lines + lines)
        assertEquals(LiveFigures(2, LiveTokens(6, 8, 10, 12), 2), tally.figures())
    }
}
