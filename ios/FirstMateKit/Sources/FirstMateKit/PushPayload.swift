import Foundation

/// Where a tapped notification opens (engine/README.md, "Push notifications"): the payload's `fm` object.
public enum PushTarget: Equatable, Sendable {
    /// `{"type":"chat","session","repo"}`: that first-mate chat.
    case chat(session: String, repo: String?)
    /// `{"type":"needs",...}` on a ticket: its read-only sheet.
    case ticket(TicketLink)
    /// `{"type":"needs",...}` with no ticket: the row's kind and id; the Needs sheet opens on `actionId`.
    case task(kind: String, actionId: String?, repo: String?)
}

public enum PushPayload {
    /// The target of a notification's `userInfo` (what `UNNotificationContent.userInfo` holds), or nil when it has no
    /// `fm` object the app knows.
    public static func target(_ userInfo: [AnyHashable: Any]) -> PushTarget? {
        guard let fm = userInfo["fm"] as? [String: Any], let type = fm["type"] as? String else { return nil }
        let repo = (fm["repo"] as? String).flatMap { $0.isEmpty ? nil : $0 }
        switch type {
        case "chat":
            guard let session = fm["session"] as? String, !session.isEmpty else { return nil }
            return .chat(session: session, repo: repo)
        case "needs":
            let kind = fm["kind"] as? String ?? "task"
            if let repo, let n = (fm["number"] as? NSNumber)?.intValue, n > 0 {
                let parts = repo.split(separator: "/", omittingEmptySubsequences: false)
                if parts.count == 2, let link = TicketLink(string: "frakworks://ticket/\(parts[0])/\(parts[1])/\(n)") { return .ticket(link) }
            }
            return .task(kind: kind, actionId: fm["actionId"] as? String, repo: repo)
        default:
            return nil
        }
    }

    /// The APNs device token as the engine takes it: lowercase hex.
    public static func hex(_ deviceToken: Data) -> String { deviceToken.map { String(format: "%02x", $0) }.joined() }
}

/// Which APNs host a build's device tokens belong to: a Debug build is signed for the sandbox, any other for production.
public enum PushEnvironment: String, Sendable {
    case sandbox, production
}

extension EngineClient {
    /// `PUT /first-mate/push/device {deviceToken, platform, environment}`: register (or re-register) this phone.
    public func pushRegistrationRequest(deviceToken: String, environment: PushEnvironment) -> URLRequest {
        request("/first-mate/push/device", method: "PUT", body: ["deviceToken": deviceToken, "platform": "ios", "environment": environment.rawValue])
    }

    /// `DELETE /first-mate/push/device {deviceToken}`: stop pushing to this phone.
    public func pushUnregistrationRequest(deviceToken: String) -> URLRequest {
        request("/first-mate/push/device", method: "DELETE", body: ["deviceToken": deviceToken])
    }

    public func registerPush(deviceToken: String, environment: PushEnvironment) async throws {
        try await send(pushRegistrationRequest(deviceToken: deviceToken, environment: environment))
    }

    public func unregisterPush(deviceToken: String) async throws {
        try await send(pushUnregistrationRequest(deviceToken: deviceToken))
    }
}
