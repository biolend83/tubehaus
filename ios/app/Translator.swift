import SwiftUI
import NaturalLanguage
import Translation
import TubeHausKit

// Comment translation for the Kotlin side (ios/src/.../CommentTranslation.kt) with Apple's
// Translation framework (iOS 18+, on the device). Its sessions come from SwiftUI's
// .translationTask, which asks the person to download a language the first time it is needed.

@available(iOS 18.0, *)
final class AppleTranslator: NSObject, ObservableObject, CommentTranslator {
    static let shared = AppleTranslator()

    /// a new value (or an invalidated one) makes .translationTask hand over a session for it
    @Published var config: TranslationSession.Configuration?

    private struct Job {
        let text: String
        let source: Locale.Language
        let target: Locale.Language
        let reply: TranslationReply
    }
    /// waiting texts, main thread only; the first one is being translated
    private var jobs: [Job] = []

    func translate(text: String, target: String, reply: TranslationReply) {
        let recognizer = NLLanguageRecognizer()
        recognizer.processString(text)
        guard let found = recognizer.dominantLanguage else {
            reply.failed(reason: "language not recognized")
            return
        }
        let source = Locale.Language(identifier: found.rawValue)
        let to = Locale.Language(identifier: target)
        if source.languageCode == to.languageCode {
            reply.sameLanguage()
            return
        }
        DispatchQueue.main.async {
            self.jobs.append(Job(text: text, source: source, target: to, reply: reply))
            if self.jobs.count == 1 { self.start() }
        }
    }

    private func start() {
        guard let job = jobs.first else { return }
        if config?.source == job.source && config?.target == job.target {
            config?.invalidate()
        } else {
            config = TranslationSession.Configuration(source: job.source, target: job.target)
        }
    }

    /// .translationTask's work: the first waiting text, then the next one gets its own session
    @MainActor
    func run(_ session: TranslationSession) async {
        guard let job = jobs.first else { return }
        do {
            job.reply.text(translated: try await session.translate(job.text).targetText)
        } catch {
            // also when the person turns down the language download
            job.reply.failed(reason: "\(error)")
        }
        jobs.removeFirst()
        start()
    }
}

@available(iOS 18.0, *)
private struct TranslationHost: ViewModifier {
    @ObservedObject var translator = AppleTranslator.shared

    func body(content: Content) -> some View {
        content.translationTask(translator.config) { session in
            await translator.run(session)
        }
    }
}

extension View {
    /// where the translation sessions (and the download question) come from; nothing before iOS 18
    @ViewBuilder
    func commentTranslation() -> some View {
        if #available(iOS 18.0, *) {
            modifier(TranslationHost())
        } else {
            self
        }
    }
}

/// gives Kotlin the translator; before iOS 18 there is none and the links stay hidden
func setUpCommentTranslation() {
    if #available(iOS 18.0, *) {
        CommentTranslation.shared.translator = AppleTranslator.shared
    }
}
