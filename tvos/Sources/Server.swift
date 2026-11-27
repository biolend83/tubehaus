import AVFoundation
import SwiftUI
import Foundation
import Observation

/// A TubeHaus server, when one is connected (Settings → TubeHaus server), as the other apps do it:
/// the library is the same as on the server and every other device (subscriptions, history and where
/// each video stopped, Watch later, likes), videos play through the server (its own HLS, so the TV's
/// address never meets YouTube's bot check), and the Music tab uses the server's music search,
/// albums, artists and lyrics. The address and login are each person's own (People.swift).
@MainActor
@Observable
final class Server {
    static let shared = Server()

    /// "https://tube.example.com", "" when none is connected
    private(set) var base = ""
    private(set) var user = ""
    @ObservationIgnored private(set) var pass = ""
    /// nil fine, else why the last sync failed: "login", "network", "notTubeHaus", "public"
    private(set) var problem: String?
    private(set) var syncedAt: Date?

    var connected: Bool { !base.isEmpty }

    @ObservationIgnored private var store: UserDefaults { Library.shared.defaults }
    @ObservationIgnored private var flushing = false

    private init() { reload() }

    /// the person using the TV changed: their server (or none)
    func reload() {
        base = store.string(forKey: "server") ?? ""
        user = store.string(forKey: "serverUser") ?? ""
        pass = store.string(forKey: "serverPass") ?? ""
        problem = nil
        syncedAt = nil
    }

    // MARK: talking to it

    var authHeader: String? {
        user.isEmpty ? nil : "Basic " + Data("\(user):\(pass)".utf8).base64EncodedString()
    }

    private func request(_ path: String, method: String = "GET", body: Any? = nil, base b: String? = nil, auth: String? = nil) async throws -> Data {
        guard let url = URL(string: (b ?? base) + path) else { throw ServerError.address }
        var r = URLRequest(url: url, timeoutInterval: 30)
        r.httpMethod = method
        r.setValue("application/json", forHTTPHeaderField: "Accept")
        if let a = auth ?? authHeader { r.setValue(a, forHTTPHeaderField: "Authorization") }
        if let body {
            r.httpBody = try JSONSerialization.data(withJSONObject: body)
            r.setValue("application/json", forHTTPHeaderField: "Content-Type")
        }
        // a phone or TV between networks, or the server getting a fresh WARP address: two more tries
        var last: Error = ServerError.network
        for wait in [0.0, 1.5, 3.0] {
            if wait > 0 { try await Task.sleep(nanoseconds: UInt64(wait * 1e9)) }
            do {
                let (d, resp) = try await URLSession.shared.data(for: r)
                let code = (resp as? HTTPURLResponse)?.statusCode ?? 0
                if code == 401 || code == 403 { throw ServerError.login }
                if code == 404 { throw ServerError.notTubeHaus }
                if (502...504).contains(code) { last = ServerError.network; continue }
                if !(200...299).contains(code) { throw ServerError.http(code) }
                return d
            } catch let e as ServerError {
                throw e
            } catch is CancellationError {
                throw CancellationError()
            } catch {
                last = error
            }
        }
        throw last
    }

    private func json(_ path: String) async throws -> [String: Any] {
        let d = try await request(path)
        guard let o = try JSONSerialization.jsonObject(with: d) as? [String: Any] else { throw ServerError.notTubeHaus }
        return o
    }

    enum ServerError: Error, LocalizedError {
        case address, login, notTubeHaus, network, isPublic, http(Int)
        var code: String {
            switch self {
            case .address: return "address"
            case .login: return "login"
            case .notTubeHaus: return "notTubeHaus"
            case .isPublic: return "public"
            default: return "network"
            }
        }
        var errorDescription: String? { Server.say(code) }
    }

    nonisolated static func say(_ code: String) -> String {
        switch code {
        case "address": return "That doesn't look like a web address"
        case "login": return "The user name or password is wrong"
        case "public": return "This is a public instance: it keeps libraries in each browser, so there is nothing to sync"
        case "notTubeHaus": return "No TubeHaus server answered there"
        default: return "Couldn't reach the server"
        }
    }

