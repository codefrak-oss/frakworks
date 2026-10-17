import XCTest
@testable import FirstMateKit

/// The HITLP server, spoken directly: `GET /tasks`, a decision with channel `iphone`, `GET /events`, and refusals.
final class HitlpClientTests: XCTestCase {
    let client = HitlpClient(config: HitlpConfig(url: URL(string: "https://hitlp.test/api")!, credential: "c"))

    func body(_ r: URLRequest) throws -> [String: Any] {
        try XCTUnwrap(JSONSerialization.jsonObject(with: r.httpBody ?? Data()) as? [String: Any])
    }

    func stub(_ status: Int, _ json: String) -> HitlpClient {
        HitlpClient(config: client.config, transport: { r in
            (Data(json.utf8), HTTPURLResponse(url: r.url!, statusCode: status, httpVersion: nil, headerFields: nil)!)
        })
    }

    func testTasksRequest() {
        let r = client.tasksRequest
        XCTAssertEqual(r.httpMethod, "GET")
        XCTAssertEqual(r.url?.absoluteString, "https://hitlp.test/api/tasks")
        XCTAssertEqual(r.value(forHTTPHeaderField: "Authorization"), "Bearer c")
    }

    func testApproveBody() throws {
        let r = client.decisionRequest(taskId: "task-7", outcome: .approve, reason: "fine", payloadDigest: "sha256:ab")
        XCTAssertEqual(r.httpMethod, "POST")
        XCTAssertEqual(r.url?.absoluteString, "https://hitlp.test/api/tasks/task-7/decision")
        XCTAssertEqual(r.value(forHTTPHeaderField: "Authorization"), "Bearer c")
        XCTAssertEqual(try body(r) as NSDictionary, ["outcome": "approve", "reason": "fine", "payloadDigest": "sha256:ab"] as NSDictionary)
    }

    func testRejectWithNoReasonLeavesItOut() throws {
        let b = try body(client.decisionRequest(taskId: "task-7", outcome: .reject, reason: ""))
        XCTAssertEqual(b as NSDictionary, ["outcome": "reject"] as NSDictionary)
    }

    /// An ask's decision is the spec's `{answer, reason?}`: no outcome, no channel.
    func testAnsweredCarriesTheAnswerAsJSON() throws {
        let b = try body(client.decisionRequest(taskId: "task-8", outcome: .answered, answer: .string("a")))
        XCTAssertEqual(b as NSDictionary, ["answer": "a"] as NSDictionary)
    }

    func testEventsRequest() {
        XCTAssertEqual(client.eventsRequest.url?.absoluteString, "https://hitlp.test/api/events")
        XCTAssertEqual(client.eventsRequest.value(forHTTPHeaderField: "Accept"), "text/event-stream")
    }

    func testTasksBareOrWrapped() async throws {
        let bare = try await stub(200, #"[{"id":"a"}]"#).tasks().map(\.id)
        let wrapped = try await stub(200, #"{"tasks":[{"id":"b"}]}"#).tasks().map(\.id)
        XCTAssertEqual(bare, ["a"])
        XCTAssertEqual(wrapped, ["b"])
    }

    func testVerifyAndRefusalShowTheServerMessage() async {
        do { try await stub(401, #"{"error":"bad credential"}"#).verify(); XCTFail("no error") }
        catch { XCTAssertEqual((error as? HitlpError)?.message, "bad credential") }
        do { try await stub(409, #"{"message":"already decided"}"#).decide(taskId: "t", outcome: .approve); XCTFail("no error") }
        catch { XCTAssertEqual((error as? HitlpError)?.status, 409); XCTAssertEqual((error as? HitlpError)?.message, "already decided") }
    }

    func testConfigRoundTrips() throws {
        let data = try JSONEncoder().encode(client.config)
        XCTAssertEqual(try JSONDecoder().decode(HitlpConfig.self, from: data), client.config)
    }
}
