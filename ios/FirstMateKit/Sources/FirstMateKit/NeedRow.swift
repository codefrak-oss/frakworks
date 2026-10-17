import Foundation

/// One row of `GET /needs` (engine/README.md, "What needs you"): the engine's own kinds (`incident`, `authorize`, ...),
/// shown read-only. HITLP tasks come from the HITLP server itself (`HitlpClient`), not from this list.
public struct NeedRow: Decodable, Identifiable, Equatable, Sendable {
    public let repo: String?
    public let number: Int?
    public let title: String
    public let kind: String
    public let wanted: String
    /// ISO-8601, as the engine writes it; nil for a row with no time.
    public let since: String?
    /// The incident or issue URL.
    public let actionId: String
    public let question: String?
    /// Whom the row is for; nil: everyone's.
    public let forLogin: String?

    public var id: String { "\(kind):\(actionId)" }
    public var sinceDate: Date? { since.flatMap(NeedRow.date) }

    enum CodingKeys: String, CodingKey {
        case repo, number, title, kind, wanted, since, actionId, question
        case forLogin = "for"
    }

    static func date(_ s: String) -> Date? {
        let f = ISO8601DateFormatter()
        f.formatOptions = [.withInternetDateTime, .withFractionalSeconds]
        if let d = f.date(from: s) { return d }
        f.formatOptions = [.withInternetDateTime]
        return f.date(from: s)
    }

    /// Oldest-waiting first; a row with no time last (the engine's own order).
    public static func oldestFirst(_ rows: [NeedRow]) -> [NeedRow] { NeedsMerge.oldestFirst(rows, by: \.sinceDate) }

    /// The ticket the row is on, or nil.
    public var ticket: TicketLink? { TicketLink(repo: repo, number: number) }
}

extension EngineClient {
    /// `GET /whoami`: the login the pairing's token belongs to.
    public func whoami() async throws -> String {
        let v = try JSONDecoder().decode(JSONValue.self, from: await send(request("/whoami")))
        guard let login = v["login"]?.string else { throw EngineError(status: 0, message: "whoami named no login") }
        return login
    }

    public func needsRequest(for login: String) -> URLRequest {
        request("/needs", query: [URLQueryItem(name: "for", value: login)])
    }

    /// `GET /needs?for=<login>`: the rows waiting on that person or on everyone.
    public func needs(for login: String) async throws -> [NeedRow] {
        try JSONDecoder().decode([NeedRow].self, from: await send(needsRequest(for: login)))
    }
}
