import UIKit
import UniformTypeIdentifiers

// "Share → TubeHaus": a YouTube link (a video, a playlist, a channel) from the YouTube app, Safari
// or a note opens in TubeHaus, through its own URL scheme (tubehaus://open?url=…). No screen of its own.
final class ShareViewController: UIViewController {
    override func viewDidAppear(_ animated: Bool) {
        super.viewDidAppear(animated)
        Task { await share() }
    }

    private func share() async {
        let items = extensionContext?.inputItems.compactMap { $0 as? NSExtensionItem } ?? []
        var text = items.compactMap { $0.attributedContentText?.string }
        for provider in items.flatMap({ $0.attachments ?? [] }) {
            if provider.hasItemConformingToTypeIdentifier(UTType.url.identifier),
               let url = try? await provider.loadItem(forTypeIdentifier: UTType.url.identifier) as? URL {
                text.insert(url.absoluteString, at: 0)
            } else if provider.hasItemConformingToTypeIdentifier(UTType.plainText.identifier),
                      let s = try? await provider.loadItem(forTypeIdentifier: UTType.plainText.identifier) as? String {
                text.append(s)
            }
        }
        if let link = text.lazy.compactMap(youTubeLink).first,
           var open = URLComponents(string: "tubehaus://open") {
            open.queryItems = [URLQueryItem(name: "url", value: link)]
            if let url = open.url { openApp(url) }
        }
        extensionContext?.completeRequest(returningItems: nil)
    }

    /** the first YouTube link in [text]; other links stay out of the app */
    private func youTubeLink(_ text: String) -> String? {
        let pattern = #"https?://([\w-]+\.)?(youtube\.com|youtu\.be)/\S*"#
        guard let r = text.range(of: pattern, options: [.regularExpression, .caseInsensitive]) else { return nil }
        return String(text[r])
    }

    /**
     * Apple has no way for a share extension to open its app (only Today widgets and iMessage
     * extensions may). The application object up the responder chain still opens it; the older
     * `openURL:` selector stopped working in iOS 18.
     */
    private func openApp(_ url: URL) {
        var responder: UIResponder? = self
        while let r = responder {
            if let app = r as? UIApplication {
                app.open(url, options: [:], completionHandler: nil)
                return
            }
            responder = r.next
        }
    }
}
