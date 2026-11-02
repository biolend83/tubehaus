import Foundation
import Observation

// Home's recommendations, the same way as the Android app (common/.../rec/Recommend.kt), all on
// this TV: it learns from what you watch, how much of it, and what you like or hide, and keeps a
// score per channel and per keyword (a video's tags and #hashtags). Home is built from candidate
// pools (videos related to what you watched, new uploads from your subscriptions, searches for
// the words you like, the topics you picked) ranked by those scores, with every fifth slot kept
// for something new so the feed doesn't collapse into one topic.

/// what was learned
struct Taste: Codable {
    static let cap = 10.0
    private static let decay = 0.98      // per day: old tastes fade

    var ch: [String: Double] = [:]
    var kw: [String: Double] = [:]
    /// what YouTube suggested next to the videos watched lately (newest last)
    var related: [Related] = []
    var hiddenVideos: [String] = []
    var pickedTopics = false
    var decayed: Double = 0

    struct Related: Codable {
        var id: String
        var videos: [Video]
    }

    private static let stop: Set<String> = ["shorts", "short", "youtubeshorts", "viral", "fyp", "trending", "video", "youtube", "new", "official"]

    /// words that describe a video: its tags and the #hashtags in its title
    static func tokens(_ title: String, _ keywords: [String] = []) -> [String] {
        let tags = title.split(separator: " ").filter { $0.hasPrefix("#") && $0.count > 2 }.map { String($0.dropFirst()) }
        var seen = Set<String>()
        return (keywords.prefix(15) + tags).map { $0.trimmingCharacters(in: .whitespaces).lowercased() }
            .map { $0.hasPrefix("#") ? String($0.dropFirst()) : $0 }
            .filter { (2...40).contains($0.count) && !stop.contains($0) && seen.insert($0).inserted }
            .prefix(20).map { $0 }
    }

    /// how much one viewing says you liked it: skipped < 0 < watched < finished < liked
    static func engagement(_ watched: Double, _ length: Double, liked: Bool) -> Double {
        let ratio = max(0, watched) / max(1, length)
        var e: Double
        switch ratio {
        case _ where watched < 2 || ratio < 0.15: e = -0.8
        case ..<0.5: e = 0.2
        case ..<0.85: e = 0.6
        default: e = 1.0
        }
        if liked { e += 2 }
        return e
    }

    private mutating func fade() {
        let now = Date().timeIntervalSince1970
        if decayed == 0 { decayed = now; return }
        let days = (now - decayed) / 86400
        guard days >= 0.25 else { return }
        let f = pow(Self.decay, days)
        ch = ch.mapValues { $0 * f }.filter { abs($0.value) >= 0.05 }
        kw = kw.mapValues { $0 * f }.filter { abs($0.value) >= 0.05 }
        decayed = now
    }

    mutating func learn(_ v: Video, keywords: [String], watched: Double, length: Double, liked: Bool) {
        fade()
        let e = Self.engagement(watched, length, liked: liked)
        if v.channelId.hasPrefix("UC") { ch[v.channelId] = min(Self.cap, max(-Self.cap, (ch[v.channelId] ?? 0) + e)) }
        for k in Self.tokens(v.title, keywords) { kw[k] = min(Self.cap, max(-Self.cap, (kw[k] ?? 0) + e * 0.5)) }
        if ch.count > 400 { ch = Dictionary(uniqueKeysWithValues: ch.sorted { abs($0.value) > abs($1.value) }.prefix(400).map { ($0.key, $0.value) }) }
        if kw.count > 600 { kw = Dictionary(uniqueKeysWithValues: kw.sorted { abs($0.value) > abs($1.value) }.prefix(600).map { ($0.key, $0.value) }) }
    }

    mutating func remember(_ id: String, _ videos: [Video]) {
        related.removeAll { $0.id == id }
        related.append(Related(id: id, videos: Array(videos.filter { !$0.short && !$0.live }.prefix(20))))
        if related.count > 40 { related.removeFirst(related.count - 40) }
    }
}

/// New uploads of every subscription, from their RSS feeds; kept for a quarter of an hour.
@MainActor
@Observable
final class SubFeed {
    static let shared = SubFeed()

    private(set) var videos: [Video] = []
    private(set) var loading = false
    private var cache: [String: (at: Date, videos: [Video])] = [:]

    /// the newest uploads, newest first (Shorts left out when they're hidden)
    func load(force: Bool = false) async {
        guard !loading else { return }
        loading = true
        defer { loading = false }
        let subs = Library.shared.subscriptions.map(\.id)
        let now = Date()
        let todo = subs.filter { force || cache[$0].map { now.timeIntervalSince($0.at) > 900 } ?? true }
        // six channels at a time; one that fails keeps what it had
        await withTaskGroup(of: (String, [Video]?).self) { g in
            var it = todo.makeIterator()
            for _ in 0..<6 { if let c = it.next() { g.addTask { (c, try? await YT.feed(c)) } } }
            for await (c, vs) in g {
                if let vs { cache[c] = (Date(), vs) }
                if let c = it.next() { g.addTask { (c, try? await YT.feed(c)) } }
            }
        }
        let keep = Set(subs)
        cache = cache.filter { keep.contains($0.key) }
        videos = cache.values.flatMap { $0.videos }
            .filter { !($0.short && Settings.hideShorts) }
            .sorted { $0.publishedAt > $1.publishedAt }
    }
}

@MainActor
final class Recommender {
    static let shared = Recommender()

