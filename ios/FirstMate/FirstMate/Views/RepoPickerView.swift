import SwiftUI
import FirstMateKit

/// The engine's target repos (`GET /repos`); the first mate works the one picked.
struct RepoPickerView: View {
    @Environment(AppModel.self) private var model
    @State private var repos: [TargetRepo] = []
    @State private var error: String?

    var body: some View {
        List {
            if let error { Text(error).foregroundStyle(.red) }
            ForEach(repos) { r in
                Button(r.fullName) { model.repo = r.fullName }
                    .foregroundStyle(r.fullName == model.repo ? Color.accentColor : .primary)
            }
        }
        .navigationTitle("Repo")
        .toolbar { Button("Sign out", role: .destructive) { model.signOut() } }
        .task { await load() }
        .refreshable { await load() }
    }

    private func load() async {
        guard let client = model.client else { return }
        do { repos = try await client.repos(); error = nil } catch { self.error = error.localizedDescription }
    }
}
