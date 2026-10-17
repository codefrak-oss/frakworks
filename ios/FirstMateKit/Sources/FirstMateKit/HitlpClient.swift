import Foundation

/// The user's own HITLP server, as this phone's surface: its URL and the surface credential, kept in the Keychain
/// (account `hitlp`) beside the pairing.
public struct HitlpConfig: Codable, Equatable, Sendable {
    public let url: URL
    public let credential: String
    public init(url: URL, credential: String) { self.url = url; self.credential = credential }
}

/// A refusal of the HITLP server: its status and its message.
public struct HitlpError: Error, LocalizedError, Sendable {
    public let status: Int
    public let message: String
    public var errorDescription: String? { "HITLP server \(status): \(message)" }
}

/// The HITLP server's surface API, spoken directly with `Authorization: Bearer <credential>`; the engine is not on
/// this path.
public struct HitlpClient: Sendable {
    public let config: HitlpConfig
    let transport: Transport
    let session: URLSession

    public init(config: HitlpConfig, session: URLSession = .shared) {
        self.init(config: config, transport: { try await session.data(for: $0) }, session: session)
    }

    public init(config: HitlpConfig, transport: @escaping Transport, session: URLSession = .shared) {
        self.config = config; self.transport = transport; self.session = session
    }

    func request(_ path: String, method: String = "GET", body: [String: Any]? = nil) -> URLRequest {
        var r = URLRequest(url: config.url.appendingPathComponent(path))
        r.httpMethod = method
        r.setValue("Bearer \(config.credential)", forHTTPHeaderField: "Authorization")
        if let body {
            r.setValue("application/json", forHTTPHeaderField: "Content-Type")
            r.httpBody = try? JSONSerialization.data(withJSONObject: body)
        }
        return r
    }

    @discardableResult
    func send(_ r: URLRequest) async throws -> Data {
        let (data, response) = try await transport(r)
        let status = (response as? HTTPURLResponse)?.statusCode ?? 0
        guard (200..<300).contains(status) else {
            let json = try? JSONDecoder().decode(JSONValue.self, from: data)
            let msg = json?["message"]?.string.flatMap { $0.isEmpty ? nil : $0 } ?? json?["error"]?.string ?? String(decoding: data, as: UTF8.self)
            throw HitlpError(status: status, message: msg)
        }
        return data
    }

    public var tasksRequest: URLRequest { request("/tasks") }

    /// `GET /tasks`: the open tasks for this surface. Takes a bare array or `{"tasks": [...]}`.
    public func tasks() async throws -> [SurfaceTask] {
        let data = try await send(tasksRequest)
        if let list = try? JSONDecoder().decode([SurfaceTask].self, from: data) { return list }
        struct Wrapped: Decodable { let tasks: [SurfaceTask] }
        return try JSONDecoder().decode(Wrapped.self, from: data).tasks
    }

    /// The URL and credential work: `GET /tasks` answers 2xx. Settings save only after this passes.
    public func verify() async throws { try await send(tasksRequest) }

    /// `POST /tasks/{id}/decision`, the spec's body (HITLP spec/schemas/surface/decide.request.schema.json) and nothing
    /// else: `{answer, reason?}` for an ask, `{outcome: approve | reject, reason?, payloadDigest?}` for an approve. The
    /// server records the channel from the surface's kind.
    public func decisionRequest(taskId: String, outcome: DecisionOutcome, answer: JSONValue? = nil, reason: String? = nil,
                                payloadDigest: String? = nil) -> URLRequest {
        var body: [String: Any] = [:]
        if outcome == .answered {
            body["answer"] = (answer ?? .null).any
        } else {
            body["outcome"] = outcome.rawValue
            if let payloadDigest { body["payloadDigest"] = payloadDigest }
        }
        if let reason, !reason.isEmpty { body["reason"] = reason }
        return request("/tasks/\(taskId)/decision", method: "POST", body: body)
    }

    /// Decides a task on the server; its refusal (400/404/409, a bad credential) comes back as a `HitlpError`.
    public func decide(taskId: String, outcome: DecisionOutcome, answer: JSONValue? = nil, reason: String? = nil,
                       payloadDigest: String? = nil) async throws {
        try await send(decisionRequest(taskId: taskId, outcome: outcome, answer: answer, reason: reason, payloadDigest: payloadDigest))
    }

    public var eventsRequest: URLRequest {
        var r = request("/events")
        r.setValue("text/event-stream", forHTTPHeaderField: "Accept")
        return r
    }

    /// `GET /events`: every event the server sends, each a cue to reload the tasks.
    public func events() -> AsyncThrowingStream<SSEEvent, Error> {
        EventStream.stream(session, eventsRequest) { $0 }
    }
}
