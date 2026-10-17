import AVFoundation
import Speech
import FirstMateKit

/// Voice mode: tap-to-talk recognised on the phone with `SFSpeechRecognizer`, and replies spoken with the system
/// `AVSpeechSynthesizer`. Nothing here touches the audio session or asks a permission until voice mode is turned on.
@Observable @MainActor
final class VoiceController: NSObject {
    private(set) var listening = false
    private(set) var speaking = false
    /// What has been heard so far in this utterance.
    private(set) var partial = ""

    /// A failure, for the chat's error line.
    var onError: (String) -> Void = { _ in }
    /// The final transcript of an utterance, sent as a message.
    var onTranscript: (String) -> Void = { _ in }

    private let recognizer = SFSpeechRecognizer()
    private let synthesizer = AVSpeechSynthesizer()
    private let audio = AVAudioEngine()
    private var request: SFSpeechAudioBufferRecognitionRequest?
    private var task: SFSpeechRecognitionTask?
    private var silence: Task<Void, Never>?
    private var sessionActive = false

    override init() {
        super.init()
        synthesizer.delegate = self
    }

    /// Voice mode turned on: ask for speech and microphone permission (iOS prompts only the first time).
    func enable() async -> Bool {
        let speech = await withCheckedContinuation { c in SFSpeechRecognizer.requestAuthorization { c.resume(returning: $0) } }
        guard speech == .authorized else { onError("Speech recognition is not allowed: turn it on in Settings > First mate."); return false }
        guard await AVAudioApplication.requestRecordPermission() else {
            onError("The microphone is not allowed: turn it on in Settings > First mate."); return false
        }
        guard let recognizer, recognizer.isAvailable else { onError("Speech recognition is unavailable right now."); return false }
        return activateSession()
    }

    /// Voice mode turned off: stop everything and give the audio session back.
    func disable() {
        stopListening(send: false)
        stopSpeaking()
        if sessionActive { try? AVAudioSession.sharedInstance().setActive(false, options: .notifyOthersOnDeactivation) }
        sessionActive = false
    }

    private func activateSession() -> Bool {
        do {
            let session = AVAudioSession.sharedInstance()
            try session.setCategory(.playAndRecord, mode: .spokenAudio, options: [.defaultToSpeaker, .allowBluetooth])
            try session.setActive(true)
            sessionActive = true
            return true
        } catch { onError("Audio session: \(error.localizedDescription)"); return false }
    }

    // MARK: listening

    func startListening() {
        guard !listening else { return }
        stopSpeaking()
        guard let recognizer, recognizer.isAvailable else { onError("Speech recognition is unavailable right now."); return }
        guard sessionActive || activateSession() else { return }
        let request = SFSpeechAudioBufferRecognitionRequest()
        request.shouldReportPartialResults = true
        if recognizer.supportsOnDeviceRecognition { request.requiresOnDeviceRecognition = true }
        let input = audio.inputNode
        input.removeTap(onBus: 0)
        input.installTap(onBus: 0, bufferSize: 1024, format: input.outputFormat(forBus: 0)) { @Sendable buffer, _ in request.append(buffer) }
        audio.prepare()
        do { try audio.start() } catch {
            input.removeTap(onBus: 0)
            onError("Microphone: \(error.localizedDescription)"); return
        }
        self.request = request
        partial = ""
        listening = true
        task = recognizer.recognitionTask(with: request) { @Sendable [weak self] result, error in
            let text = result?.bestTranscription.formattedString
            let isFinal = result?.isFinal ?? false
            Task { @MainActor in self?.recognized(text, final: isFinal, error: error) }
        }
        armSilenceTimer()
    }

    /// Tap again: end the utterance and send what was heard.
    func stopListening(send: Bool = true) {
        guard listening else { return }
        listening = false
        silence?.cancel()
        audio.stop()
        audio.inputNode.removeTap(onBus: 0)
        request?.endAudio()
        task?.cancel()
        request = nil
        task = nil
        let text = partial.trimmingCharacters(in: .whitespacesAndNewlines)
        partial = ""
        if send, !text.isEmpty { onTranscript(text) }
    }

    private func recognized(_ text: String?, final: Bool, error: Error?) {
        guard listening else { return }
        if let text { partial = text; armSilenceTimer() }
        if final { stopListening(); return }
        if let error {
            // Recognition ends with an error when nothing was heard; say so only when there is nothing to send.
            if partial.isEmpty { onError("Speech recognition: \(error.localizedDescription)") }
            stopListening()
        }
    }

    /// Two seconds without new words ends the utterance.
    private func armSilenceTimer() {
        silence?.cancel()
        silence = Task { [weak self] in
            try? await Task.sleep(for: .seconds(2))
            guard !Task.isCancelled, let self, self.listening, !self.partial.isEmpty else { return }
            self.stopListening()
        }
    }

    // MARK: speaking

    /// Queue a reply's text: utterances on one synthesiser play in the order they are queued.
    func speak(_ markdown: String) {
        guard sessionActive else { return }
        for chunk in SpeechText.prepare(markdown) {
            let u = AVSpeechUtterance(string: chunk)
            u.voice = AVSpeechSynthesisVoice(language: AVSpeechSynthesisVoice.currentLanguageCode())
            synthesizer.speak(u)
            speaking = true
        }
    }

    func stopSpeaking() {
        synthesizer.stopSpeaking(at: .immediate)
        speaking = false
    }
}

extension VoiceController: AVSpeechSynthesizerDelegate {
    nonisolated func speechSynthesizer(_ synthesizer: AVSpeechSynthesizer, didFinish utterance: AVSpeechUtterance) {
        Task { @MainActor in self.speaking = synthesizer.isSpeaking }
    }

    nonisolated func speechSynthesizer(_ synthesizer: AVSpeechSynthesizer, didCancel utterance: AVSpeechUtterance) {
        Task { @MainActor in self.speaking = synthesizer.isSpeaking }
    }
}
