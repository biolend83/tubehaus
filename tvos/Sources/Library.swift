import Foundation
import Observation

/// Everything the TV keeps: subscriptions, what was watched and where it stopped, Watch later,
/// likes, and what Home learned. All on the Apple TV itself (UserDefaults), each person's in their
/// own (People.swift); with a TubeHaus server connected, every change goes to it too (Server.swift).
@Observable
final class Library {
    static let shared = Library()

    struct Watched: Codable, Hashable, Identifiable {
        var video: Video
        var position: Double
        var at: Double
        var id: String { video.id }
    }

    private(set) var subscriptions: [Channel] = []
    private(set) var history: [Watched] = []
    private(set) var later: [Video] = []
    private(set) var liked: [Video] = []
    private(set) var disliked: [String] = []
    private(set) var taste = Taste()
    /// channel names, for the list of what was learned (the scores are kept by channel id)
    private(set) var names: [String: String] = [:]

    /// the person using the TV: the first one keeps the TV's own UserDefaults, the others a suite each
    private(set) var defaults = People.defaults(for: People.current)
    private var store: UserDefaults { defaults }

    private init() { load() }

    /// someone else uses the TV now: their things
    func switchTo(_ person: String) {
        defaults = People.defaults(for: person)
        load()
    }

    private func load() {
        subscriptions = read("subscriptions") ?? []
        history = read("history") ?? []
        later = read("later") ?? []
        liked = read("liked") ?? []
        disliked = read("disliked") ?? []
        taste = read("taste") ?? Taste()
        names = read("names") ?? [:]
    }

    private func read<T: Decodable>(_ key: String) -> T? {
        store.data(forKey: key).flatMap { try? JSONDecoder().decode(T.self, from: $0) }
    }

    private func write<T: Encodable>(_ key: String, _ value: T) {
        if let d = try? JSONEncoder().encode(value) { store.set(d, forKey: key) }
    }

    // MARK: subscriptions

    func isSubscribed(_ channelId: String) -> Bool { subscriptions.contains { $0.id == channelId } }

    /// the picture of a channel subscribed to, for lists that came without one
    func avatar(of channelId: String) -> URL? {
        channelId.isEmpty ? nil : subscriptions.first { $0.id == channelId }?.avatarURL
    }

    func toggleSubscription(_ c: Channel) {
        let on = !isSubscribed(c.id)
        if !on { subscriptions.removeAll { $0.id == c.id } }
        else { subscriptions.append(c); sortSubs() }
        write("subscriptions", subscriptions)
        Server.shared.send("/yt/sub", key: "sub:\(c.id)", ["id": c.id, "on": on, "name": c.name, "thumb": c.avatar])
    }

    /// adds the channels not there yet; returns how many were new
    @discardableResult
    func subscribe(_ list: [Channel]) -> Int {
        let have = Set(subscriptions.map(\.id))
        var seen = Set<String>()
        let new = list.filter { !have.contains($0.id) && seen.insert($0.id).inserted }
        guard !new.isEmpty else { return 0 }
        subscriptions += new
        sortSubs()
        write("subscriptions", subscriptions)
        for c in new { Server.shared.send("/yt/sub", key: "sub:\(c.id)", ["id": c.id, "on": true, "name": c.name, "thumb": c.avatar]) }
        return new.count
    }

    /// a newer name or picture for a channel already subscribed
    func refresh(_ c: Channel) {
        guard let i = subscriptions.firstIndex(where: { $0.id == c.id }) else { return }
        let old = subscriptions[i]
        let name = c.name.isEmpty ? old.name : c.name, avatar = c.avatar.isEmpty ? old.avatar : c.avatar
        guard name != old.name || avatar != old.avatar else { return }
        subscriptions[i].name = name
        subscriptions[i].avatar = avatar
        sortSubs()
        write("subscriptions", subscriptions)
    }

    private func sortSubs() {
        subscriptions.sort { $0.name.localizedCaseInsensitiveCompare($1.name) == .orderedAscending }
    }

    // MARK: history and where each video stopped

