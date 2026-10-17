import Foundation

public struct TargetRepo: Decodable, Identifiable, Hashable, Sendable {
    public let fullName: String
    public var id: String { fullName }
}

public struct SessionView: Decodable, Identifiable, Sendable {
    public let id: String
    public let repo: String
    public let state: String
    public let firstLine: String?
}

public struct TranscriptMessage: Decodable, Identifiable, Sendable {
    public let seq: Int
    public let role: String
    public let body: JSONValue
    public var id: Int { seq }
}

public struct Transcript: Decodable, Sendable {
    public let id: String
    public let repo: String
    public let state: String
    public let messages: [TranscriptMessage]
}

/// `GET /ticket/<o>/<n>/<num>`'s fields the read-only sheet shows.
public struct TicketDetail: Decodable, Sendable {
    public let repo: String
    public let number: Int
    public let title: String
    public let body: String
    public let state: String
    public let url: String
    public let labels: [String]
}

public struct EngineError: Error, LocalizedError, Sendable {
    public let status: Int
    public let message: String
    public var errorDescription: String? { "\(status): \(message)" }
}

/// One HTTP exchange: `URLSession.data(for:)`, or a stub in tests.
public typealias Transport = @Sendable (URLRequest) async throws -> (Data, URLResponse)

/// The engine's HTTP API with the pairing's first-mate bearer token.
public struct EngineClient: Sendable {
    public let pairing: Pairing
    let transport: Transport
    /// The session `EventStream` streams `GET /events` on.
    let session: URLSession
    /// How long `send` waits before its one retry (client ride-out of a lease handover, engine/README.md "Replicas and the lease").
    let retryDelay: Duration

    public init(pairing: Pairing, session: URLSession = .shared) {
        self.init(pairing: pairing, transport: { try await session.data(for: $0) }, session: session)
    }

    public init(pairing: Pairing, transport: @escaping Transport, retryDelay: Duration = .milliseconds(500), session: URLSession = .shared) {
        self.pairing = pairing; self.transport = transport; self.retryDelay = retryDelay; self.session = session
    }

    func request(_ path: String, method: String = "GET", body: [String: Any]? = nil, query: [URLQueryItem] = []) -> URLRequest {
        var c = URLComponents(url: pairing.url.appendingPathComponent(path), resolvingAgainstBaseURL: false)!
        if !query.isEmpty { c.queryItems = query }
        var r = URLRequest(url: c.url!)
        r.httpMethod = method
        r.setValue("Bearer \(pairing.token)", forHTTPHeaderField: "Authorization")
        if let body {
            r.setValue("application/json", forHTTPHeaderField: "Content-Type")
            r.httpBody = try? JSONSerialization.data(withJSONObject: body)
        }
        return r
    }

    /// A 502/503 or a refused connection: no ready engine endpoint for a moment (a lease handover).
    static func retryable(status: Int) -> Bool { status == 502 || status == 503 }
    static func retryable(_ error: Error) -> Bool { (error as? URLError)?.code == .cannotConnectToHost }

    /// One exchange, retried once after `retryDelay` on 502/503 or a refused connection.
    func exchange(_ r: URLRequest) async throws -> (Data, URLResponse) {
        do {
            let (data, response) = try await transport(r)
            guard Self.retryable(status: (response as? HTTPURLResponse)?.statusCode ?? 0) else { return (data, response) }
        } catch let e where Self.retryable(e) {}
        try await Task.sleep(for: retryDelay)
        return try await transport(r)
    }

    @discardableResult
    func send(_ r: URLRequest) async throws -> Data {
        let (data, response) = try await exchange(r)
        let status = (response as? HTTPURLResponse)?.statusCode ?? 0
        guard (200..<300).contains(status) else {
            // The engine's `{"error"}`, or Spring's `{"message", "error": <reason phrase>}` for a ResponseStatusException.
            let json = try? JSONDecoder().decode(JSONValue.self, from: data)
            let msg = json?["message"]?.string.flatMap { $0.isEmpty ? nil : $0 } ?? json?["error"]?.string ?? String(decoding: data, as: UTF8.self)
            throw EngineError(status: status, message: msg)
        }
        return data
    }

    public func repos() async throws -> [TargetRepo] { try JSONDecoder().decode([TargetRepo].self, from: await send(request("/repos"))) }

    public func start(repo: String) async throws -> SessionView {
        try JSONDecoder().decode(SessionView.self, from: await send(request("/first-mate/sessions", method: "POST", body: ["repo": repo])))
    }

    /// The message carries a `clientId`, made once here, so a retry the engine already stored is not stored twice.
    public func send(text: String, to id: String, clientId: String = UUID().uuidString) async throws {
        try await send(request("/first-mate/sessions/\(id)/messages", method: "POST", body: ["text": text, "clientId": clientId]))
    }

    public func transcript(_ id: String) async throws -> Transcript {
        try JSONDecoder().decode(Transcript.self, from: await send(request("/first-mate/sessions/\(id)/transcript")))
    }

    public func end(_ id: String) async throws { try await send(request("/first-mate/sessions/\(id)", method: "DELETE")) }

    public func ticket(_ link: TicketLink) async throws -> TicketDetail {
        try JSONDecoder().decode(TicketDetail.self, from: await send(request(link.apiPath)))
    }

    public func workItemsRequest(repo: String) -> URLRequest { request("/work-items", query: [URLQueryItem(name: "repo", value: repo)]) }
    public func workItemRequest(_ id: String) -> URLRequest { request("/work-items/\(id)") }
    public func replyRequest(to id: String, body: String) -> URLRequest { request("/work-items/\(id)/entries", method: "POST", body: ["body": body]) }

    /// `GET /work-items?repo=`: the repo's work items, untrusted text fenced.
    public func workItems(repo: String) async throws -> [WorkItem] {
        try JSONDecoder().decode([WorkItem].self, from: await send(workItemsRequest(repo: repo)))
    }

    public func workItem(_ id: String) async throws -> WorkItem {
        try JSONDecoder().decode(WorkItem.self, from: await send(workItemRequest(id)))
    }

    /// `POST /work-items/{id}/entries`: a conversation entry by the token's login.
    @discardableResult
    public func reply(to id: String, body: String) async throws -> WorkItemEntry {
        try JSONDecoder().decode(WorkItemEntry.self, from: await send(replyRequest(to: id, body: body)))
    }

    /// `GET /events?repo=<repo>` as an SSE request (the caller streams it with EventStream).
    public func eventsRequest(repo: String) -> URLRequest {
        var r = request("/events", query: [URLQueryItem(name: "repo", value: repo)])
        r.setValue("text/event-stream", forHTTPHeaderField: "Accept")
        r.timeoutInterval = .infinity
        return r
    }
}
