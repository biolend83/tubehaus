import Foundation

/// "This video at a glance" (the web app's glance.js, the apps' Glance.kt): from the captions, on the
/// TV (nothing is sent anywhere), the words the video keeps coming back to and the moments that say
/// the most. Plain counting, no AI model.
enum Glance {
    struct Moment: Identifiable, Hashable {
        var start: Double
        var text: String
        var id: Double { start }
    }

    struct Result: Equatable {
        var keywords: [String]
        var moments: [Moment]
    }

    private static let stop: Set<String> = Set(("the and for you that this with are was but not have has had they them his her she him its it's our your from what when where which who how all any can could would should will just like so um uh yeah okay ok really very about there here then than into out get got going gonna know think one two also some more now well right because been being were did does doing don't i'm we're you're that's let's there's music applause laughter "
        + "그리고 그래서 그런데 하지만 그냥 진짜 정말 이제 이거 저거 그거 여기 거기 저기 우리 저는 제가 나는 내가 너무 많이 약간 조금 그런 이런 저런 어떤 있는 없는 있어요 있습니다 합니다 했어요 하는 해서 하고 에서 으로 그게 이게 뭐가 같은 같아요 됩니다 되는 그럼 근데 아니 네네 음악 박수")
        .split(separator: " ").map(String.init))
    private static let particles = ["에서", "으로", "에게", "까지", "부터", "처럼", "은", "는", "이", "가", "을", "를", "에", "의", "로", "도", "와", "과", "만"]

    private static func isHangul(_ c: Character) -> Bool {
        guard let u = c.unicodeScalars.first?.value else { return false }
        return u >= 0xAC00 && u <= 0xD7A3
    }

    static func words(_ text: String) -> [String] {
        var out: [String] = []
        let parts = text.lowercased().split { !($0.isLetter || $0.isNumber || $0 == "'") }
        for p in parts {
            var w = String(p).trimmingCharacters(in: CharacterSet(charactersIn: "'"))
            if w.count < 2 || w.allSatisfy(\.isNumber) { continue }
            if let f = w.first, isHangul(f), w.count >= 3,
               let x = particles.first(where: { w.hasSuffix($0) && w.count - $0.count >= 2 }) {
                w = String(w.dropLast(x.count))
            }
            if w.count < 2 || stop.contains(w) { continue }
            if let f = w.first, f.isASCII, f.isLetter, w.count < 3 { continue }
            out.append(w)
        }
        return out
    }

    private struct Window {
        var start: Double
        var end: Double
        var text: String
        var terms: [String] = []
    }

    static func analyze(_ cues: [Subtitles.Cue], length: Double) -> Result? {
        guard cues.count >= 8 else { return nil }
        // sentences, or about 18 seconds each
        var wins: [Window] = []
        var open = false
        for c in cues {
            if !open { wins.append(Window(start: c.start, end: c.end, text: "")); open = true }
            let i = wins.count - 1
            wins[i].text += (wins[i].text.isEmpty ? "" : " ") + c.text
            wins[i].end = c.end
            if c.end - wins[i].start > 18 || c.text.hasSuffix(".") || c.text.hasSuffix("?") || c.text.hasSuffix("!") || c.text.hasSuffix("。") { open = false }
        }
        for i in wins.indices { wins[i].terms = words(wins[i].text) }
        let n = Double(wins.count)
        var tf: [String: Int] = [:], df: [String: Int] = [:]
        for w in wins {
            for t in w.terms { tf[t, default: 0] += 1 }
            for t in Set(w.terms) { df[t, default: 0] += 1 }
        }
        func idf(_ t: String) -> Double { log(1 + n / Double(df[t] ?? 1)) }
        let keywords = tf.filter { $0.value >= 3 && Double(df[$0.key] ?? 0) < n * 0.6 }
            .sorted { Double($0.value) * idf($0.key) > Double($1.value) * idf($1.key) }
            .prefix(8).map { $0.key }
        var weight: [String: Double] = [:]
        for (t, c) in tf { weight[t] = log(1 + Double(c)) * idf(t) }
        let total = length > 0 ? length : (wins.last?.end ?? 0)
        let k = max(3, min(6, wins.count / 14))
        let gap = total / (Double(k) * 1.6)
        let scored = wins.filter { $0.text.count >= 25 }.map { w -> (Window, Double) in
            let d = Set(w.terms)
            let s = d.reduce(0) { $0 + (weight[$1] ?? 0) }
            return (w, d.isEmpty ? 0 : s / (Double(w.terms.count) + 4).squareRoot())
        }.sorted { $0.1 > $1.1 }
        var picked: [Window] = []
        for (w, _) in scored {
            if picked.count >= k { break }
            if picked.allSatisfy({ abs($0.start - w.start) >= gap }) { picked.append(w) }
        }
        let moments = picked.sorted { $0.start < $1.start }.map { w in
            Moment(start: w.start, text: w.text.count > 160 ? String(w.text.prefix(157)).trimmingCharacters(in: .whitespaces) + "…" : w.text)
        }
        if keywords.isEmpty && moments.isEmpty { return nil }
        return Result(keywords: Array(keywords), moments: moments)
    }
}