    /// where to start `id` again: nothing in the first or last few seconds
    func resumeAt(_ id: String, length: Int) -> Double {
        guard let w = history.first(where: { $0.video.id == id }) else { return 0 }
        if w.position < 10 { return 0 }
        if length > 0 && w.position > Double(length) - 15 { return 0 }
        return w.position
    }

    func progress(_ id: String) -> Double? {
        guard let w = history.first(where: { $0.video.id == id }), w.video.lengthSec > 0 else { return nil }
        return min(1, w.position / Double(w.video.lengthSec))
    }

    func watched(_ v: Video, at position: Double) {
        history.removeAll { $0.video.id == v.id }
        history.insert(Watched(video: v, position: position, at: Date().timeIntervalSince1970), at: 0)
        if history.count > 500 { history.removeLast(history.count - 500) }
        write("history", history)
        Server.shared.send("/yt/pos", key: "pos:\(v.id)", ["id": v.id, "t": Int(position), "len": v.lengthSec, "meta": Self.meta(v)])
    }

    func forget(_ id: String) {
        history.removeAll { $0.video.id == id }
        write("history", history)
        Server.shared.send("/yt/hist/del", ["id": id])
    }

    func clearHistory() {
        history = []
        write("history", history)
        Server.shared.send("/yt/hist/del", ["all": true])
    }

    /// started but not finished, newest first
    var continueWatching: [Watched] {
        history.filter { w in
            w.position >= 10 && (w.video.lengthSec == 0 || w.position < Double(w.video.lengthSec) - 15)
        }
    }

    /// watched to (nearly) the end
    func finished(_ id: String) -> Bool {
        guard let w = history.first(where: { $0.video.id == id }) else { return false }
        return w.video.lengthSec > 0 && w.position > Double(w.video.lengthSec) * 0.8
    }

    // MARK: Watch later and likes

    func isLater(_ id: String) -> Bool { later.contains { $0.id == id } }

    func toggleLater(_ v: Video) {
        let on = !isLater(v.id)
        if !on { later.removeAll { $0.id == v.id } } else { later.insert(v, at: 0) }
        write("later", later)
        list("wl", v, on)
    }

    private func list(_ name: String, _ v: Video, _ on: Bool) {
        Server.shared.send("/yt/list", key: "list:\(name):\(v.id)", ["list": name, "on": on, "video": Self.meta(v)])
    }

    func isLiked(_ id: String) -> Bool { liked.contains { $0.id == id } }

    /// a like stays on the TV; it tells Home what to show more of
    func toggleLike(_ v: Video, keywords: [String]) {
        let on = !isLiked(v.id)
        if !on {
            liked.removeAll { $0.id == v.id }
        } else {
            liked.insert(v, at: 0)
            learn(v, keywords: keywords, watched: 0, length: 0, liked: true)
        }
        write("liked", liked)
        list("likes", v, on)
        if disliked.contains(v.id) { disliked.removeAll { $0 == v.id }; write("disliked", disliked); list("dislikes", v, false) }
    }

    func isDisliked(_ id: String) -> Bool { disliked.contains(id) }

    /// a dislike: less from this channel
    func toggleDislike(_ v: Video) {
        let on = !isDisliked(v.id)
        if !on {
            disliked.removeAll { $0 == v.id }
        } else {
            disliked.append(v.id)
            if disliked.count > 2000 { disliked.removeFirst(disliked.count - 2000) }
            if isLiked(v.id) { liked.removeAll { $0.id == v.id }; write("liked", liked); list("likes", v, false) }
            nudge(v, -1.5)
        }
        write("disliked", disliked)
        list("dislikes", v, on)
    }

    // MARK: what Home learned (see Recommend.swift)

    func learn(_ v: Video, keywords: [String], watched: Double, length: Double, liked: Bool = false) {
        taste.learn(v, keywords: keywords, watched: watched, length: length, liked: liked)
        name(v)
        write("taste", taste)
    }

    private func nudge(_ v: Video, _ by: Double) {
        guard !v.channelId.isEmpty else { return }
        taste.ch[v.channelId] = min(Taste.cap, max(-Taste.cap, (taste.ch[v.channelId] ?? 0) + by))
        name(v)
        write("taste", taste)
    }

