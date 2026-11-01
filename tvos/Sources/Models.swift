import Foundation

/// What engine.js gives back, read into Swift. Only the fields the TV screens use.
struct Video: Codable, Hashable, Identifiable {
    var id: String
    var title: String
    var author: String
    var channelId: String
    var lengthSec: Int
    var viewsText: String
    var publishedText: String
    var live: Bool
    var publishedAt: Int
    var short: Bool

    var thumb: URL? { URL(string: "https://i.ytimg.com/vi/\(id)/hqdefault.jpg") }

    init?(_ o: [String: Any]) {
        guard let id = o["id"] as? String, !id.isEmpty else { return nil }
        self.id = id
        title = o["title"] as? String ?? ""
        author = o["author"] as? String ?? ""
        channelId = o["channelId"] as? String ?? ""
        lengthSec = (o["lengthSec"] as? NSNumber)?.intValue ?? 0
        viewsText = o["viewsText"] as? String ?? ""
        publishedText = o["publishedText"] as? String ?? ""
        live = o["live"] as? Bool ?? false
        publishedAt = (o["publishedAt"] as? NSNumber)?.intValue ?? 0
        short = o["short"] as? Bool ?? false
    }

    init(id: String, title: String, author: String, channelId: String, lengthSec: Int) {
        self.id = id; self.title = title; self.author = author; self.channelId = channelId
        self.lengthSec = lengthSec; viewsText = ""; publishedText = ""; live = false; publishedAt = 0; short = false
    }
}

struct Channel: Codable, Hashable, Identifiable {
    var id: String
    var name: String
    var avatar: String
    var subsText: String

    var avatarURL: URL? { URL(string: avatar.hasPrefix("//") ? "https:" + avatar : avatar) }

    init?(_ o: [String: Any]) {
        guard let id = o["id"] as? String, !id.isEmpty else { return nil }
        self.id = id
        name = o["name"] as? String ?? ""
        avatar = o["avatar"] as? String ?? ""
        subsText = o["subsText"] as? String ?? ""
    }

    init(id: String, name: String, avatar: String) {
        self.id = id; self.name = name; self.avatar = avatar; subsText = ""
    }
}

struct Playlist: Codable, Hashable, Identifiable {
    var id: String
    var title: String
    var thumb: String
    var countText: String
    var author: String

    var thumbURL: URL? { URL(string: thumb.hasPrefix("//") ? "https:" + thumb : thumb) }

    init?(_ o: [String: Any]) {
        guard let id = o["id"] as? String, !id.isEmpty else { return nil }
        self.id = id
        title = o["title"] as? String ?? ""
        thumb = o["thumb"] as? String ?? ""
        countText = o["countText"] as? String ?? ""
        author = o["author"] as? String ?? ""
    }
}

enum Item: Hashable, Identifiable {
    case video(Video)
    case channel(Channel)
    case playlist(Playlist)

    var id: String {
        switch self {
        case .video(let v): return "v" + v.id
        case .channel(let c): return "c" + c.id
        case .playlist(let p): return "p" + p.id
        }
    }

    init?(_ o: [String: Any]) {
        switch o["type"] as? String {
        case "video": guard let v = Video(o) else { return nil }; self = .video(v)
        case "channel": guard let c = Channel(o) else { return nil }; self = .channel(c)
        case "playlist": guard let p = Playlist(o) else { return nil }; self = .playlist(p)
        default: return nil
        }
    }
}

/// a list and the token for the rest of it
struct Page {
    var items: [Item]
    var next: String?

    init(_ any: Any?) {
        let o = any as? [String: Any] ?? [:]
        items = (o["items"] as? [[String: Any]] ?? []).compactMap(Item.init)
        next = o["next"] as? String
    }
}

struct StreamInfo {
    var title: String
    var author: String
    var channelId: String
    var lengthSec: Int
    var description: String
    var live: Bool
    var hls: URL?
}

struct Details {
    var likesText: String
    var subsText: String
    var avatar: String
    var publishedText: String
    var viewsText: String
    var related: [Item]
    var relatedNext: String?
}

struct ChannelPage {
    var id: String
    var name: String
    var avatar: String
    var banner: String
    var subsText: String
    var videos: Page
}

struct PlaylistPage {
    var title: String
    var author: String
    var countText: String
    var videos: Page
}

/// The engine's operations, typed.
enum YT {
    static let videosTab = "EgZ2aWRlb3PyBgQKAjoA"

    static func search(_ q: String) async throws -> Page { Page(try await Engine.shared.call("search", [q, NSNull()])) }
    static func searchMore(_ token: String) async throws -> Page { Page(try await Engine.shared.call("searchMore", [token])) }
    static func relatedMore(_ token: String) async throws -> Page { Page(try await Engine.shared.call("relatedMore", [token])) }
    static func browseMore(_ token: String) async throws -> Page { Page(try await Engine.shared.call("browseMore", [token])) }

    static func suggestions(_ q: String) async throws -> [String] {
        try await Engine.shared.call("suggestions", [q]) as? [String] ?? []
    }

    static func stream(_ id: String) async throws -> StreamInfo {
        let o = try await Engine.shared.call("stream", [id]) as? [String: Any] ?? [:]
        return StreamInfo(title: o["title"] as? String ?? "", author: o["author"] as? String ?? "",
                      channelId: o["channelId"] as? String ?? "", lengthSec: (o["lengthSec"] as? NSNumber)?.intValue ?? 0,
                      description: o["description"] as? String ?? "", live: o["live"] as? Bool ?? false,
                      hls: (o["hls"] as? String).flatMap(URL.init(string:)))
    }

    static func details(_ id: String) async throws -> Details {
        let o = try await Engine.shared.call("details", [id]) as? [String: Any] ?? [:]
        return Details(likesText: o["likesText"] as? String ?? "", subsText: o["subsText"] as? String ?? "",
                       avatar: o["avatar"] as? String ?? "", publishedText: o["publishedText"] as? String ?? "",
                       viewsText: o["viewsText"] as? String ?? "",
                       related: (o["related"] as? [[String: Any]] ?? []).compactMap(Item.init),
                       relatedNext: o["relatedNext"] as? String)
    }

    static func channel(_ id: String) async throws -> ChannelPage {
        let o = try await Engine.shared.call("channel", [id, videosTab]) as? [String: Any] ?? [:]
        return ChannelPage(id: o["id"] as? String ?? id, name: o["name"] as? String ?? "", avatar: o["avatar"] as? String ?? "",
                           banner: o["banner"] as? String ?? "", subsText: o["subsText"] as? String ?? "",
                           videos: Page(o["items"]))
    }

    static func playlist(_ id: String) async throws -> PlaylistPage {
        let o = try await Engine.shared.call("playlist", [id]) as? [String: Any] ?? [:]
        return PlaylistPage(title: o["title"] as? String ?? "", author: o["author"] as? String ?? "",
                            countText: o["countText"] as? String ?? "", videos: Page(o["items"]))
    }

    /// a channel's newest uploads (its RSS feed, or its videos tab when that fails)
    static func feed(_ channelId: String) async throws -> [Video] {
        let a = try await Engine.shared.call("feed", [channelId]) as? [[String: Any]] ?? []
        return a.compactMap(Video.init)
    }
}

func duration(_ sec: Int) -> String {
    guard sec > 0 else { return "" }
    let h = sec / 3600, m = sec / 60 % 60, s = sec % 60
    return h > 0 ? String(format: "%d:%02d:%02d", h, m, s) : String(format: "%d:%02d", m, s)
}