    /// "tube.example.com" → "https://tube.example.com"
    static func normalize(_ raw: String) -> String? {
        var u = raw.trimmingCharacters(in: .whitespaces)
        while u.hasSuffix("/") { u.removeLast() }
        if u.isEmpty || u.contains(" ") { return nil }
        if !u.hasPrefix("http://") && !u.hasPrefix("https://") { u = "https://" + u }
        guard let c = URLComponents(string: u), c.host?.isEmpty == false else { return nil }
        return u
    }

    // MARK: connecting

    /// checks the server, adds this TV's library to it, then takes the merged library; nil when it worked
    func connect(_ address: String, user u: String, pass p: String) async -> String? {
        guard let b = Self.normalize(address) else { return "address" }
        let auth = u.isEmpty ? nil : "Basic " + Data("\(u):\(p)".utf8).base64EncodedString()
        do {
            let d = try await request("/api/p/health", base: b, auth: auth)
            guard let h = try JSONSerialization.jsonObject(with: d) as? [String: Any], h["ok"] as? Bool == true else { return "notTubeHaus" }
            if h["public"] as? Bool == true { return "public" }
            // the login is checked by a call that needs it
            _ = try await request("/api/p/yt/export?format=tubehaus", base: b, auth: auth)
        } catch let e as ServerError {
            return e.code
        } catch {
            return "network"
        }
        store.set(b, forKey: "server"); store.set(u, forKey: "serverUser"); store.set(p, forKey: "serverPass")
        reload()
        await pushAll()
        await pull()
        return problem
    }

    func disconnect() {
        for k in ["server", "serverUser", "serverPass", "serverQueue"] { store.removeObject(forKey: k) }
        reload()
    }

    // MARK: the library

    /// the server's library in place of this TV's (it already has everything sent from here)
    func pull() async {
        guard connected else { return }
        do {
            let d = try await request("/api/p/yt/export?format=tubehaus")
            guard let o = try JSONSerialization.jsonObject(with: d) as? [String: Any] else { throw ServerError.notTubeHaus }
            // a change made while it was on its way goes first; the next sync brings it back
            if queue.isEmpty { Library.shared.take(server: o) }
            problem = nil
            syncedAt = Date()
        } catch is CancellationError {
        } catch let e as ServerError {
            problem = e.code
        } catch {
            problem = "network"
        }
    }

    /// everything on this TV, merged into the server's library (it adds, never removes)
    private func pushAll() async {
        let text = Library.shared.exportJSON()
        do { _ = try await request("/api/p/yt/import", method: "POST", body: ["text": text]) } catch {}
    }

    /// changes the server hasn't taken yet ("/yt/sub" and its body), kept until it has them
    private var queue: [[String: Any]] {
        get { (store.array(forKey: "serverQueue") as? [[String: Any]]) ?? [] }
        set { store.set(newValue, forKey: "serverQueue") }
    }

    /// one change for the server (the endpoints the web version uses); a newer one with the same key replaces it
    nonisolated func send(_ path: String, key: String = "", _ body: [String: Any]) {
        let b = body as NSDictionary
        Task { @MainActor in self.sendNow(path, key: key, b as? [String: Any] ?? [:]) }
    }

    private func sendNow(_ path: String, key: String, _ body: [String: Any]) {
        guard connected else { return }
        var q = queue
        if !key.isEmpty { q.removeAll { $0["key"] as? String == key } }
        q.append(["path": path, "key": key, "body": body])
        queue = q
        Task { await flush() }
    }

    func flush() async {
        guard connected, !flushing else { return }
        flushing = true
        defer { flushing = false }
        while let op = queue.first {
            do {
                _ = try await request("/api/p" + (op["path"] as? String ?? ""), method: "POST", body: op["body"] ?? [:])
            } catch ServerError.http(let code) where (400...499).contains(code) && code != 429 {
                // refused (a playlist deleted elsewhere, an older server): dropped rather than blocking the rest
            } catch let e as ServerError {
                problem = e.code
                return
            } catch {
                problem = "network"
                return
            }
            var q = queue
            if !q.isEmpty { q.removeFirst() }
            queue = q
        }
    }

    /// when the app comes to the front and every few minutes
    func sync() async {
        await flush()
        await pull()
    }

    // MARK: playing through the server

