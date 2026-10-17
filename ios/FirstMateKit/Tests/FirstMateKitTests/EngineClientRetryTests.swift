import XCTest
@testable import FirstMateKit

/// EngineClient rides out a moment with no ready engine endpoint: one retry on 502/503 or a refused connection.
final class EngineClientRetryTests: XCTestCase {
    /// Answers in order (a status code, or an error to throw) and records every request.
    final class Stub: @unchecked Sendable {
        var answers: [Result<Int, URLError>]
        var requests: [URLRequest] = []
        init(_ answers: [Result<Int, URLError>]) { self.answers = answers }

        func client() -> EngineClient {
            EngineClient(pairing: Pairing(url: URL(string: "https://engine.test")!, token: "t"), transport: { r in
                self.requests.append(r)
                let status = try self.answers.removeFirst().get()
                return (Data("{}".utf8), HTTPURLResponse(url: r.url!, statusCode: status, httpVersion: nil, headerFields: nil)!)
            }, retryDelay: .zero)
        }

        func body(_ i: Int) -> [String: String] {
            (try? JSONSerialization.jsonObject(with: requests[i].httpBody ?? Data())) as? [String: String] ?? [:]
        }
    }

    func test503ThenOkRetriesOnceWithTheSameClientId() async throws {
        let stub = Stub([.success(503), .success(200)])
        try await stub.client().send(text: "hi", to: "s1")
        XCTAssertEqual(stub.requests.count, 2)
        let first = stub.body(0)["clientId"]
        XCTAssertNotNil(first)
        XCTAssertEqual(first, stub.body(1)["clientId"])
        XCTAssertEqual(stub.body(0)["text"], "hi")
    }

    func testRefusedThenOkRetries() async throws {
        let stub = Stub([.failure(URLError(.cannotConnectToHost)), .success(200)])
        _ = try await stub.client().send(stub.client().request("/repos"))
        XCTAssertEqual(stub.requests.count, 2)
    }

    func testRetriesOnlyOnce() async {
        let stub = Stub([.success(502), .success(502), .success(200)])
        do { _ = try await stub.client().send(stub.client().request("/repos")); XCTFail("expected an error") }
        catch { XCTAssertEqual((error as? EngineError)?.status, 502) }
        XCTAssertEqual(stub.requests.count, 2)
    }

    func test500IsNotRetried() async {
        let stub = Stub([.success(500), .success(200)])
        do { _ = try await stub.client().send(stub.client().request("/repos")); XCTFail("expected an error") }
        catch { XCTAssertEqual((error as? EngineError)?.status, 500) }
        XCTAssertEqual(stub.requests.count, 1)
    }
}
