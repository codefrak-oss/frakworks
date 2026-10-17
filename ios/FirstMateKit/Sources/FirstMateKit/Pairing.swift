import Foundation

/// What the pairing QR on the web version's /#/pair page (and the desktop's Pair a phone) encodes: `{"url", "token"}`.
public struct Pairing: Codable, Equatable, Sendable {
    public let url: URL
    public let token: String

    public init(url: URL, token: String) { self.url = url; self.token = token }

    public enum Failure: Error, Equatable { case notJSON, missingField(String), badURL(String) }

    /// Decodes a pasted or scanned payload; the URL must be http(s) with a host, the token non-blank.
    public static func decode(_ text: String) throws -> Pairing {
        guard let data = text.trimmingCharacters(in: .whitespacesAndNewlines).data(using: .utf8),
              let object = try? JSONSerialization.jsonObject(with: data) as? [String: Any] else { throw Failure.notJSON }
        guard let raw = (object["url"] as? String)?.trimmingCharacters(in: .whitespaces), !raw.isEmpty else { throw Failure.missingField("url") }
        guard let token = (object["token"] as? String)?.trimmingCharacters(in: .whitespaces), !token.isEmpty else { throw Failure.missingField("token") }
        guard let url = URL(string: raw.hasSuffix("/") ? String(raw.dropLast()) : raw),
              let scheme = url.scheme?.lowercased(), scheme == "http" || scheme == "https", url.host != nil else { throw Failure.badURL(raw) }
        return Pairing(url: url, token: token)
    }
}
