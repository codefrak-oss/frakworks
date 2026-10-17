package com.codefrak.engine.mcp

import com.codefrak.engine.smith.SandboxStatus
import com.codefrak.engine.smith.SmithCaller
import com.codefrak.engine.smith.SmithSandbox
import com.codefrak.engine.smith.SmithSpec
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.ResultActionsDsl
import org.springframework.test.web.servlet.post
import org.springframework.test.web.servlet.request.RequestPostProcessor
import org.springframework.test.web.servlet.setup.MockMvcBuilders

/** A sandbox that knows only which address is which run. */
private class AddressSandbox(val runs: Map<String, SmithCaller> = emptyMap()) : SmithSandbox {
    override fun start(spec: SmithSpec): String = error("unused")
    override fun status(handle: String): SandboxStatus = error("unused")
    override fun logs(handle: String, maxLines: Int): String = ""
    override fun output(handle: String, path: String): ByteArray? = null
    override fun remove(handle: String) {}
    override fun runAt(address: String): SmithCaller? = runs[address]
}

/** A plain server: `tools/call` answers a result, the task methods are not its. */
private class PlainServer : EngineMcpServer {
    override val name = "plain"; override val version = "1"
    override fun tools() = listOf(McpTool("echo", "echo", mapOf("type" to "object")))
    override fun call(tool: String, args: Map<String, Any?>, caller: McpCaller) = McpToolResult.of(mapOf("echo" to args))
}

/** A task server that records what reached it and answers canned handles. */
private class FakeTaskServer : TaskServer {
    override val name = "tasky"; override val version = "1"
    val calls = mutableListOf<String>()
    override fun tools() = listOf(McpTool("human.ask", "ask", mapOf("type" to "object")))
    override fun callTask(tool: String, args: Map<String, Any?>, caller: McpCaller): TaskCall {
        calls += "call $tool ${caller::class.simpleName}"
        return if (tool == "human.do") TaskCall.Refused.invalid("reserved: $tool") else TaskCall.Ok(mapOf("taskId" to "t-1", "status" to "working", "ttl" to 1000, "pollInterval" to 5000))
    }
    override fun getTask(taskId: String, caller: McpCaller): TaskCall { calls += "get $taskId"; return TaskCall.Ok(mapOf("taskId" to taskId, "status" to "working")) }
    override fun listTasks(caller: McpCaller, cursor: String?): TaskCall { calls += "list ${caller::class.simpleName} $cursor"; return TaskCall.Ok(mapOf("tasks" to emptyList<Any>())) }
    override fun cancelTask(taskId: String, caller: McpCaller): TaskCall { calls += "cancel $taskId"; return if (taskId == "gone") TaskCall.Refused.caller("not yours") else TaskCall.Ok(mapOf("taskId" to taskId, "status" to "cancelled")) }
}

/**
 * The Tasks extension in [EngineMcpController]: protocol `2026-07-28`, the `tasks` capability only for a [TaskServer],
 * `tools/call` answered as a task there, and `tasks/get`, `tasks/list`, `tasks/cancel` routed to it and `-32601` elsewhere.
 */
class EngineMcpControllerTasksTest {
    private val tasky = FakeTaskServer()
    private val smithAddress = "172.18.0.9"
    private val mvc: MockMvc = MockMvcBuilders.standaloneSetup(
        EngineMcpController(listOf(PlainServer(), tasky), AddressSandbox(mapOf(smithAddress to SmithCaller("run-1", 7, "acme/widgets"))))).build()

    private fun rpc(server: String, body: String, from: String = "127.0.0.1"): ResultActionsDsl = mvc.post("/mcp/$server") {
        contentType = MediaType.APPLICATION_JSON; accept = MediaType.APPLICATION_JSON; content = body
        with(RequestPostProcessor { it.remoteAddr = from; it })
    }

    @Test
    fun `initialize echoes 2026-07-28 and the older versions, and advertises tasks only on a task server`() {
        rpc("tasky", """{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2026-07-28"}}""").andExpect {
            status { isOk() }
            jsonPath("$.result.protocolVersion") { value("2026-07-28") }
            jsonPath("$.result.capabilities.tools") { isMap() }
            jsonPath("$.result.capabilities.tasks.list") { isMap() }
            jsonPath("$.result.capabilities.tasks.cancel") { isMap() }
            jsonPath("$.result.capabilities.tasks.requests.tools.call") { isMap() }
        }
        rpc("plain", """{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-06-18"}}""").andExpect {
            jsonPath("$.result.protocolVersion") { value("2025-06-18") }
            jsonPath("$.result.capabilities.tasks") { doesNotExist() }
        }
        rpc("plain", """{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"1999-01-01"}}""").andExpect {
            jsonPath("$.result.protocolVersion") { value("2025-11-25") }
        }
        rpc("plain", """{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2026-07-28"}}""").andExpect {
            jsonPath("$.result.protocolVersion") { value("2025-11-25") }
        }
        rpc("tasky", """{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"1999-01-01"}}""").andExpect {
            jsonPath("$.result.protocolVersion") { value("2026-07-28") }
        }
    }

