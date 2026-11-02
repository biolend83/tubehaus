import AVKit
import UIKit

/// Subtitles. YouTube's HLS streams carry none, so they come from the video's own tracks (the
/// timedtext addresses in the player response) as WebVTT, and are drawn over the picture here,
/// in time with the player. The track is picked from the Subtitles menu in the player's top bar;
/// the language picked last is used again for the next video.
enum Subtitles {
    struct Cue {
        var start: Double
        var end: Double
        var text: String
    }

    /// what the menu offers: the video's tracks, then (when none is in the TV's language) a
    /// translation into it, which YouTube makes from the first track
    struct Choice: Hashable {
        var title: String
        var lang: String
        var url: String
    }

    static func choices(_ tracks: [Caption]) -> [Choice] {
        var out = tracks.map { Choice(title: $0.name.isEmpty ? $0.lang : $0.name, lang: $0.lang, url: $0.url + "&fmt=vtt") }
        let mine = String((Locale.preferredLanguages.first ?? "en").prefix(2))
        if let base = tracks.first(where: { !$0.auto }) ?? tracks.first, !tracks.contains(where: { $0.lang.hasPrefix(mine) }) {
            let name = Locale.current.localizedString(forLanguageCode: mine) ?? mine
            out.append(Choice(title: "\(name) (auto-translated)", lang: mine, url: base.url + "&fmt=vtt&tlang=\(mine)"))
        }
        return out
    }

    /// the track for language `lang`: one made by people before an automatic one
    static func pick(_ choices: [Choice], tracks: [Caption], lang: String) -> Choice? {
        guard !lang.isEmpty else { return nil }
        let same = choices.filter { $0.lang == lang || $0.lang.hasPrefix(lang + "-") }
        let human = same.first { c in tracks.contains { $0.url + "&fmt=vtt" == c.url && !$0.auto } }
        return human ?? same.first
    }

    static func load(_ url: String) async -> [Cue] {
        guard let u = URL(string: url),
              let (d, r) = try? await URLSession.shared.data(from: u),
              (r as? HTTPURLResponse)?.statusCode == 200 else { return [] }
        return parse(String(decoding: d, as: UTF8.self))
    }

    /// WebVTT → cues, made fit to show: automatic and translated tracks "roll" (each cue repeats
    /// the line before, cues overlap, 10 ms cues sit between them), so every line is shown once,
    /// cues don't overlap and the word-timing tags are gone. The same as the Android app's Vtt.kt.
    static func parse(_ src: String) -> [Cue] {
        let rows = src.replacingOccurrences(of: "\r\n", with: "\n").components(separatedBy: "\n")
        var raw: [(Double, Double, [String])] = []
        var i = 0
        while i < rows.count {
            let line = rows[i].trimmingCharacters(in: .whitespaces)
            guard line.contains("-->") else { i += 1; continue }
            let parts = line.components(separatedBy: "-->")
            guard parts.count == 2, let a = seconds(parts[0]),
                  let b = seconds(parts[1].trimmingCharacters(in: .whitespaces).components(separatedBy: " ")[0]) else { i += 1; continue }
            i += 1
            var text: [String] = []
            while i < rows.count, !rows[i].trimmingCharacters(in: .whitespaces).isEmpty {
                let t = rows[i].replacingOccurrences(of: "<[^>]*>", with: "", options: .regularExpression)
                    .replacingOccurrences(of: "&nbsp;", with: " ").replacingOccurrences(of: "&amp;", with: "&")
                    .replacingOccurrences(of: "&lt;", with: "<").replacingOccurrences(of: "&gt;", with: ">")
                    .trimmingCharacters(in: .whitespaces)
                if !t.isEmpty { text.append(t) }
                i += 1
            }
            raw.append((a, b, text))
        }
        var out: [Cue] = []
        var shown: [String] = []
        for (a, b, lines) in raw {
            // lines already on screen in the cue before are dropped, and repeats within a cue too
            var seen = Set<String>()
            let fresh = lines.filter { !shown.contains($0) && seen.insert($0).inserted }
            if !lines.isEmpty { shown = lines }
            if fresh.isEmpty || b - a < 0.05 { continue }
            if let last = out.indices.last, out[last].end > a { out[last].end = a }
            out.append(Cue(start: a, end: b, text: fresh.joined(separator: "\n")))
        }
        return out.filter { $0.end > $0.start }
    }

    /// "01:02:03.456" or "02:03.456" → seconds
    private static func seconds(_ s: String) -> Double? {
        let p = s.trimmingCharacters(in: .whitespaces).split(separator: ":")
        guard p.count >= 2, p.count <= 3 else { return nil }
        var t = 0.0
        for (n, part) in p.enumerated() {
            guard let v = Double(part) else { return nil }
            t += v * pow(60, Double(p.count - 1 - n))
        }
        return t
    }
}

/// The subtitle line on top of the video: white on a dark box, near the bottom, the size from Settings.
final class CaptionLayer: UIView {
    private let box = UIView()
    private let label = UILabel()
    private var cues: [Subtitles.Cue] = []

    /// `compact`: the smaller lines for the video playing on its page
    init(compact: Bool = false) {
        super.init(frame: .zero)
        isUserInteractionEnabled = false
        box.backgroundColor = UIColor.black.withAlphaComponent(0.72)
        box.layer.cornerRadius = 10
        box.isHidden = true
        label.numberOfLines = 0
        label.textAlignment = .center
        label.textColor = .white
        let size: CGFloat = [38, 48, 60][max(0, min(2, Settings.captionSize))] * (compact ? 0.55 : 1)
        label.font = .systemFont(ofSize: size, weight: .medium)
        box.translatesAutoresizingMaskIntoConstraints = false
        label.translatesAutoresizingMaskIntoConstraints = false
        addSubview(box)
        box.addSubview(label)
        NSLayoutConstraint.activate([
            label.leadingAnchor.constraint(equalTo: box.leadingAnchor, constant: 22),
            label.trailingAnchor.constraint(equalTo: box.trailingAnchor, constant: -22),
            label.topAnchor.constraint(equalTo: box.topAnchor, constant: 10),
            label.bottomAnchor.constraint(equalTo: box.bottomAnchor, constant: -10),
            box.centerXAnchor.constraint(equalTo: centerXAnchor),
            box.bottomAnchor.constraint(equalTo: bottomAnchor, constant: compact ? -24 : -80),
            box.widthAnchor.constraint(lessThanOrEqualTo: widthAnchor, multiplier: 0.8),
        ])
    }

    required init?(coder: NSCoder) { fatalError() }

    func set(_ cues: [Subtitles.Cue]) {
        self.cues = cues
        show("")
    }

    /// called several times a second with the player's time
    func update(_ t: Double) {
        guard !cues.isEmpty else { return }
        // the first cue that hasn't ended yet (cues are in order and don't overlap)
        var lo = 0, hi = cues.count
        while lo < hi {
            let mid = (lo + hi) / 2
            if cues[mid].end <= t { lo = mid + 1 } else { hi = mid }
        }
        show(lo < cues.count && cues[lo].start <= t ? cues[lo].text : "")
    }

    private func show(_ text: String) {
        guard label.text != text else { return }
        label.text = text
        box.isHidden = text.isEmpty
    }
}
