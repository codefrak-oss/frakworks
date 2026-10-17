import XCTest
@testable import FirstMateKit

/// `GET /needs` rows (engine kinds only) decode, sort oldest-waiting first, and merge with the HITLP server's tasks.
final class NeedRowTests: XCTestCase {
    func rows() throws -> [NeedRow] {
        try JSONDecoder().decode([NeedRow].self, from: Data(#"""
    [ {"repo":"acme/app","number":12,"title":"Fix","kind":"incident","wanted":"retry","since":"2026-10-12T11:00:00.5Z","actionId":"inc-1","question":null,"for":null},
      {"repo":null,"number":null,"title":"Authorize","kind":"authorize","wanted":"label it","since":null,"actionId":"auth-1","question":null,"for":"lundmatt1"} ]
    """#.utf8))
    }

    func tasks() throws -> [SurfaceTask] {
        try JSONDecoder().decode([SurfaceTask].self, from: Data(#"""
    [ {"id":"task-7","primitive":"approve","status":"input_required","title":"Ship?","action":"deploy","payload":{"a":"x","b":2},"payloadDigest":"sha256:ab",
       "attested":{"requester":"engine"},"asserted":{"why":"Ship?"},"createdAt":"2026-10-12T09:00:00Z"},
      {"id":"task-8","primitive":"ask","status":"input_required","question":"Which?","options":[{"value":"a","label":"Option A"},"b"],"createdAt":"2026-10-12T10:30:00+02:00",
       "context":{"data":{"repo":"acme/app","ticket":3}}} ]
    """#.utf8))
    }

    func testEngineRow() throws {
        let r = try rows()[0]
        XCTAssertEqual(r.kind, "incident"); XCTAssertEqual(r.number, 12)
        XCTAssertEqual(r.ticket, TicketLink(owner: "acme", name: "app", number: 12))
        XCTAssertEqual(try rows()[1].forLogin, "lundmatt1"); XCTAssertNil(try rows()[1].ticket)
    }

    /// The spec's `primitive` names the kind; `working` and `input_required` wait on a person (spec examples/surface/task-list.json lists working and completed ones too).
    func testPrimitiveAndOpenStatus() throws {
        let list = try JSONDecoder().decode([SurfaceTask].self, from: Data(#"""
    [ {"id":"task-7f3a","primitive":"approve","status":"working","action":"deploy release 4821 to production"},
      {"id":"task-81b0","primitive":"ask","status":"completed","question":"Which currency?"},
      {"id":"open","primitive":"approve","status":"input_required","action":"x"} ]
    """#.utf8))
        XCTAssertEqual(list.map(\.kind), ["approve", "ask", "approve"])
        XCTAssertEqual(list.filter(\.isOpen).map(\.id), ["task-7f3a", "open"])
    }

    func testTaskKeepsAttestedAndAssertedApart() throws {
        let t = try tasks()[0]
        XCTAssertEqual(t.kind, "approve")
        XCTAssertEqual(t.attested, .object(["requester": .string("engine")]))
        XCTAssertEqual(t.asserted, .object(["why": .string("Ship?")]))
        XCTAssertEqual(t.action, "deploy"); XCTAssertEqual(t.payloadDigest, "sha256:ab")
        XCTAssertEqual(t.payload?.pretty, "{\n  \"a\" : \"x\",\n  \"b\" : 2\n}")
        XCTAssertNil(t.ticket)
    }

    func testAskOptionsAndTicket() throws {
        let t = try tasks()[1]
        XCTAssertEqual(t.options, [AskOption(value: "a", label: "Option A"), AskOption(value: "b", label: "b")])
        XCTAssertNil(t.asserted)
        XCTAssertEqual(t.ticket, TicketLink(owner: "acme", name: "app", number: 3))
    }

    func testTicketFromStringNumberOrBadRepo() throws {
        let s = try JSONDecoder().decode(SurfaceTask.self, from: Data(#"{"context":{"data":{"repo":"acme/app","ticket":"9"}}}"#.utf8))
        XCTAssertEqual(s.ticket?.number, 9)
        let bad = try JSONDecoder().decode(SurfaceTask.self, from: Data(#"{"context":{"data":{"repo":"app","ticket":9}}}"#.utf8))
        XCTAssertNil(bad.ticket)
    }

    func testAskOptionsFromSchemaEnumOrNone() throws {
        let enumTask = try JSONDecoder().decode(SurfaceTask.self, from: Data(#"{"responseSchema":{"type":"string","enum":["yes","no"]}}"#.utf8))
        XCTAssertEqual(enumTask.options.map(\.value), ["yes", "no"])
        let free = try JSONDecoder().decode(SurfaceTask.self, from: Data(#"{"responseSchema":{"type":"string"}}"#.utf8))
        XCTAssertTrue(free.options.isEmpty)
    }

    func testMergeOldestFirstWithNoTimeLast() throws {
        XCTAssertEqual(NeedsMerge.merge(try rows(), try tasks()).map(\.actionId), ["task-8", "task-7", "inc-1", "auth-1"])
    }

    func testNeedsFrame() {
        let e = SSEEvent(event: "needs", data: #"{"repo":"*","kind":"incident","state":"removed","actionId":"inc-1"}"#)
        XCTAssertEqual(NeedsFrame.decode(e), NeedsFrame(kind: "incident", state: "removed", actionId: "inc-1"))
        XCTAssertNil(NeedsFrame.decode(SSEEvent(event: "first-mate", data: "{}")))
    }
}