    static let topics = [
        "Music", "Gaming", "News", "Science", "Technology", "Cooking", "Travel", "Sports", "Comedy",
        "Movies", "Podcasts", "Documentaries", "Fitness", "Art", "Nature", "Cars", "Anime", "Education", "DIY", "Finance",
    ]

    private struct Cand {
        var v: Video
        var src: String
        var tag: String?
    }

    private var pools: [String: (at: Date, items: [Cand])] = [:]

    func clearCache() { pools = [:] }

    private func pool(_ key: String, ttl: TimeInterval = 3600, _ fetch: @escaping () async throws -> [Cand]) async -> [Cand] {
        if let p = pools[key], Date().timeIntervalSince(p.at) < ttl { return p.items }
        let items = (try? await fetch()) ?? pools[key]?.items ?? []
        pools[key] = (Date(), items)
        return items
    }

    private func search(_ q: String, _ src: String) async throws -> [Cand] {
        try await YT.search(q, videosOnly: true).items.compactMap(\.video)
            .filter { !$0.short && !($0.lengthSec > 0 && $0.lengthSec <= 60) }
            .map { Cand(v: $0, src: src, tag: q) }
    }

    /// up to `n` videos for Home, leaving out `skip` (what's already shown)
    func home(_ n: Int = 40, skip: Set<String> = []) async -> [Video] {
        let lib = Library.shared
        let t = lib.taste
        var cands: [Cand] = []
        // videos related to what you watched lately
        for r in t.related { cands += r.videos.map { Cand(v: $0, src: "rel") } }
        // subscriptions: the last two weeks
        if !lib.subscriptions.isEmpty {
            if SubFeed.shared.videos.isEmpty { await SubFeed.shared.load() }
            let since = Int(Date().timeIntervalSince1970) - 14 * 86400
            cands += SubFeed.shared.videos.filter { !$0.short && $0.publishedAt > since }.map { Cand(v: $0, src: "sub") }
        }
        // the words you like, and channels you like but aren't subscribed to
        let likedKw = t.kw.filter { $0.value > 0.5 }.sorted { $0.value > $1.value }.prefix(5).map { $0.key }
        let likedCh = t.ch.filter { $0.value > 1.5 && !lib.isSubscribed($0.key) }.sorted { $0.value > $1.value }.prefix(4).map { $0.key }
        var seeds = likedKw
        if cands.count < 30 {
            for topic in Self.topics.shuffled() where seeds.count < 4 && !seeds.contains(topic.lowercased()) { seeds.append(topic) }
        }
        await withTaskGroup(of: [Cand].self) { g in
            for k in seeds {
                let src = likedKw.contains(k) ? "kw" : "pop"
                g.addTask { await self.pool("kw:\(k)") { try await self.search(k, src) } }
            }
            for c in likedCh {
                g.addTask { await self.pool("ch:\(c)") { try await YT.feed(c).filter { !$0.short }.map { Cand(v: $0, src: "chan") } } }
            }
            for await list in g { cands += list }
        }
        return rank(cands, n, skip: skip, t)
    }

    private func rank(_ items: [Cand], _ n: Int, skip: Set<String>, _ t: Taste) -> [Video] {
        let lib = Library.shared
        let hidden = Set(t.hiddenVideos).union(skip)
        let bonus = ["sub": 0.6, "rel": 0.5, "chan": 0.4, "kw": 0.25, "pop": 0.0]
        let now = Int(Date().timeIntervalSince1970)
        var best: [String: (score: Double, c: Cand)] = [:]
        for c in items {
            let v = c.v
            if hidden.contains(v.id) || lib.finished(v.id) || (Settings.hideShorts && v.short) { continue }
            let toks = Taste.tokens(v.title, c.tag.map { [$0] } ?? [])
            let kw = toks.isEmpty ? 0 : toks.reduce(0) { $0 + (t.kw[$1] ?? 0) } / Double(toks.count).squareRoot()
            let fresh = v.publishedAt > 0 && now - v.publishedAt < 3 * 86400
            let s = (t.ch[v.channelId] ?? 0) * 0.5 + 0.6 * kw + (bonus[c.src] ?? 0)
                + 0.1 * log10(1 + Double(v.views)) / 7 + (fresh ? 0.25 : 0) + gauss() * 0.2
            if let old = best[v.id] {
                // the same video from several pools: the best score plus a little for each extra pool
                best[v.id] = (max(old.score, s) + 0.15, s > old.score ? c : old.c)
            } else {
                best[v.id] = (s, c)
            }
        }
        let ranked = best.values.sorted { $0.score > $1.score }.map { $0.c }
        var explore = ranked.filter { $0.src == "pop" || $0.src == "kw" }.shuffled()
        var out: [Video] = []
        var ids = Set<String>()
        var perCh: [String: Int] = [:]
        func take(_ c: Cand) -> Bool {
            let v = c.v
            if ids.contains(v.id) || (!v.channelId.isEmpty && (perCh[v.channelId] ?? 0) >= 3) { return false }
            if (t.ch[v.channelId] ?? 0) <= -3 { return false }      // channels you keep skipping
            out.append(v); ids.insert(v.id)
            perCh[v.channelId, default: 0] += 1
            return true
        }
        for c in ranked {
            if out.count >= n { break }
            if out.count % 5 == 4 {       // every 5th slot: something new
                while let e = explore.popLast() { if take(e) { break } }
                if out.count >= n { break }
            }
            _ = take(c)
        }
        return out
    }

    private func gauss() -> Double {
        let u = max(Double.random(in: 0..<1), 1e-9), v = Double.random(in: 0..<1)
        return (-2 * log(u)).squareRoot() * cos(2 * .pi * v)
    }
}
