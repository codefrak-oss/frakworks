import Foundation

/// `frakworks://ticket/<owner>/<name>/<n>`: the link that opens a ticket (observability/desktop/README.md,
/// "Links that open a ticket in the app"). Nothing may follow the number.
public struct TicketLink: Equatable, Hashable, Sendable, Identifiable {
    public let owner: String
    public let name: String
    public let number: Int

    public var id: String { "\(owner)/\(name)/\(number)" }
    public var repo: String { "\(owner)/\(name)" }
    public var url: URL { URL(string: "frakworks://ticket/\(owner)/\(name)/\(number)")! }
    /// The engine path that reads it: `GET /ticket/<owner>/<name>/<n>`.
    public var apiPath: String { "/ticket/\(owner)/\(name)/\(number)" }

    public init(owner: String, name: String, number: Int) { self.owner = owner; self.name = name; self.number = number }

    /// From a repo `owner/name` and a number, as `GET /needs` rows and a HITLP task's `context` name them.
    public init?(repo: String?, number: Int?) {
        guard let repo, let number, number > 0 else { return nil }
        let parts = repo.split(separator: "/", omittingEmptySubsequences: false)
        guard parts.count == 2, Self.valid(parts[0]), Self.valid(parts[1]) else { return nil }
        self.init(owner: String(parts[0]), name: String(parts[1]), number: number)
    }

    public init?(url: URL) { self.init(string: url.absoluteString) }

    public init?(string: String) {
        let prefix = "frakworks://ticket/"
        guard string.lowercased().hasPrefix(prefix) else { return nil }
        let parts = string.dropFirst(prefix.count).split(separator: "/", omittingEmptySubsequences: false)
        guard parts.count == 3, Self.valid(parts[0]), Self.valid(parts[1]),
              !parts[2].isEmpty, parts[2].allSatisfy(\.isASCII), parts[2].allSatisfy(\.isNumber),
              let n = Int(parts[2]), n > 0 else { return nil }
        self.init(owner: String(parts[0]), name: String(parts[1]), number: n)
    }

    private static func valid(_ s: Substring) -> Bool {
        !s.isEmpty && s.allSatisfy { $0.isASCII && ($0.isLetter || $0.isNumber || $0 == "-" || $0 == "_" || $0 == ".") }
    }

    /// Every ticket link in [markdown], in order (markdown links and bare ones).
    public static func all(in markdown: String) -> [TicketLink] {
        guard let re = try? NSRegularExpression(pattern: #"frakworks://ticket/[A-Za-z0-9._-]+/[A-Za-z0-9._-]+/[0-9]+(?![0-9A-Za-z/])"#) else { return [] }
        let ns = markdown as NSString
        return re.matches(in: markdown, range: NSRange(location: 0, length: ns.length)).compactMap { TicketLink(string: ns.substring(with: $0.range)) }
    }
}