    /// the server's HLS of a video (all qualities, the original audio first), and what it knows of it
    func stream(_ id: String) async throws -> StreamInfo {
        let o = try await json("/api/v1/videos/\(id)")
        if let e = o["error"] as? String { throw EngineError(kind: "OTHER", message: e) }
        let live = o["liveNow"] as? Bool ?? false
        let hls: URL?
        if live, let h = o["hlsUrl"] as? String { hls = URL(string: h.hasPrefix("/") ? base + h : h) }
        else { hls = URL(string: base + "/api/p/hls/\(id).m3u8") }
        let caps = (o["captions"] as? [[String: Any]] ?? []).compactMap { c -> Caption? in
            guard let u = c["url"] as? String, !u.isEmpty else { return nil }
            let label = c["label"] as? String ?? ""
            return Caption(lang: c["language_code"] as? String ?? c["languageCode"] as? String ?? "", name: label,
                           url: u.hasPrefix("/") ? base + u : u, auto: label.localizedCaseInsensitiveContains("auto-generated"))
        }
        return StreamInfo(title: o["title"] as? String ?? "", author: o["author"] as? String ?? "",
                          channelId: o["authorId"] as? String ?? "", lengthSec: (o["lengthSeconds"] as? NSNumber)?.intValue ?? 0,
                          views: (o["viewCount"] as? NSNumber)?.intValue ?? 0,
                          description: o["description"] as? String ?? "", keywords: o["keywords"] as? [String] ?? [],
                          live: live, hls: hls, captions: caps,
                          storyboard: Storyboard(server: o["storyboards"], base: base),
                          loudnessDb: (o["loudnessDb"] as? NSNumber)?.doubleValue)
    }

    /// the player's item for a URL on the server: its login goes with every request of it
    func asset(_ url: URL) -> AVURLAsset {
        guard connected, url.absoluteString.hasPrefix(base), let a = authHeader else { return AVURLAsset(url: url) }
        return AVURLAsset(url: url, options: ["AVURLAssetHTTPHeaderFieldsKey": ["Authorization": a]])
    }

    /// a caption file from the server (its login with it)
    func data(_ url: URL) async -> Data? {
        var r = URLRequest(url: url, timeoutInterval: 20)
        if url.absoluteString.hasPrefix(base), let a = authHeader { r.setValue(a, forHTTPHeaderField: "Authorization") }
        return try? await URLSession.shared.data(for: r).0
    }

    // MARK: music (the server's YouTube Music: search with the album recordings first, albums, artists, lyrics)

    private func music(_ what: String, _ q: [String: String]) async throws -> [String: Any] {
        var c = URLComponents()
        c.queryItems = (q.merging(["hl": Settings.hl, "region": Settings.gl]) { a, _ in a }).map { URLQueryItem(name: $0.key, value: $0.value) }
        return try await json("/api/p/yt/music/\(what)?" + (c.percentEncodedQuery ?? ""))
    }

    func musicSearch(_ q: String) async throws -> MusicResults {
        let o = try await music("search", ["q": q])
        return MusicResults(artists: Self.cards(o["artists"]), albums: Self.cards(o["albums"]), songs: Self.songs(o["songs"]))
    }

    func musicAlbum(_ id: String) async throws -> MusicPage {
        let o = try await music("album", ["id": id])
        return MusicPage(title: o["title"] as? String ?? "", sub: [o["artist"] as? String, o["sub"] as? String, o["info"] as? String]
                            .compactMap { $0 }.filter { !$0.isEmpty }.joined(separator: " · "),
                         art: o["art"] as? String ?? "", songs: Self.songs(o["songs"]), shelves: [])
    }

    func musicArtist(_ id: String) async throws -> MusicPage {
        let o = try await music("artist", ["id": id])
        let shelves = (o["shelves"] as? [[String: Any]] ?? []).compactMap { s -> (String, [MusicCard])? in
            let items = Self.cards(s["items"])
            return items.isEmpty ? nil : (s["title"] as? String ?? "", items)
        }
        return MusicPage(title: o["name"] as? String ?? o["title"] as? String ?? "", sub: o["audience"] as? String ?? "",
                         art: o["art"] as? String ?? "", songs: Self.songs(o["songs"]), shelves: shelves)
    }

