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
    var views: Int = 0
    var avatar: String = ""

    /// 480×360 with black bars above and below a 16:9 picture; cards crop it to 16:9.
    /// It exists for every video, live ones too.
    var thumb: URL? { URL(string: "https://i.ytimg.com/vi/\(id)/hqdefault.jpg") }

    /// the big picture for the watch page: the sharpest one the video has
    var pictures: [URL] {
        ["maxresdefault", "hq720", "sddefault", "hqdefault"].compactMap { URL(string: "https://i.ytimg.com/vi/\(id)/\($0).jpg") }
    }

    var avatarURL: URL? { avatar.isEmpty ? nil : URL(string: avatar.hasPrefix("//") ? "https:" + avatar : avatar) }

    /// "Channel · 1.2M views · 3 days ago"
    var byline: String {
        [author, viewsText, publishedText.isEmpty ? ago(publishedAt) : publishedText].filter { !$0.isEmpty }.joined(separator: " · ")
    }

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
        views = (o["views"] as? NSNumber)?.intValue ?? 0
        avatar = o["avatar"] as? String ?? ""
        if viewsText.isEmpty && views > 0 { viewsText = shortCount(views) + " views" }
    }

    init(id: String, title: String, author: String, channelId: String, lengthSec: Int) {
        self.id = id; self.title = title; self.author = author; self.channelId = channelId
        self.lengthSec = lengthSec; viewsText = ""; publishedText = ""; live = false; publishedAt = 0; short = false
    }

    // older saved lists have no views or avatar
    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        id = try c.decode(String.self, forKey: .id)
        title = try c.decodeIfPresent(String.self, forKey: .title) ?? ""
        author = try c.decodeIfPresent(String.self, forKey: .author) ?? ""
        channelId = try c.decodeIfPresent(String.self, forKey: .channelId) ?? ""
        lengthSec = try c.decodeIfPresent(Int.self, forKey: .lengthSec) ?? 0
        viewsText = try c.decodeIfPresent(String.self, forKey: .viewsText) ?? ""
        publishedText = try c.decodeIfPresent(String.self, forKey: .publishedText) ?? ""
        live = try c.decodeIfPresent(Bool.self, forKey: .live) ?? false
        publishedAt = try c.decodeIfPresent(Int.self, forKey: .publishedAt) ?? 0
        short = try c.decodeIfPresent(Bool.self, forKey: .short) ?? false
        views = try c.decodeIfPresent(Int.self, forKey: .views) ?? 0
        avatar = try c.decodeIfPresent(String.self, forKey: .avatar) ?? ""
    }
}

struct Channel: Codable, Hashable, Identifiable {
    var id: String
    var name: String
    var avatar: String
    var subsText: String

    var avatarURL: URL? { avatar.isEmpty ? nil : URL(string: avatar.hasPrefix("//") ? "https:" + avatar : avatar) }

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

    var video: Video? { if case .video(let v) = self { return v } else { return nil } }

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

/// one subtitle track; `url` is YouTube's timedtext address
struct Caption: Hashable {
    var lang: String
    var name: String
    var url: String
    var auto: Bool
}

struct StreamInfo {
    var title: String
    var author: String
    var channelId: String
    var lengthSec: Int
    var views: Int
    var description: String
    var keywords: [String]
    var live: Bool
    var hls: URL?
    var captions: [Caption]
}

struct Details {
    var likesText: String
    var subsText: String
    var avatar: String
    var publishedText: String
    var viewsText: String
    var related: [Item]
    var relatedNext: String?
    var commentsToken: String?
}

struct Comment: Identifiable, Hashable {
    var id: String
    var author: String
    var avatar: String
    var text: String
    var likes: String
    var replies: Int
    var published: String
    var byCreator: Bool
    var pinned: Bool

    var avatarURL: URL? { avatar.isEmpty ? nil : URL(string: avatar.hasPrefix("//") ? "https:" + avatar : avatar) }
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
    /// search filter: videos only
    static let onlyVideos = "EgIQAQ%3D%3D"

    static func search(_ q: String, videosOnly: Bool = false) async throws -> Page {
        Page(try await Engine.shared.call("search", [q, videosOnly ? onlyVideos : NSNull()]))
    }
    static func searchMore(_ token: String) async throws -> Page { Page(try await Engine.shared.call("searchMore", [token])) }
    static func relatedMore(_ token: String) async throws -> Page { Page(try await Engine.shared.call("relatedMore", [token])) }
    static func browseMore(_ token: String) async throws -> Page { Page(try await Engine.shared.call("browseMore", [token])) }

