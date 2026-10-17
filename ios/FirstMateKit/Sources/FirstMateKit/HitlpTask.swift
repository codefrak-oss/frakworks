import Foundation

/// A HITLP task view as the user's HITLP server answers it on `GET /tasks`, kept verbatim. `attested` (what the server vouches for)
/// and `asserted` (what the requester claims) are kept apart and never merged.
public struct SurfaceTask: Decodable, Equatable, Sendable {
    /// The whole view.
    public let raw: JSONValue
    public let attested: JSONValue?
    public let asserted: JSONValue?

    public init(from decoder: Decoder) throws {
        raw = try JSONValue(from: decoder)
        attested = raw["attested"]
        asserted = raw["asserted"]
    }

    public var id: String? { raw["id"]?.string }
    public var question: String? { raw["question"]?.string }
    public var action: String? { raw["action"]?.string }
    public var payload: JSONValue? { raw["payload"] }
    public var payloadDigest: String? { raw["payloadDigest"]?.string }
    /// `ask` or `approve`: the spec's `primitive` (HITLP spec/schemas/surface/task.schema.json).
    public var kind: String { raw["primitive"]?.string ?? "ask" }
    /// Waiting on a person: `status` `working` or `input_required` (spec 7.3: an Ask, and an Approve with no decision
    /// page, stay `working` until decided). `GET /tasks` also lists decided tasks.
    public var isOpen: Bool { ["working", "input_required"].contains(raw["status"]?.string ?? "") }
    public var title: String? { raw["title"]?.string }
    /// When it was created (`createdAt`, ISO-8601); nil when the server gave no time.
    public var createdAt: Date? { (raw["createdAt"]?.string).flatMap(NeedRow.date) }
    /// The ticket in `context.data.repo` (`owner/name`) and `context.data.ticket`, or nil when the context names none.
    /// The spec closes `context` to summary, links and data, so the engine files them under `data`.
    public var ticket: TicketLink? {
        let data = raw["context"]?["data"]
        let number: Int? = data?["ticket"]?.int ?? data?["ticket"]?.string.flatMap { Int($0) }
        return TicketLink(repo: data?["repo"]?.string, number: number)
    }

    /// An ask's options, `{value, label}` (a bare string is both); the `responseSchema`'s `enum` when there are none;
    /// empty for a free answer, which the card takes in a text field.
    public var options: [AskOption] {
        if let list = raw["options"]?.array, !list.isEmpty {
            return list.compactMap { o in
                if let s = o.string { return AskOption(value: s, label: s) }
                guard let v = o["value"]?.string else { return nil }
                return AskOption(value: v, label: o["label"]?.string ?? v)
            }
        }
        let schema = raw["responseSchema"] ?? raw["schema"]
        return (schema?["enum"]?.array ?? []).compactMap { $0.string.map { AskOption(value: $0, label: $0) } }
    }
}

public struct AskOption: Equatable, Hashable, Sendable {
    public let value: String
    public let label: String
}

/// A decision on a HITLP surface task: `approve`, `reject` or `answered`.
public enum DecisionOutcome: String, Sendable {
    case approve, reject, answered
}

