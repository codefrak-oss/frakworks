import XCTest
@testable import FirstMateKit

/// A notification's `fm` payload decodes to where a tap opens, and the device registration is the engine's request.
final class PushPayloadTests: XCTestCase {
    func testChatOpensTheSession() {
        let info: [AnyHashable: Any] = ["aps": ["alert": ["body": "x"]], "fm": ["type": "chat", "session": "s-1", "repo": "acme/app"]]
        XCTAssertEqual(PushPayload.target(info), .chat(session: "s-1", repo: "acme/app"))
        XCTAssertNil(PushPayload.target(["fm": ["type": "chat"]]))
    }

    func testNeedsOnATicketOpensItsSheet() {
        let info: [AnyHashable: Any] = ["fm": ["type": "needs", "kind": "question", "repo": "acme/app", "number": NSNumber(value: 12), "actionId": "a-1"]]
        XCTAssertEqual(PushPayload.target(info), .ticket(TicketLink(owner: "acme", name: "app", number: 12)))
    }

    func testNeedsWithNoTicketIsATask() {
        let info: [AnyHashable: Any] = ["fm": ["type": "needs", "kind": "approve", "repo": NSNull(), "number": NSNull(), "actionId": "task-7"]]
        XCTAssertEqual(PushPayload.target(info), .task(kind: "approve", actionId: "task-7", repo: nil))
    }

    func testNeedsWithNoTicketNamesTheTaskTheSheetOpensOn() {
        let info: [AnyHashable: Any] = ["fm": ["type": "needs", "kind": "ask", "repo": "acme/app", "actionId": "task-8"]]
        guard case let .task(kind, actionId, _)? = PushPayload.target(info) else { return XCTFail("not a task") }
        XCTAssertEqual(kind, "ask"); XCTAssertEqual(actionId, "task-8")
    }

    func testUnknownPayloadsAreNil() {
        XCTAssertNil(PushPayload.target([:]))
        XCTAssertNil(PushPayload.target(["fm": ["type": "merged"]]))
    }

    func testDeviceTokenIsLowercaseHex() {
        XCTAssertEqual(PushPayload.hex(Data([0x00, 0xAB, 0x7f])), "00ab7f")
    }

    func testRegistrationRequests() throws {
        let client = EngineClient(pairing: Pairing(url: URL(string: "https://engine.test")!, token: "t"))
        let put = client.pushRegistrationRequest(deviceToken: "00ab", environment: .sandbox)
        XCTAssertEqual(put.httpMethod, "PUT")
        XCTAssertEqual(put.url?.path, "/first-mate/push/device")
        XCTAssertEqual(put.value(forHTTPHeaderField: "Authorization"), "Bearer t")
        let body = try XCTUnwrap(JSONSerialization.jsonObject(with: put.httpBody ?? Data()) as? [String: String])
        XCTAssertEqual(body, ["deviceToken": "00ab", "platform": "ios", "environment": "sandbox"])

        let delete = client.pushUnregistrationRequest(deviceToken: "00ab")
        XCTAssertEqual(delete.httpMethod, "DELETE")
        XCTAssertEqual(delete.url?.path, "/first-mate/push/device")
        XCTAssertEqual(try XCTUnwrap(JSONSerialization.jsonObject(with: delete.httpBody ?? Data()) as? [String: String]), ["deviceToken": "00ab"])
    }
}
