import SwiftUI

/// Card sizes for a 1920×1080 screen: four video cards across, with room for the focus lift.
enum Size {
    static let card: CGFloat = 400
    static let thumbH: CGFloat = 225
    static let avatar: CGFloat = 200
}

/// a thumbnail, with the length (or LIVE) in the corner and how much was watched underneath
struct Thumb: View {
    let url: URL?
    var badge: String = ""
    var progress: Double? = nil

    var body: some View {
        AsyncImage(url: url) { img in
            img.resizable().aspectRatio(16 / 9, contentMode: .fill)
        } placeholder: {
            Rectangle().fill(.quaternary)
        }
        .frame(width: Size.card, height: Size.thumbH)
        .clipped()
        .overlay(alignment: .bottomTrailing) {
            if !badge.isEmpty {
                Text(badge)
                    .font(.caption2.weight(.semibold).monospacedDigit())
                    .padding(.horizontal, 8).padding(.vertical, 3)
                    .background(badge == "LIVE" ? Color.red : Color.black.opacity(0.8), in: RoundedRectangle(cornerRadius: 6))
                    .foregroundStyle(.white)
                    .padding(10)
            }
        }
        .overlay(alignment: .bottom) {
            if let progress {
                GeometryReader { g in
                    Rectangle().fill(.red).frame(width: g.size.width * progress, height: 6)
                }
                .frame(height: 6)
            }
        }
    }
}

struct VideoCard: View {
    let video: Video
    @State private var library = Library.shared

    var body: some View {
        VStack(alignment: .leading, spacing: 14) {
            NavigationLink(value: Route.video(video)) {
                Thumb(url: video.thumb, badge: video.live ? "LIVE" : duration(video.lengthSec), progress: library.progress(video.id))
            }
            .buttonStyle(.card)
            .contextMenu {
                Button(library.isLater(video.id) ? "Remove from Watch later" : "Watch later", systemImage: "clock") {
                    library.toggleLater(video)
                }
                if library.progress(video.id) != nil {
                    Button("Remove from history", systemImage: "trash") { library.forget(video.id) }
                }
            }
            VStack(alignment: .leading, spacing: 4) {
                Text(video.title).font(.callout.weight(.medium)).lineLimit(2)
                Text([video.author, video.viewsText, video.publishedText].filter { !$0.isEmpty }.joined(separator: " · "))
                    .font(.caption).foregroundStyle(.secondary).lineLimit(1)
            }
            .frame(width: Size.card, alignment: .leading)
        }
    }
}

struct ChannelCard: View {
    let channel: Channel

    var body: some View {
        VStack(spacing: 14) {
            NavigationLink(value: Route.channel(channel.id)) {
                AsyncImage(url: channel.avatarURL) { img in
                    img.resizable().scaledToFill()
                } placeholder: {
                    Image(systemName: "person.crop.circle.fill").resizable().foregroundStyle(.tertiary)
                }
                .frame(width: Size.avatar, height: Size.avatar)
                .clipShape(Circle())
            }
            .buttonStyle(.borderless)
            VStack(spacing: 4) {
                Text(channel.name).font(.callout.weight(.medium)).lineLimit(1)
                if !channel.subsText.isEmpty { Text(channel.subsText).font(.caption).foregroundStyle(.secondary).lineLimit(1) }
            }
            .frame(width: Size.card)
        }
    }
}

struct PlaylistCard: View {
    let playlist: Playlist

    var body: some View {
        VStack(alignment: .leading, spacing: 14) {
            NavigationLink(value: Route.playlist(playlist.id)) {
                Thumb(url: playlist.thumbURL, badge: playlist.countText)
            }
            .buttonStyle(.card)
            VStack(alignment: .leading, spacing: 4) {
                Text(playlist.title).font(.callout.weight(.medium)).lineLimit(2)
                Text(playlist.author).font(.caption).foregroundStyle(.secondary).lineLimit(1)
            }
            .frame(width: Size.card, alignment: .leading)
        }
    }
}

struct ItemCard: View {
    let item: Item

    var body: some View {
        switch item {
        case .video(let v): VideoCard(video: v)
        case .channel(let c): ChannelCard(channel: c)
        case .playlist(let p): PlaylistCard(playlist: p)
        }
    }
}

/// a titled row that scrolls sideways
struct Row<Content: View>: View {
    let title: String
    @ViewBuilder var content: Content

    var body: some View {
        VStack(alignment: .leading, spacing: 20) {
            Text(title).font(.title3.weight(.semibold)).padding(.leading, 80)
            ScrollView(.horizontal) {
                LazyHStack(alignment: .top, spacing: 48) { content }
                    .padding(.horizontal, 80).padding(.vertical, 30)
            }
            .scrollClipDisabled()
        }
    }
}

/// a grid of cards that asks for more when its end comes into view
struct CardGrid: View {
    let items: [Item]
    var more: (() async -> Void)? = nil

    private let columns = Array(repeating: GridItem(.fixed(Size.card), spacing: 48, alignment: .top), count: 4)

    var body: some View {
        LazyVGrid(columns: columns, alignment: .leading, spacing: 60) {
            ForEach(items) { item in
                ItemCard(item: item)
                    .task { if item.id == items.last?.id, let more { await more() } }
            }
        }
        .padding(.horizontal, 80)
    }
}

/// what a screen shows while it loads, or when loading failed
struct Status: View {
    let error: String?
    var retry: (() -> Void)? = nil

    var body: some View {
        VStack(spacing: 24) {
            if let error {
                Image(systemName: "exclamationmark.triangle").font(.system(size: 60)).foregroundStyle(.secondary)
                Text(error).multilineTextAlignment(.center).foregroundStyle(.secondary)
                if let retry { Button("Try again", action: retry) }
            } else {
                ProgressView()
            }
        }
        .frame(maxWidth: .infinity, minHeight: 400)
    }
}

/// a page of items with "more" handled once at a time
@Observable
final class Feed {
    var items: [Item] = []
    var next: String?
    var error: String?
    var loaded = false
    private var busy = false

    func load(_ first: @escaping () async throws -> Page) async {
        error = nil
        do {
            let p = try await first()
            items = p.items.filter(Feed.keep); next = p.next
        } catch {
            self.error = error.localizedDescription
        }
        loaded = true
    }

    func more(_ rest: @escaping (String) async throws -> Page) async {
        guard let token = next, !busy else { return }
        busy = true
        defer { busy = false }
        if let p = try? await rest(token) {
            let seen = Set(items.map(\.id))
            items += p.items.filter { Feed.keep($0) && !seen.contains($0.id) }
            next = p.next
        } else {
            next = nil
        }
    }

    static func keep(_ i: Item) -> Bool {
        if case .video(let v) = i, v.short, Settings.hideShorts { return false }
        return true
    }
}
