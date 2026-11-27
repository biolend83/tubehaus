import ImageIO
import SwiftUI
import UIKit

/// a chapter of a video, from its description or YouTube's automatic ones
struct Chapter: Hashable, Identifiable {
    var title: String
    var start: Double
    var thumb: String
    var id: Double { start }

    static func list(_ any: Any?) -> [Chapter] {
        (any as? [[String: Any]] ?? []).compactMap { c in
            guard let ms = (c["startMs"] as? NSNumber)?.doubleValue else { return nil }
            return Chapter(title: c["title"] as? String ?? "", start: ms / 1000, thumb: c["thumb"] as? String ?? "")
        }
        .sorted { $0.start < $1.start }
    }

    /// the chapter playing at `t`
    static func at(_ t: Double, in list: [Chapter]) -> Chapter? { list.last { $0.start <= t + 0.5 } }
}

/// YouTube's "Most replayed": how much each slice of the video is watched again, 0…1
struct Heat: Equatable {
    var points: [Double]
    var span: Double
    var peak: Double?

    init?(_ any: Any?) {
        guard let o = any as? [String: Any], let p = o["points"] as? [NSNumber], p.count >= 10,
              let span = (o["spanMs"] as? NSNumber)?.doubleValue, span > 0 else { return nil }
        points = p.map(\.doubleValue)
        self.span = span / 1000
        peak = (o["peakMs"] as? NSNumber).map { $0.doubleValue / 1000 }
    }
}

/// The pictures YouTube shows over the seek bar: sheets of small frames, a frame every few
/// seconds. Read from the player's storyboard spec ("url|w#h#count#cols#rows#interval#name#sigh|…",
/// the largest size last) or from a TubeHaus server's list of them.
struct Storyboard: Hashable {
    var template: String
    var width: Int
    var height: Int
    var count: Int
    var cols: Int
    var rows: Int
    /// milliseconds between frames (0: spread over the video)
    var interval: Double

    init?(spec: String) {
        let parts = spec.components(separatedBy: "|")
        guard parts.count >= 2 else { return nil }
        let i = parts.count - 2                       // the largest
        let f = parts[i + 1].components(separatedBy: "#")
        guard f.count >= 8, let w = Int(f[0]), let h = Int(f[1]), let n = Int(f[2]), let c = Int(f[3]), let r = Int(f[4]),
              w > 0, h > 0, n > 0, c > 0, r > 0 else { return nil }
        var url = parts[0].replacingOccurrences(of: "$L", with: String(i)).replacingOccurrences(of: "$N", with: f[6])
        let sigh = f[7].addingPercentEncoding(withAllowedCharacters: .alphanumerics) ?? f[7]
        url += (url.contains("?") ? "&" : "?") + "sigh=" + sigh
        template = url; width = w; height = h; count = n; cols = c; rows = r; interval = Double(f[5]) ?? 0
    }

    init?(server any: Any?, base: String) {
        guard let list = any as? [[String: Any]], let o = list.last,
              let t = o["templateUrl"] as? String, !t.isEmpty,
              let w = (o["width"] as? NSNumber)?.intValue, let h = (o["height"] as? NSNumber)?.intValue,
              let n = (o["count"] as? NSNumber)?.intValue, let c = (o["storyboardWidth"] as? NSNumber)?.intValue,
              let r = (o["storyboardHeight"] as? NSNumber)?.intValue, w > 0, h > 0, n > 0, c > 0, r > 0 else { return nil }
        template = t; width = w; height = h; count = n; cols = c; rows = r
        interval = (o["interval"] as? NSNumber)?.doubleValue ?? 0
    }

    /// which sheet and where on it the frame for `t` (of a video `length` long) is
    func frame(at t: Double, length: Double) -> (url: URL, rect: CGRect)? {
        let step = interval > 0 ? interval / 1000 : (length > 0 ? length / Double(count) : 0)
        guard step > 0 else { return nil }
        let n = max(0, min(count - 1, Int(t / step)))
        let per = cols * rows
        let sheet = n / per, k = n % per
        let url = template.replacingOccurrences(of: "$M", with: String(sheet))
        guard let u = URL(string: url) else { return nil }
        return (u, CGRect(x: (k % cols) * width, y: (k / cols) * height, width: width, height: height))
    }
}

/// the sheets, kept while a video plays (a few hundred KB each)
@MainActor
enum Sheets {
    private static var got: [URL: CGImage] = [:]
    private static var asked: Set<URL> = []
    private static var order: [URL] = []

    static func image(_ u: URL) -> CGImage? { got[u] }

    static func fetch(_ u: URL) async -> CGImage? {
        if let i = got[u] { return i }
        guard !asked.contains(u) else { return nil }
        asked.insert(u)
        let img = await Task.detached(priority: .userInitiated) { () -> CGImage? in
            guard let (d, r) = try? await URLSession.shared.data(from: u), (r as? HTTPURLResponse)?.statusCode == 200,
                  let src = CGImageSourceCreateWithData(d as CFData, nil) else { return nil }
            return CGImageSourceCreateImageAtIndex(src, 0, nil)
        }.value
        asked.remove(u)
        if let img {
            got[u] = img
            order.append(u)
            if order.count > 24 { got[order.removeFirst()] = nil }
        }
        return img
    }
}

/// the frame of the storyboard for a moment, as a picture (grey while its sheet loads)
struct StoryboardFrame: View {
    let board: Storyboard
    let time: Double
    let length: Double
    @State private var image: CGImage?

    var body: some View {
        let f = board.frame(at: time, length: length)
        ZStack {
            Color.black
            if let image, let f, let crop = image.cropping(to: f.rect) {
                Image(decorative: crop, scale: 1).resizable().interpolation(.medium).scaledToFill()
            }
        }
        .task(id: f?.url) {
            guard let f else { return }
            image = Sheets.image(f.url)
            if image == nil { image = await Sheets.fetch(f.url) }
        }
    }
}
