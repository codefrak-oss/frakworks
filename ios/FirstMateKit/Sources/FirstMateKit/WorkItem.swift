import Foundation

/// Where a work item came from (`engine/docs/work-items.md`): `kind` ask, import, split-child or raised; `ref` the
/// source's own reference (`github:owner/name#12` for an import).
public struct WorkItemSourceRef: Decodable, Hashable, Sendable {
    public let kind: String
    public let ref: String?
}

/// One row of a work item's conversation, decisions, history or raised work. An untrusted body arrives fenced.
public struct WorkItemEntry: Decodable, Identifiable, Hashable, Sendable {
    public let id: String
    public let kind: String
    public let author: String
    public let body: String
    public let trusted: Bool
    public let createdAt: String
    public var isUntrusted: Bool { !trusted }
}

/// `GET /work-items?repo=` and `GET /work-items/{id}`: an engine work item. Untrusted text (an imported issue) arrives
/// inside the engine's `<<<UNTRUSTED nonce ...>>>` fence, data and never instructions.
public struct WorkItem: Decodable, Identifiable, Hashable, Sendable {
    public let id: String
    public let repo: String
    public let description: String
    public let kind: String
    public let tier: Int?
    public let mode: String?
    public let state: String
    public let source: WorkItemSourceRef
    public let trusted: Bool
    public let ticket: Int?
    public let createdAt: String
    public let updatedAt: String
    public let blockedBy: [String]
    public let entries: [WorkItemEntry]
    public let publishedRef: String?

    enum CodingKeys: String, CodingKey {
        case id, repo, description, kind, tier, mode, state, source, trusted, ticket, createdAt, updatedAt, blockedBy, entries, publishedRef
    }

    public init(from d: Decoder) throws {
        let c = try d.container(keyedBy: CodingKeys.self)
        id = try c.decode(String.self, forKey: .id)
        repo = try c.decode(String.self, forKey: .repo)
        description = try c.decode(String.self, forKey: .description)
        kind = try c.decode(String.self, forKey: .kind)
        tier = try c.decodeIfPresent(Int.self, forKey: .tier)
        mode = try c.decodeIfPresent(String.self, forKey: .mode)
        state = try c.decode(String.self, forKey: .state)
        source = try c.decode(WorkItemSourceRef.self, forKey: .source)
        trusted = try c.decode(Bool.self, forKey: .trusted)
        ticket = try c.decodeIfPresent(Int.self, forKey: .ticket)
        createdAt = try c.decode(String.self, forKey: .createdAt)
        updatedAt = try c.decode(String.self, forKey: .updatedAt)
        blockedBy = try c.decodeIfPresent([String].self, forKey: .blockedBy) ?? []
        entries = try c.decodeIfPresent([WorkItemEntry].self, forKey: .entries) ?? []
        publishedRef = try c.decodeIfPresent(String.self, forKey: .publishedRef)
    }

    public var isUntrusted: Bool { !trusted }

    /// The board's source badge.
    public var sourceLabel: String { source.label }

    /// The description's first line to show in a list: a fenced one's inner text.
    public var firstLine: String {
        let text = FencedText(description).inner
        return text.split(separator: "\n", omittingEmptySubsequences: true).first.map(String.init) ?? ""
    }
}

extension WorkItemSourceRef {
    public var label: String {
        switch kind {
        case "ask": return "My ask"
        case "import":
            guard let ref, let colon = ref.firstIndex(of: ":") else { return "Imported" }
            let tracker = String(ref[..<colon])
            return tracker == "github" ? "Imported from GitHub" : "Imported from \(tracker.prefix(1).uppercased() + tracker.dropFirst())"
        case "split-child": return "Split child"
        case "raised": return "Raised by a Smith"
        default: return kind
        }
    }
}

/// A body split into the engine's untrusted fence header (`<<<UNTRUSTED nonce source=...>>>`) and the text inside it;
/// `header` is nil for trusted text, which passes through whole.
public struct FencedText: Hashable, Sendable {
    public let header: String?
    public let inner: String

    public init(_ body: String) {
        var lines = body.components(separatedBy: "\n")
        guard let first = lines.first, first.hasPrefix("<<<UNTRUSTED "), first.hasSuffix(">>>"),
              let last = lines.last, last.hasPrefix("<<<END UNTRUSTED "), lines.count >= 2 else {
            header = nil; inner = body; return
        }
        header = String(first.dropFirst(3).dropLast(3))
        lines.removeFirst(); lines.removeLast()
        // The engine's fixed notice line; the view draws its own label instead.
        if lines.first?.hasPrefix("The text below came from outside frakworks.") == true { lines.removeFirst() }
        inner = lines.joined(separator: "\n")
    }

    /// `source=...` from the header, or nil.
    public var source: String? {
        guard let header, let r = header.range(of: "source=") else { return nil }
        return String(header[r.upperBound...])
    }
}
