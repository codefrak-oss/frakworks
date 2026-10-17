import SwiftUI
import UIKit
import UserNotifications
import FirstMateKit

@main
struct FirstMateApp: App {
    @UIApplicationDelegateAdaptor(PushDelegate.self) private var push

    var body: some Scene {
        WindowGroup {
            RootView()
                .environment(push.model)
                .onOpenURL { push.model.open($0) }
        }
    }
}

/// APNs for the app (ios/README.md, "Push"): the device token goes to the engine, and a tapped notification opens its
/// chat, ticket or Needs task. It owns the model, so the system's callbacks reach the same one the views see.
final class PushDelegate: NSObject, UIApplicationDelegate, UNUserNotificationCenterDelegate {
    let model = AppModel()

    func application(_ application: UIApplication, didFinishLaunchingWithOptions launchOptions: [UIApplication.LaunchOptionsKey: Any]? = nil) -> Bool {
        UNUserNotificationCenter.current().delegate = self
        if model.pairing != nil { model.askForPush() }
        return true
    }

    func application(_ application: UIApplication, didRegisterForRemoteNotificationsWithDeviceToken deviceToken: Data) {
        model.registered(PushPayload.hex(deviceToken))
    }

    func application(_ application: UIApplication, didFailToRegisterForRemoteNotificationsWithError error: Error) {
        model.pushError = error.localizedDescription
    }

    /// In the foreground, still show it (a chat in another session answered while this one is open).
    func userNotificationCenter(_ center: UNUserNotificationCenter, willPresent notification: UNNotification) async -> UNNotificationPresentationOptions {
        [.banner, .sound]
    }

    func userNotificationCenter(_ center: UNUserNotificationCenter, didReceive response: UNNotificationResponse) async {
        let content = response.notification.request.content
        guard let target = PushPayload.target(content.userInfo) else { return }
        await MainActor.run { model.open(target, body: content.body) }
    }
}

/// The paired engine, the chosen repo and the ticket sheet, shared by every view.
@Observable
final class AppModel {
    var pairing: Pairing? = Keychain.load()
    /// The user's HITLP server (URL and surface credential), or nil until set in its settings sheet.
    var hitlp: HitlpConfig? = Keychain.loadHitlp()
    var repo: String? = UserDefaults.standard.string(forKey: "firstMate.repo") {
        didSet { UserDefaults.standard.set(repo, forKey: "firstMate.repo") }
    }
    var ticket: TicketLink?
    /// A chat a tapped notification opens; `TranscriptListView` takes it.
    var chat: String?
    /// The Needs sheet is open; [needsFocus] the task it opens on (a tapped push of a task with no ticket), or nil.
    var needsOpen = false
    var needsFocus: String?
    var pushError: String?
    let sessions = SessionStore()

    /// The device token last posted to the engine, so sign-out can unregister it.
    private var deviceToken: String? = UserDefaults.standard.string(forKey: "firstMate.deviceToken") {
        didSet { UserDefaults.standard.set(deviceToken, forKey: "firstMate.deviceToken") }
    }

    var client: EngineClient? { pairing.map { EngineClient(pairing: $0) } }
    var hitlpClient: HitlpClient? { hitlp.map { HitlpClient(config: $0) } }

    func pair(_ p: Pairing) throws { try Keychain.save(p); pairing = p; askForPush() }

    func signOut() {
        if let client, let token = deviceToken { Task { try? await client.unregisterPush(deviceToken: token) } }
        deviceToken = nil
        Keychain.clear(); sessions.clear(); pairing = nil; repo = nil
    }

    /// Asks for permission to notify (once; later calls answer at once) and, when given, registers with APNs.
    func askForPush() {
        Task { @MainActor in
            let granted = (try? await UNUserNotificationCenter.current().requestAuthorization(options: [.alert, .sound, .badge])) ?? false
            if granted { UIApplication.shared.registerForRemoteNotifications() }
        }
    }

    /// APNs gave a device token (on every launch, and a new one when it changes): post it to the engine.
    func registered(_ token: String) {
        guard let client else { return }
        #if DEBUG
        let environment = PushEnvironment.sandbox
        #else
        let environment = PushEnvironment.production
        #endif
        let previous = deviceToken
        Task { @MainActor in
            do {
                try await client.registerPush(deviceToken: token, environment: environment)
                if let previous, previous != token { try? await client.unregisterPush(deviceToken: previous) }
                deviceToken = token
            } catch { pushError = error.localizedDescription }
        }
    }

    /// A `frakworks://ticket/...` link opens the read-only ticket sheet; anything else goes to the system.
    func open(_ url: URL) -> Bool {
        guard let link = TicketLink(url: url) else { return false }
        ticket = link
        return true
    }

    /// A tapped notification: a chat opens in its repo's list, a ticket's Needs row its sheet, a task the Needs sheet on it.
    func open(_ target: PushTarget, body: String) {
        guard pairing != nil else { return }
        switch target {
        case let .chat(session, chatRepo):
            if let chatRepo { repo = chatRepo }
            chat = session
        case let .ticket(link):
            ticket = link
        case let .task(_, actionId, _):
            needsFocus = actionId
            needsOpen = true
        }
    }
}

struct RootView: View {
    @Environment(AppModel.self) private var model

    var body: some View {
        @Bindable var model = model
        Group {
            if model.pairing == nil { SignInView() }
            else if model.repo == nil { NavigationStack { RepoPickerView() } }
            else { NavigationStack { TranscriptListView() } }
        }
        .environment(\.openURL, OpenURLAction { model.open($0) ? .handled : .systemAction })
        .sheet(item: $model.ticket) { TicketSheet(link: $0) }
        .sheet(isPresented: $model.needsOpen, onDismiss: { model.needsFocus = nil }) { NeedsView(focus: model.needsFocus) }
    }
}