    static func suggestions(_ q: String) async throws -> [String] {
        try await Engine.shared.call("suggestions", [q]) as? [String] ?? []
    }

    static func stream(_ id: String) async throws -> StreamInfo {
        let o = try await Engine.shared.call("stream", [id]) as? [String: Any] ?? [:]
        let caps = (o["captions"] as? [[String: Any]] ?? []).compactMap { c -> Caption? in
            guard let url = c["url"] as? String, !url.isEmpty else { return nil }
            return Caption(lang: c["lang"] as? String ?? "", name: c["name"] as? String ?? "", url: url, auto: c["auto"] as? Bool ?? false)
        }
        return StreamInfo(title: o["title"] as? String ?? "", author: o["author"] as? String ?? "",
                          channelId: o["channelId"] as? String ?? "", lengthSec: (o["lengthSec"] as? NSNumber)?.intValue ?? 0,
                          views: (o["views"] as? NSNumber)?.intValue ?? 0,
                          description: o["description"] as? String ?? "", keywords: o["keywords"] as? [String] ?? [],
                          live: o["live"] as? Bool ?? false,
                          hls: (o["hls"] as? String).flatMap(URL.init(string:)), captions: caps)
    }

    static func details(_ id: String) async throws -> Details {
        let o = try await Engine.shared.call("details", [id]) as? [String: Any] ?? [:]
        return Details(likesText: o["likesText"] as? String ?? "", subsText: o["subsText"] as? String ?? "",
                       avatar: o["avatar"] as? String ?? "", publishedText: o["publishedText"] as? String ?? "",
                       viewsText: o["viewsText"] as? String ?? "",
                       related: (o["related"] as? [[String: Any]] ?? []).compactMap(Item.init),
                       relatedNext: o["relatedNext"] as? String, commentsToken: o["commentsToken"] as? String)
    }

    static func comments(_ token: String) async throws -> (items: [Comment], next: String?) {
        let o = try await Engine.shared.call("comments", [token]) as? [String: Any] ?? [:]
        let items = (o["items"] as? [[String: Any]] ?? []).compactMap { c -> Comment? in
            guard let id = c["id"] as? String else { return nil }
            return Comment(id: id, author: c["author"] as? String ?? "", avatar: c["avatar"] as? String ?? "",
                           text: c["text"] as? String ?? "", likes: c["likes"] as? String ?? "",
                           replies: (c["replies"] as? NSNumber)?.intValue ?? 0, published: c["published"] as? String ?? "",
                           byCreator: c["byCreator"] as? Bool ?? false, pinned: c["pinned"] as? Bool ?? false)
        }
        return (items, o["next"] as? String)
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

    /// a link or @handle → ("channel", "UC...") / ("video", id) / ("playlist", id)
    static func resolve(_ url: String) async throws -> (kind: String, id: String)? {
        guard let a = try await Engine.shared.call("resolve", [url]) as? [String], a.count == 2 else { return nil }
        return (a[0], a[1])
    }
}

func duration(_ sec: Int) -> String {
    guard sec > 0 else { return "" }
    let h = sec / 3600, m = sec / 60 % 60, s = sec % 60
    return h > 0 ? String(format: "%d:%02d:%02d", h, m, s) : String(format: "%d:%02d", m, s)
}

/// 1234567 → "1.2M"
func shortCount(_ n: Int) -> String {
    switch n {
    case 1_000_000_000...: return String(format: "%.1fB", Double(n) / 1e9).replacingOccurrences(of: ".0", with: "")
    case 1_000_000...: return String(format: "%.1fM", Double(n) / 1e6).replacingOccurrences(of: ".0", with: "")
    case 1_000...: return String(format: "%.1fK", Double(n) / 1e3).replacingOccurrences(of: ".0", with: "")
    default: return "\(n)"
    }
}

/// epoch seconds → "3 days ago" in the TV's language; "" when unknown
func ago(_ at: Int) -> String {
    guard at > 0 else { return "" }
    let f = RelativeDateTimeFormatter()
    f.unitsStyle = .full
    return f.localizedString(for: Date(timeIntervalSince1970: TimeInterval(at)), relativeTo: Date())
}