    private func name(_ v: Video) {
        guard !v.channelId.isEmpty, !v.author.isEmpty, names[v.channelId] != v.author else { return }
        names[v.channelId] = v.author
        write("names", names)
    }

    /// "Don't recommend this channel"
    func hideChannel(_ v: Video) {
        guard !v.channelId.isEmpty else { return }
        taste.ch[v.channelId] = -Taste.cap
        name(v)
        write("taste", taste)
    }

    func forgetChannel(_ id: String) {
        taste.ch[id] = nil
        write("taste", taste)
    }

    func forgetWord(_ w: String) {
        taste.kw[w] = nil
        write("taste", taste)
    }

    func rememberRelated(_ id: String, _ related: [Video]) {
        taste.remember(id, related)
        write("taste", taste)
    }

    func pickTopics(_ topics: [String]) {
        for t in topics { taste.kw[t.lowercased()] = max(2, taste.kw[t.lowercased()] ?? 0) }
        taste.pickedTopics = true
        write("taste", taste)
    }

    func notInterested(_ v: Video) {
        taste.hiddenVideos.append(v.id)
        if taste.hiddenVideos.count > 2000 { taste.hiddenVideos.removeFirst(taste.hiddenVideos.count - 2000) }
        if !v.channelId.isEmpty { taste.ch[v.channelId] = max(-Taste.cap, (taste.ch[v.channelId] ?? 0) - 1.5) }
        write("taste", taste)
    }

    func resetTaste() {
        taste = Taste()
        write("taste", taste)
    }

    // MARK: a TubeHaus server's library (its export, "tubehaus" format: the apps' Library)

    /// what a server keeps of a video
    static func meta(_ v: Video) -> [String: Any] {
        ["id": v.id, "title": v.title, "author": v.author, "authorId": v.channelId, "len": v.lengthSec,
         "published": v.publishedAt, "views": v.views, "live": v.live]
    }

    private static func video(_ m: [String: Any]) -> Video? {
        guard let id = m["id"] as? String, !id.isEmpty else { return nil }
        var v = Video(id: id, title: m["title"] as? String ?? "", author: m["author"] as? String ?? "",
                      channelId: m["authorId"] as? String ?? "", lengthSec: (m["len"] as? NSNumber)?.intValue ?? 0)
        v.views = (m["views"] as? NSNumber)?.intValue ?? 0
        v.publishedAt = (m["published"] as? NSNumber)?.intValue ?? 0
        v.live = m["live"] as? Bool ?? false
        return v
    }

    /// the server's library in place of this TV's lists (what Home learned stays the TV's own)
    func take(server o: [String: Any]) {
        let subs = (o["subs"] as? [String: [String: Any]] ?? [:]).map { id, s in
            Channel(id: id, name: s["name"] as? String ?? "", avatar: s["thumb"] as? String ?? "")
        }
        subscriptions = subs.map { c in
            var c = c
            if c.avatar.isEmpty, let mine = subscriptions.first(where: { $0.id == c.id }) { c.avatar = mine.avatar }
            if c.name.isEmpty, let mine = subscriptions.first(where: { $0.id == c.id }) { c.name = mine.name }
            return c
        }
        sortSubs()
        let pos = o["pos"] as? [String: [NSNumber]] ?? [:]
        history = (o["hist"] as? [[String: Any]] ?? []).prefix(500).compactMap { m in
            guard let v = Self.video(m) else { return nil }
            let p = pos[v.id]
            return Watched(video: v, position: p?.first?.doubleValue ?? 0,
                           at: (m["at"] as? NSNumber)?.doubleValue ?? p.flatMap { $0.count > 2 ? $0[2].doubleValue : nil } ?? 0)
        }
        later = (o["wl"] as? [[String: Any]] ?? []).compactMap(Self.video)
        liked = (o["likes"] as? [[String: Any]] ?? []).compactMap(Self.video)
        disliked = o["dislikes"] as? [String] ?? []
        write("subscriptions", subscriptions); write("history", history); write("later", later)
        write("liked", liked); write("disliked", disliked)
    }

