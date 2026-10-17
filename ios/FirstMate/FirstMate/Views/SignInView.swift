import SwiftUI
import FirstMateKit

/// Pair with the engine: paste the QR's text, or scan the QR from the web version's Pair a phone page.
struct SignInView: View {
    @Environment(AppModel.self) private var model
    @State private var text = ""
    @State private var scanning = false
    @State private var error: String?

    var body: some View {
        NavigationStack {
            Form {
                Section {
                    Button("Scan pairing QR", systemImage: "qrcode.viewfinder") { scanning = true }
                } footer: { Text("Open Pair a phone in the engine's web version (/web/#/pair) and scan its QR.") }
                Section("Or paste it") {
                    TextField(#"{"url": …, "token": …}"#, text: $text, axis: .vertical)
                        .textInputAutocapitalization(.never).autocorrectionDisabled().font(.footnote.monospaced())
                    Button("Pair") { pair(text) }.disabled(text.isEmpty)
                }
                if let error { Section { Text(error).foregroundStyle(.red) } }
            }
            .navigationTitle("First mate")
            .sheet(isPresented: $scanning) {
                QRScannerView { code in scanning = false; pair(code) }.ignoresSafeArea()
            }
        }
    }

    private func pair(_ payload: String) {
        do { try model.pair(Pairing.decode(payload)); error = nil }
        catch { self.error = "Not a pairing code: \(error)" }
    }
}
