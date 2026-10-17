import SwiftUI
import FirstMateKit

/// The HITLP server this phone is a surface of (ios/README.md, "Needs"): its URL and the surface credential, saved to
/// the Keychain only after `GET /tasks` on that server answers. Forget drops them.
struct HitlpSettingsView: View {
    @Environment(AppModel.self) private var model
    @Environment(\.dismiss) private var dismiss
    @State private var url = ""
    @State private var credential = ""
    @State private var busy = false
    @State private var error: String?

    var body: some View {
        NavigationStack {
            Form {
                Section {
                    TextField("https://hitlp.example.com", text: $url)
                        .keyboardType(.URL).textInputAutocapitalization(.never).autocorrectionDisabled()
                    SecureField("Surface credential", text: $credential)
                } header: { Text("HITLP server") } footer: {
                    Text("Tasks and decisions go straight to this server; the engine is not on the path.")
                }
                if let error { Text(error).foregroundStyle(.red) }
                Section {
                    Button("Verify & save") { save() }.disabled(busy || URL(string: url)?.scheme == nil || credential.isEmpty)
                    if model.hitlp != nil {
                        Button("Forget", role: .destructive) { Keychain.clearHitlp(); model.hitlp = nil; dismiss() }
                    }
                }
            }
            .disabled(busy)
            .navigationTitle("HITLP server").navigationBarTitleDisplayMode(.inline)
            .toolbar { Button("Cancel") { dismiss() } }
        }
        .onAppear {
            url = model.hitlp?.url.absoluteString ?? ""
            credential = model.hitlp?.credential ?? ""
        }
    }

    func save() {
        guard let u = URL(string: url.trimmingCharacters(in: .whitespaces)) else { return }
        let config = HitlpConfig(url: u, credential: credential.trimmingCharacters(in: .whitespacesAndNewlines))
        busy = true; error = nil
        Task {
            defer { busy = false }
            do {
                try await HitlpClient(config: config).verify()
                try Keychain.saveHitlp(config)
                model.hitlp = config
                dismiss()
            } catch { self.error = error.localizedDescription }
        }
    }
}