    @Test
    fun `initialize echoes Claude Code's 2025-11-25 on both servers, with the tasks capability only on the task server`() {
        assertEquals(listOf("2025-11-25", "2025-06-18", "2025-03-26", "2024-11-05"), EngineMcpController.PROTOCOL_VERSIONS)
        assertEquals("2026-07-28", EngineMcpController.TASK_PROTOCOL_VERSIONS.first())
        rpc("tasky", """{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-11-25"}}""").andExpect {
            jsonPath("$.result.protocolVersion") { value("2025-11-25") }
            jsonPath("$.result.capabilities.tasks.list") { isMap() }
        }
        rpc("plain", """{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-11-25"}}""").andExpect {
            jsonPath("$.result.protocolVersion") { value("2025-11-25") }
            jsonPath("$.result.capabilities.tasks") { doesNotExist() }
        }
    }

    @Test
    fun `tools-call on a task server answers a task, a refusal is a JSON-RPC error, and the caller is the address's`() {
        rpc("tasky", """{"jsonrpc":"2.0","id":2,"method":"tools/call","params":{"name":"human.ask","arguments":{"q":1},"task":{"ttl":1000}}}""", from = smithAddress).andExpect {
            jsonPath("$.result.task.taskId") { value("t-1") }
            jsonPath("$.result.task.status") { value("working") }
            jsonPath("$.result.content") { doesNotExist() }
        }
        assertEquals("call human.ask Smith", tasky.calls.last())
        // Without `task` (Claude Code has no Tasks extension) the handle is a tool result, not `{task}`.
        rpc("tasky", """{"jsonrpc":"2.0","id":21,"method":"tools/call","params":{"name":"human.ask","arguments":{"q":1}}}""").andExpect {
            jsonPath("$.result.structuredContent.taskId") { value("t-1") }
            jsonPath("$.result.content[0].type") { value("text") }
            jsonPath("$.result.task") { doesNotExist() }
        }
        rpc("tasky", """{"jsonrpc":"2.0","id":3,"method":"tools/call","params":{"name":"human.do","arguments":{}}}""").andExpect {
            jsonPath("$.error.code") { value(-32602) }
            jsonPath("$.error.message") { value("reserved: human.do") }
        }
        assertEquals("call human.do Nobody", tasky.calls.last())
        rpc("plain", """{"jsonrpc":"2.0","id":4,"method":"tools/call","params":{"name":"echo","arguments":{"a":1}}}""").andExpect {
            jsonPath("$.result.content[0].type") { value("text") }
            jsonPath("$.result.task") { doesNotExist() }
        }
    }

    @Test
    fun `tasks-get, tasks-list and tasks-cancel reach the task server, need a taskId, and are -32601 on a plain server`() {
        rpc("tasky", """{"jsonrpc":"2.0","id":5,"method":"tasks/get","params":{"taskId":"t-1"}}""").andExpect {
            jsonPath("$.result.taskId") { value("t-1") }
        }
        rpc("tasky", """{"jsonrpc":"2.0","id":6,"method":"tasks/list","params":{"cursor":"c2"}}""").andExpect {
            jsonPath("$.result.tasks") { isArray() }
        }
        rpc("tasky", """{"jsonrpc":"2.0","id":7,"method":"tasks/cancel","params":{"taskId":"t-1"}}""").andExpect {
            jsonPath("$.result.status") { value("cancelled") }
        }
        rpc("tasky", """{"jsonrpc":"2.0","id":8,"method":"tasks/cancel","params":{"taskId":"gone"}}""").andExpect {
            jsonPath("$.error.code") { value(TaskCall.Refused.REFUSED) }
        }
        rpc("tasky", """{"jsonrpc":"2.0","id":9,"method":"tasks/get","params":{}}""").andExpect {
            jsonPath("$.error.code") { value(-32602) }
        }
        assertEquals(listOf("get t-1", "list Nobody c2", "cancel t-1", "cancel gone"), tasky.calls)
        for (method in listOf("tasks/get", "tasks/list", "tasks/cancel"))
            rpc("plain", """{"jsonrpc":"2.0","id":10,"method":"$method","params":{"taskId":"t-1"}}""").andExpect {
                jsonPath("$.error.code") { value(-32601) }
            }
    }
}
