// Translates stdin into the language given as the only argument with Apple's own translation
// (macOS 26+, on the Mac, nothing is sent anywhere). The app runs it once per "Translate" click.
//
//   echo "hello" | translate-mac ko
//
// Exit codes: 0 the translation is on stdout; 2 the text is already in that language;
// 3 the language can't be told or the pair isn't supported; 4 the pair needs a download in
// System Settings; 1 anything else (message on stderr).
import Foundation
import NaturalLanguage
import Translation

func fail(_ code: Int32, _ msg: String) -> Never {
    FileHandle.standardError.write(Data((msg + "\n").utf8))
    exit(code)
}

guard CommandLine.arguments.count == 2 else { fail(1, "usage: translate-mac <target language>") }
let target = Locale.Language(identifier: CommandLine.arguments[1])
let text = String(decoding: FileHandle.standardInput.readDataToEndOfFile(), as: UTF8.self)
guard !text.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else { fail(1, "nothing to translate") }

let recognizer = NLLanguageRecognizer()
recognizer.processString(text)
guard let found = recognizer.dominantLanguage else { fail(3, "language not recognized") }
let source = Locale.Language(identifier: found.rawValue)
if source.languageCode == target.languageCode { fail(2, "already \(found.rawValue)") }

switch await LanguageAvailability().status(from: source, to: target) {
case .installed: break
// downloadable: only System Settings (or a SwiftUI window) can fetch the language
case .supported: fail(4, "\(found.rawValue) → \(CommandLine.arguments[1]) not downloaded")
default: fail(3, "\(found.rawValue) → \(CommandLine.arguments[1]) not supported")
}

do {
    let result = try await TranslationSession(installedSource: source, target: target).translate(text)
    FileHandle.standardOutput.write(Data(result.targetText.utf8))
} catch {
    fail(1, "\(error)")
}
