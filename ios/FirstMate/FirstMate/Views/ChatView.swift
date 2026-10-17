import SwiftUI
import FirstMateKit

/// One chat: the transcript so far, then the session's `first-mate` frames from `GET /events` as they come.
struct ChatView: View {
    let sessionId: String
    @Environment(AppModel.self) private var model
    @State private var bubbles: [Bubble] = []
    @State private var draft = ""
    @State private var resuming = false
    @State private var error: String?
    /// Voice mode: off by default and per chat; off means v1 exactly, no audio session and no prompt.
    @State private var voiceOn = false
    @State private var voice: VoiceController?

    struct Bubble: Identifiable { let id = UUID(); let role: String; var text: String }

    var body: some View {
        VStack(spacing: 0) {
            ScrollViewReader { proxy in
                ScrollView {
                    LazyVStack(alignment: .leading, spacing: 8) {
                        ForEach(bubbles) { b in BubbleView(bubble: b).id(b.id) }
                        if resuming { Text("resuming…").font(.footnote).foregroundStyle(.secondary).id("resuming") }
                        if let error { Text(error).font(.footnote).foregroundStyle(.red) }
                    }.padding()
                }
                .onChange(of: bubbles.count) { if let last = bubbles.last { proxy.scrollTo(last.id, anchor: .bottom) } }
            }
            HStack {
                if voiceOn, let voice {
                    Button(voice.listening ? "Stop listening" : "Talk", systemImage: voice.listening ? "mic.fill" : "mic") {
                        voice.listening ? voice.stopListening() : voice.startListening()
                    }.labelStyle(.iconOnly).font(.title2).foregroundStyle(voice.listening ? .red : .accentColor)
                    if voice.speaking {
                        Button("Stop speaking", systemImage: "speaker.slash.fill") { voice.stopSpeaking() }
                            .labelStyle(.iconOnly).font(.title2)
                    }
                }
                TextField("Message", text: $draft, axis: .vertical).textFieldStyle(.roundedBorder).lineLimit(1...6)
                Button("Send", systemImage: "arrow.up.circle.fill") { Task { await send() } }
                    .labelStyle(.iconOnly).font(.title2).disabled(draft.trimmingCharacters(in: .whitespaces).isEmpty)
            }.padding()
            if voiceOn, let voice, voice.listening {
                Text(voice.partial.isEmpty ? "Listening…" : voice.partial).font(.footnote).foregroundStyle(.secondary)
                    .frame(maxWidth: .infinity, alignment: .leading).padding([.horizontal, .bottom])
            }
        }
        .toolbar {
            Button(voiceOn ? "Voice off" : "Voice on", systemImage: voiceOn ? "speaker.wave.2.fill" : "speaker.slash") {
                Task { await setVoice(!voiceOn) }
            }
        }
        .onDisappear { voice?.disable(); voice = nil; voiceOn = false }
        .navigationTitle("First mate").navigationBarTitleDisplayMode(.inline)
        .task { await run() }
    }

    private func run() async {
        guard let client = model.client else { return }
        do {
            let t = try await client.transcript(sessionId)
            bubbles = t.messages.compactMap(Self.bubble)
        } catch { self.error = error.localizedDescription }
        // Reconnects while the view is up: the engine's stream has no replay, the transcript is the record.
        while !Task.isCancelled {
            do {
                for try await frame in EventStream.frames(client, repo: model.repo ?? "", sessionId: sessionId) {
                    if frame.isResuming { resuming = true; continue }
                    resuming = false
                    if let text = frame.assistantText {
                        bubbles.append(Bubble(role: "assistant", text: text))
                        if voiceOn { voice?.speak(text) }
                    }
                    else if case .string(let raw) = frame.line { bubbles.append(Bubble(role: "system", text: raw)) }
                }
            } catch { if Task.isCancelled { return } }
            try? await Task.sleep(for: .seconds(2))
        }
    }

    private func send() async {
        let text = draft.trimmingCharacters(in: .whitespacesAndNewlines)
        draft = ""
        await send(text)
    }

    private func send(_ text: String) async {
        guard let client = model.client, !text.isEmpty else { return }
        if bubbles.allSatisfy({ $0.role != "user" }) { model.sessions.retitle(sessionId, String(text.prefix(60))) }
        bubbles.append(Bubble(role: "user", text: text))
        do { try await client.send(text: text, to: sessionId); error = nil } catch { self.error = error.localizedDescription }
    }

    /// Turning voice on asks for permissions and starts the audio session; turning it off stops both.
    private func setVoice(_ on: Bool) async {
        if !on { voice?.disable(); voice = nil; voiceOn = false; return }
        let v = VoiceController()
        v.onError = { error = $0 }
        v.onTranscript = { text in Task { await send(text) } }
        guard await v.enable() else { v.disable(); return }
        voice = v
        voiceOn = true
        error = nil
    }

    /// A transcript row as a bubble: the user's and system's `{text}`, the assistant's stream-json text.
    static func bubble(_ m: TranscriptMessage) -> Bubble? {
        if let text = m.body["text"]?.string { return Bubble(role: m.role, text: text) }
        let frame = FirstMateFrame(repo: "", sessionId: "", seq: m.seq, line: m.body)
        return frame.assistantText.map { Bubble(role: "assistant", text: $0) }
    }
}

private struct BubbleView: View {
    let bubble: ChatView.Bubble

    var body: some View {
        let md = (try? AttributedString(markdown: bubble.text, options: .init(interpretedSyntax: .inlineOnlyPreservingWhitespace)))
            ?? AttributedString(bubble.text)
        switch bubble.role {
        case "user":
            HStack { Spacer(minLength: 40); Text(md).padding(10).background(Color.accentColor.opacity(0.2), in: .rect(cornerRadius: 12)) }
        case "system":
            Text(md).font(.footnote).foregroundStyle(.secondary).frame(maxWidth: .infinity)
        default:
            Text(md).textSelection(.enabled).padding(10).background(.quaternary, in: .rect(cornerRadius: 12))
        }
    }
}