    /// this TV's library as a TubeHaus export, for the server to merge in when it is connected
    func exportJSON() -> String {
        var subs: [String: Any] = [:]
        for c in subscriptions { subs[c.id] = ["name": c.name, "thumb": c.avatar] }
        var pos: [String: Any] = [:]
        for w in history { pos[w.video.id] = [Int(w.position), w.video.lengthSec, Int(w.at)] }
        let o: [String: Any] = [
            "subs": subs, "pos": pos,
            "hist": history.map { var m = Self.meta($0.video); m["at"] = Int($0.at); return m },
            "wl": later.map(Self.meta), "likes": liked.map(Self.meta), "dislikes": disliked,
        ]
        let d = (try? JSONSerialization.data(withJSONObject: o)) ?? Data("{}".utf8)
        return String(data: d, encoding: .utf8) ?? "{}"
    }

    /// nothing to go on yet: Home first asks for a few topics
    var needsTopics: Bool { !taste.pickedTopics && taste.kw.isEmpty && history.isEmpty && subscriptions.isEmpty }
}

/// Settings, all in UserDefaults; read from any thread.
enum Settings {
    /// the language and region YouTube answers in: the TV's own, unless changed in Settings
    static var hl: String {
        UserDefaults.standard.string(forKey: "hl") ?? Locale.preferredLanguages.first.map { String($0.prefix(2)) } ?? "en"
    }

    static var gl: String {
        UserDefaults.standard.string(forKey: "gl") ?? Locale.current.region?.identifier ?? "US"
    }

    static var hideShorts: Bool {
        get { UserDefaults.standard.object(forKey: "hideShorts") as? Bool ?? false }
        set { UserDefaults.standard.set(newValue, forKey: "hideShorts") }
    }

    /// play the next video in "Up next" when one ends
    static var autoplayNext: Bool {
        get { UserDefaults.standard.object(forKey: "autoplayNext") as? Bool ?? true }
        set { UserDefaults.standard.set(newValue, forKey: "autoplayNext") }
    }

    static var originalAudio: Bool {
        get { UserDefaults.standard.object(forKey: "originalAudio") == nil || UserDefaults.standard.bool(forKey: "originalAudio") }
        set { UserDefaults.standard.set(newValue, forKey: "originalAudio") }
    }

    static var sponsorBlock: Bool {
        get { UserDefaults.standard.object(forKey: "sponsorBlock") as? Bool ?? true }
        set { UserDefaults.standard.set(newValue, forKey: "sponsorBlock") }
    }

    static var sponsorBlockCategories: Set<SponsorBlock.Category> {
        get {
            guard let keys = UserDefaults.standard.stringArray(forKey: "sponsorBlockCategories") else {
                return SponsorBlock.Category.defaultAutoSkip
            }
            return Set(keys.compactMap(SponsorBlock.Category.init(rawValue:)))
        }
        set { UserDefaults.standard.set(newValue.map(\.rawValue).sorted(), forKey: "sponsorBlockCategories") }
    }

    /// "This video at a glance" under the video
    static var glance: Bool {
        get { UserDefaults.standard.object(forKey: "glance") as? Bool ?? true }
        set { UserDefaults.standard.set(newValue, forKey: "glance") }
    }

    /// the subtitle language turned on last ("" = off); the next video starts with it too
    static var captions: String {
        get { UserDefaults.standard.string(forKey: "captions") ?? "" }
        set { UserDefaults.standard.set(newValue, forKey: "captions") }
    }

    /// 0 small, 1 medium, 2 large
    static var captionSize: Int {
        get { UserDefaults.standard.object(forKey: "captionSize") as? Int ?? 1 }
        set { UserDefaults.standard.set(newValue, forKey: "captionSize") }
    }

    static var hideComments: Bool {
        get { UserDefaults.standard.bool(forKey: "hideComments") }
        set { UserDefaults.standard.set(newValue, forKey: "hideComments") }
    }

    static var hideRelated: Bool {
        get { UserDefaults.standard.bool(forKey: "hideRelated") }
        set { UserDefaults.standard.set(newValue, forKey: "hideRelated") }
    }

    /// the side menu folded away (the button at its top)
    static var railFolded: Bool {
        get { UserDefaults.standard.bool(forKey: "railFolded") }
        set { UserDefaults.standard.set(newValue, forKey: "railFolded") }
    }
}