    /// a song's lyrics: [(ms, line)], timed when `synced`
    func lyrics(_ s: Song) async throws -> (synced: Bool, lines: [(Int, String)]) {
        let o = try await music("lyrics", ["v": s.id, "title": s.title, "artist": s.artist, "len": String(s.lengthSec)])
        let lines = (o["lines"] as? [[Any]] ?? []).map { l in ((l.first as? NSNumber)?.intValue ?? 0, l.count > 1 ? (l[1] as? String ?? "") : "") }
        return (o["synced"] as? Bool ?? false, lines)
    }

    private static func cards(_ any: Any?) -> [MusicCard] {
        (any as? [[String: Any]] ?? []).compactMap { c in
            guard let id = c["id"] as? String, !id.isEmpty else { return nil }
            return MusicCard(id: id, title: c["title"] as? String ?? "", sub: c["sub"] as? String ?? "",
                             art: c["art"] as? String ?? "", kind: c["kind"] as? String ?? "")
        }
    }

    private static func songs(_ any: Any?) -> [Song] {
        (any as? [[String: Any]] ?? []).compactMap { s in
            guard let id = s["id"] as? String, !id.isEmpty else { return nil }
            return Song(id: id, title: s["title"] as? String ?? "", artist: s["artist"] as? String ?? "",
                        artistId: s["artistId"] as? String ?? "", album: s["album"] as? String ?? "",
                        albumId: s["albumId"] as? String ?? "", lengthSec: (s["len"] as? NSNumber)?.intValue ?? 0,
                        art: s["art"] as? String ?? "")
        }
    }
}

struct MusicCard: Hashable, Identifiable {
    var id: String
    var title: String
    var sub: String
    var art: String
    /// "album", "artist" or "playlist"
    var kind: String
}

struct Song: Hashable, Identifiable, Codable {
    var id: String
    var title: String
    var artist: String
    var artistId: String = ""
    var album: String = ""
    var albumId: String = ""
    var lengthSec: Int = 0
    var art: String = ""

    /// the square album cover when known, else the video's picture
    var cover: URL? { URL(string: art.isEmpty ? "https://i.ytimg.com/vi/\(id)/hqdefault.jpg" : (art.hasPrefix("//") ? "https:" + art : art)) }
    var video: Video { Video(id: id, title: title, author: artist, channelId: artistId, lengthSec: lengthSec) }
}

struct MusicResults {
    var artists: [MusicCard]
    var albums: [MusicCard]
    var songs: [Song]
}

struct MusicPage {
    var title: String
    var sub: String
    var art: String
    var songs: [Song]
    var shelves: [(String, [MusicCard])]
}

/// the address of a TubeHaus server and, when it has accounts, a user name and password
struct ConnectServer: View {
    @State private var address = ""
    @State private var user = ""
    @State private var pass = ""
    @State private var busy = false
    @State private var problem: String?
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        VStack(alignment: .leading, spacing: 32) {
            Text("Connect to a TubeHaus server").font(Fonts.titleLarge)
            Text("Your library is shared with the server and your other devices, videos play through it, and the Music tab opens.")
                .font(Fonts.bodySmall).foregroundStyle(Theme.sub).frame(maxWidth: 1100, alignment: .leading)
            TextField("Address (tube.example.com)", text: $address).font(Fonts.bodyLarge).frame(width: 900)
                .textContentType(.URL).autocorrectionDisabled().textInputAutocapitalization(.never).firstFocus()
            TextField("User name (if it has accounts)", text: $user).font(Fonts.bodyLarge).frame(width: 900)
                .autocorrectionDisabled().textInputAutocapitalization(.never)
            SecureField("Password", text: $pass).font(Fonts.bodyLarge).frame(width: 900)
            if let p = problem { Text(Server.say(p)).font(Fonts.bodySmall).foregroundStyle(Theme.error) }
            HStack(spacing: 24) {
                FilledButton(busy ? "Connecting…" : "Connect", enabled: !busy && !address.isEmpty) {
                    busy = true; problem = nil
                    Task {
                        let r = await Server.shared.connect(address, user: user, pass: pass)
                        busy = false
                        if let r { problem = r } else { dismiss() }
                    }
                }
                TextButton("Cancel") { dismiss() }
            }
        }
        .padding(80)
    }
}
