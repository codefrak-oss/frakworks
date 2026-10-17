import Foundation

/// A `first-mate` frame on `GET /events` (engine EngineEvent.FirstMate): one stream-json line a session's pod wrote.
public struct FirstMateFrame: Equatable, Sendable {
    public let repo: String
    public let sessionId: String
    public let seq: Int
    /// The line: parsed JSON (a dictionary for stream-json) or raw text.
    public let line: JSONValue

    public init(repo: String, sessionId: String, seq: Int, line: JSONValue) {
        self.repo = repo
        self.sessionId = sessionId
        self.seq = seq
        self.line = line
    }

    /// Seq 0 `{"type":"resuming"}`: the engine is starting a frozen session's pod again.
    public var isResuming: Bool { seq == 0 && line["type"]?.string == "resuming" }

    /// The assistant's text in this line (`{"type":"assistant","message":{"content":[{"type":"text","text":…}]}}`), or nil.
    public var assistantText: String? {
        guard line["type"]?.string == "assistant", case .array(let parts)? = line["message"]?["content"] else { return nil }
        let texts = parts.compactMap { $0["type"]?.string == "text" ? $0["text"]?.string : nil }
        return texts.isEmpty ? nil : texts.joined()
    }

    /// The final `{"type":"result"}` line of a turn.
    public var isResult: Bool { line["type"]?.string == "result" }
}

public enum FrameDecoder {
    /// The frame in [event] when it is a `first-mate` event for [sessionId] (any session when nil); otherwise nil.
    public static func decode(_ event: SSEEvent, sessionId: String? = nil) -> FirstMateFrame? {
        guard event.event == "first-mate", let data = event.data.data(using: .utf8),
              let value = try? JSONDecoder().decode(JSONValue.self, from: data),
              let sid = value["sessionId"]?.string, let repo = value["repo"]?.string,
              let seq = value["seq"]?.int else { return nil }
        if let sessionId, sessionId != sid { return nil }
        return FirstMateFrame(repo: repo, sessionId: sid, seq: seq, line: value["line"] ?? .null)
    }
}

/// A `needs` frame on `GET /events` (engine EngineEvent.Needs): a row of `GET /needs` appeared or went.
public struct NeedsFrame: Equatable, Sendable {
    public let kind: String
    /// `open`, `removed`, `fired`, ...
    public let state: String
    public let actionId: String?

    /// The frame in [event] when it is a `needs` event; otherwise nil.
    public static func decode(_ event: SSEEvent) -> NeedsFrame? {
        guard event.event == "needs", let data = event.data.data(using: .utf8),
              let value = try? JSONDecoder().decode(JSONValue.self, from: data),
              let kind = value["kind"]?.string, let state = value["state"]?.string else { return nil }
        return NeedsFrame(kind: kind, state: state, actionId: value["actionId"]?.string)
    }
}

/// Any JSON value, for the stream-json lines whose shape the app only partly reads.
public enum JSONValue: Codable, Equatable, Sendable {
    case null, bool(Bool), number(Double), string(String), array([JSONValue]), object([String: JSONValue])

    public init(from decoder: Decoder) throws {
        let c = try decoder.singleValueContainer()
        if c.decodeNil() { self = .null }
        else if let b = try? c.decode(Bool.self) { self = .bool(b) }
        else if let n = try? c.decode(Double.self) { self = .number(n) }
        else if let s = try? c.decode(String.self) { self = .string(s) }
        else if let a = try? c.decode([JSONValue].self) { self = .array(a) }
        else { self = .object(try c.decode([String: JSONValue].self)) }
    }

    public func encode(to encoder: Encoder) throws {
        var c = encoder.singleValueContainer()
        switch self {
        case .null: try c.encodeNil()
        case .bool(let b): try c.encode(b)
        case .number(let n): try c.encode(n)
        case .string(let s): try c.encode(s)
        case .array(let a): try c.encode(a)
        case .object(let o): try c.encode(o)
        }
    }

    public subscript(key: String) -> JSONValue? { if case .object(let o) = self { return o[key] }; return nil }
    public var string: String? { if case .string(let s) = self { return s }; return nil }
    public var int: Int? { if case .number(let n) = self { return Int(exactly: n) }; return nil }
    public var array: [JSONValue]? { if case .array(let a) = self { return a }; return nil }

    /// As `JSONSerialization` takes it, for a request body.
    public var any: Any {
        switch self {
        case .null: return NSNull()
        case .bool(let b): return b
        case .number(let n): return n
        case .string(let s): return s
        case .array(let a): return a.map(\.any)
        case .object(let o): return o.mapValues(\.any)
        }
    }

    /// Indented JSON with sorted keys, as a card shows a payload.
    public var pretty: String {
        let enc = JSONEncoder(); enc.outputFormatting = [.prettyPrinted, .sortedKeys, .withoutEscapingSlashes]
        return (try? enc.encode(self)).map { String(decoding: $0, as: UTF8.self) } ?? ""
    }
}
