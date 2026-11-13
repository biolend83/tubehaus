import Foundation
import Observation

/// Everything the TV keeps: subscriptions, what was watched and where it stopped, Watch later,
/// likes, and what Home learned. All on the Apple TV itself (UserDefaults); no account, no server.
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

    private let store = UserDefaults.standard

    private init() {
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
        if isSubscribed(c.id) { subscriptions.removeAll { $0.id == c.id } }
        else { subscriptions.append(c); sortSubs() }
        write("subscriptions", subscriptions)
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
    }

    func forget(_ id: String) {
        history.removeAll { $0.video.id == id }
        write("history", history)
    }

    func clearHistory() {
        history = []
        write("history", history)
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
        if isLater(v.id) { later.removeAll { $0.id == v.id } } else { later.insert(v, at: 0) }
        write("later", later)
    }

    func isLiked(_ id: String) -> Bool { liked.contains { $0.id == id } }

    /// a like stays on the TV; it tells Home what to show more of
    func toggleLike(_ v: Video, keywords: [String]) {
        if isLiked(v.id) {
            liked.removeAll { $0.id == v.id }
        } else {
            liked.insert(v, at: 0)
            learn(v, keywords: keywords, watched: 0, length: 0, liked: true)
        }
        write("liked", liked)
        if disliked.contains(v.id) { disliked.removeAll { $0 == v.id }; write("disliked", disliked) }
    }

    func isDisliked(_ id: String) -> Bool { disliked.contains(id) }

    /// a dislike: less from this channel
    func toggleDislike(_ v: Video) {
        if isDisliked(v.id) {
            disliked.removeAll { $0 == v.id }
        } else {
            disliked.append(v.id)
            if disliked.count > 2000 { disliked.removeFirst(disliked.count - 2000) }
            if isLiked(v.id) { liked.removeAll { $0.id == v.id }; write("liked", liked) }
            nudge(v, -1.5)
        }
        write("disliked", disliked)
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
