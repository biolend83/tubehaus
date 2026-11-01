import Foundation
import Observation

/// Everything the TV keeps: subscriptions, what was watched and where it stopped, Watch later.
/// All on the Apple TV itself (UserDefaults); no account, no server.
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

    private let store = UserDefaults.standard

    private init() {
        subscriptions = read("subscriptions") ?? []
        history = read("history") ?? []
        later = read("later") ?? []
    }

    private func read<T: Decodable>(_ key: String) -> T? {
        store.data(forKey: key).flatMap { try? JSONDecoder().decode(T.self, from: $0) }
    }

    private func write<T: Encodable>(_ key: String, _ value: T) {
        if let d = try? JSONEncoder().encode(value) { store.set(d, forKey: key) }
    }

    // MARK: subscriptions

    func isSubscribed(_ channelId: String) -> Bool { subscriptions.contains { $0.id == channelId } }

    func toggleSubscription(_ c: Channel) {
        if isSubscribed(c.id) { subscriptions.removeAll { $0.id == c.id } }
        else { subscriptions.append(c); subscriptions.sort { $0.name.localizedCaseInsensitiveCompare($1.name) == .orderedAscending } }
        write("subscriptions", subscriptions)
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

    // MARK: Watch later

    func isLater(_ id: String) -> Bool { later.contains { $0.id == id } }

    func toggleLater(_ v: Video) {
        if isLater(v.id) { later.removeAll { $0.id == v.id } } else { later.insert(v, at: 0) }
        write("later", later)
    }
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
        get { UserDefaults.standard.object(forKey: "hideShorts") as? Bool ?? true }
        set { UserDefaults.standard.set(newValue, forKey: "hideShorts") }
    }
}
