package com.codefrak.engine.workitems

import com.codefrak.engine.TestTokens.asLogin
import com.codefrak.engine.chore.ScriptedSandbox
import com.codefrak.engine.github.IssueSnapshot
import com.codefrak.engine.repos.Access
import com.codefrak.engine.repos.TargetRepos
import com.codefrak.engine.smith.SmithCaller
import com.codefrak.engine.trigger.IssueApprovals
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.http.HttpStatus
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.web.server.ResponseStatusException
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** `GET /work-items?repo=` and `GET /work-items/{id}`: a target repo's items, one item with entries; no default repo. */
@SpringBootTest
class WorkItemControllerTest(@Autowired val controller: WorkItemController, @Autowired val store: WorkItemStore,
                             @Autowired val repos: TargetRepos, @Autowired val jdbc: JdbcClient,
                             @Autowired val access: Access, @Autowired val github: GitHubWorkItemSource) {
    @BeforeEach
    fun seed() { jdbc.sql("DELETE FROM engine.work_item").update(); repos.add("acme/workitems") }

    @AfterEach
    fun drop() { repos.remove("acme/workitems") }

    @Test
    fun `lists a target repo's items and reads one with its entries`() {
        val a = store.create(NewWorkItem("acme/workitems", "a", "bug", WorkItemSourceRef(WorkItemSourceRef.ASK)))
        store.create(NewWorkItem("acme/other", "b", "bug", WorkItemSourceRef(WorkItemSourceRef.ASK)))
        assertEquals(listOf(a.id), controller.list("ACME/workitems").map { it.id })
        val read = controller.read(a.id)
        assertEquals("a", read.description)
        assertEquals(1, read.entries.size)
    }

    @Test
    fun `a missing repo, a repo that is not a target and an unknown item are refused`() {
        val missing = assertThrows<ResponseStatusException> { controller.list(null) }
        assertEquals(HttpStatus.BAD_REQUEST, missing.statusCode)
        assertTrue("acme/workitems" in missing.reason.orEmpty(), missing.reason)
        assertEquals(HttpStatus.BAD_REQUEST, assertThrows<ResponseStatusException> { controller.list("acme/nowhere") }.statusCode)
        assertEquals(HttpStatus.NOT_FOUND, assertThrows<ResponseStatusException> { controller.read("nope") }.statusCode)
    }

    @Test
    fun `a trusted login's reply is a trusted conversation entry, and a Smith, an untrusted login, no login and an unknown item are refused`() {
        val sandbox = ScriptedSandbox()
        val replies = WorkItemController(store, repos, access, sandbox, github)
        access.add("trusted-one", "test"); access.remove("stranger", "test")
        val a = store.create(NewWorkItem("acme/workitems", "a", "bug", WorkItemSourceRef(WorkItemSourceRef.ASK)))
        val before = store.entries(a.id).size
        val e = replies.reply(a.id, WorkItemController.ReplyRequest("go ahead"), asLogin("trusted-one"))
        assertEquals(WorkItemEntry.CONVERSATION, e.kind); assertEquals("trusted-one", e.author); assertTrue(e.trusted)
        assertTrue(store.entries(a.id).any { it.id == e.id && it.body == "go ahead" && it.trusted })
        sandbox.callers["10.9.0.8"] = SmithCaller("run-1", 7, "acme/workitems")
        assertEquals(HttpStatus.FORBIDDEN, assertThrows<ResponseStatusException> {
            replies.reply(a.id, WorkItemController.ReplyRequest("x"), asLogin("trusted-one", from = "10.9.0.8")) }.statusCode)
        assertEquals(HttpStatus.FORBIDDEN, assertThrows<ResponseStatusException> {
            replies.reply(a.id, WorkItemController.ReplyRequest("x"), asLogin("stranger")) }.statusCode)
        assertEquals(HttpStatus.UNAUTHORIZED, assertThrows<ResponseStatusException> {
            replies.reply(a.id, WorkItemController.ReplyRequest("x"), MockHttpServletRequest()) }.statusCode)
        assertEquals(HttpStatus.NOT_FOUND, assertThrows<ResponseStatusException> {
            replies.reply("nope", WorkItemController.ReplyRequest("x"), asLogin("trusted-one")) }.statusCode)
        assertEquals(HttpStatus.BAD_REQUEST, assertThrows<ResponseStatusException> {
            replies.reply(a.id, WorkItemController.ReplyRequest(" "), asLogin("trusted-one")) }.statusCode)
        assertEquals(before + 1, store.entries(a.id).size)
    }

    @Test
    fun `starting is 403 for a non-trusted login and 404 for an unknown item`() {
        access.add("trusted-one", "test"); access.remove("stranger", "test")
        val starting = WorkItemController(store, repos, access, ScriptedSandbox(), github)
        val a = store.create(NewWorkItem("acme/workitems", "a", "bug", WorkItemSourceRef(WorkItemSourceRef.ASK)))
        assertEquals(HttpStatus.FORBIDDEN, assertThrows<ResponseStatusException> {
            starting.start(a.id, asLogin("stranger")) }.statusCode)
        assertEquals(HttpStatus.NOT_FOUND, assertThrows<ResponseStatusException> {
            starting.start("nope", asLogin("trusted-one")) }.statusCode)
    }

    @Test
    fun `starting is 409 when the item was already started, and 200 with the outcome when it starts`() {
        access.add("trusted-one", "test")
        val reader = RecordingGitHubIssueApi(mutableMapOf(9L to IssueSnapshot(9, "Build it", "body", "outsider")))
        val importer = WorkItemController(store, repos, access, ScriptedSandbox(),
            GitHubWorkItemSource(reader))
        val item = importer.import(ImportWorkItem("acme/workitems", 9), asLogin("trusted-one"))
        val approvals = mock(IssueApprovals::class.java)
        val started = WorkItemController(store, repos, access, ScriptedSandbox(), github, approvals)

        `when`(approvals.approveTicket("acme/workitems", item.ticket!!, "trusted-one"))
            .thenReturn(IssueApprovals.TicketApproved.Refused("work item ${item.id} has already been started"))
        val conflict = assertThrows<ResponseStatusException> { started.start(item.id, asLogin("trusted-one")) }
        assertEquals(HttpStatus.CONFLICT, conflict.statusCode)
        assertTrue("already" in conflict.reason.orEmpty(), conflict.reason)

        `when`(approvals.approveTicket("acme/workitems", item.ticket!!, "trusted-one"))
            .thenReturn(IssueApprovals.TicketApproved.Done("feature", "started", "started by @trusted-one", 1L, emptyList()))
        val result = started.start(item.id, asLogin("trusted-one"))
        assertEquals("started", result["outcome"])
        assertEquals(item.ticket, result["ticket"])
    }
}
