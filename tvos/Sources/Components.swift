import SwiftUI
import UIKit

/// The look: dark, with the TubeHaus teal where the remote is. Sizes are for the 1920×1080 point
/// screen every Apple TV has; pages pad 80 points left and right themselves (their scroll views
/// reach the screen's edges so rows can scroll past them), which leaves 1760 for content.
enum Theme {
    static let teal = Color(red: 0.18, green: 0.83, blue: 0.75)
    static let red = Color(red: 1, green: 0, blue: 0.2)
    static let placeholder = Color.white.opacity(0.08)
    static let edge: CGFloat = 80
    /// four cards across: (1760 − 3 × 40) / 4
    static let card: CGFloat = 410
    static let gap: CGFloat = 40
    static let avatar: CGFloat = 180
}

// MARK: pictures

/// Pictures from YouTube, decoded off the main thread and kept in memory, so a row scrolled back
/// into view shows them at once. A missing size (YouTube answers with a small grey stand-in) counts
/// as no picture, so a list of sizes can be tried best first.
enum Images {
    static let cache: NSCache<NSURL, UIImage> = {
        let c = NSCache<NSURL, UIImage>()
        c.countLimit = 600
        return c
    }()

    static func cached(_ urls: [URL]) -> UIImage? {
        for u in urls { if let i = cache.object(forKey: u as NSURL) { return i } }
        return nil
    }

    static func load(_ urls: [URL]) async -> UIImage? {
        if let i = cached(urls) { return i }
        for u in urls {
            let img = await Task.detached(priority: .utility) { () -> UIImage? in
                guard let (d, r) = try? await URLSession.shared.data(from: u),
                      (r as? HTTPURLResponse)?.statusCode == 200,
                      let i = UIImage(data: d), i.size.width > 130 else { return nil }
                return i.preparingForDisplay() ?? i
            }.value
            if Task.isCancelled { return nil }
            if let img {
                cache.setObject(img, forKey: u as NSURL)
                return img
            }
        }
        return nil
    }
}

/// A picture that fills its frame (cropped, never stretched), with a quiet grey while it loads.
struct Picture: View {
    let urls: [URL]
    @State private var image: UIImage?

    init(_ urls: [URL]) {
        self.urls = urls
        _image = State(initialValue: Images.cached(urls))
    }

    init(_ url: URL?) { self.init(url.map { [$0] } ?? []) }

    var body: some View {
        Rectangle().fill(Theme.placeholder)
            .overlay {
                if let image {
                    Image(uiImage: image).resizable().scaledToFill().transition(.opacity)
                }
            }
            .clipped()
            .task(id: urls) {
                if let i = Images.cached(urls) { image = i; return }
                image = nil      // another video's picture (the next one in Up next)
                guard !urls.isEmpty else { return }
                let i = await Images.load(urls)
                if !Task.isCancelled { withAnimation(.easeOut(duration: 0.2)) { image = i } }
            }
    }
}

/// a round channel picture, or its first letter
struct Avatar: View {
    let url: URL?
    let name: String
    var size: CGFloat = 60

    var body: some View {
        ZStack {
            Circle().fill(Theme.teal.opacity(0.35))
            Text(String(name.prefix(1)).uppercased()).font(.system(size: size * 0.45, weight: .semibold)).foregroundStyle(.white)
            if url != nil { Picture(url).clipShape(Circle()) }
        }
        .frame(width: size, height: size)
    }
}

// MARK: cards

/// a 16:9 thumbnail, with the length (or LIVE) in the corner and how much was watched underneath
struct Thumb: View {
    let urls: [URL]
    var badge: String = ""
    var progress: Double? = nil

    var body: some View {
        Picture(urls)
            .aspectRatio(16 / 9, contentMode: .fit)
            .overlay(alignment: .bottomTrailing) {
                if !badge.isEmpty {
                    Text(badge)
                        .font(.caption2.weight(.bold).monospacedDigit())
                        .padding(.horizontal, 10).padding(.vertical, 4)
                        .background(badge == "LIVE" ? Theme.red : Color.black.opacity(0.8), in: RoundedRectangle(cornerRadius: 6))
                        .foregroundStyle(.white)
                        .padding(12)
                }
            }
            .overlay(alignment: .bottom) {
                if let progress {
                    GeometryReader { g in
                        ZStack(alignment: .leading) {
                            Rectangle().fill(.white.opacity(0.35))
                            Rectangle().fill(Theme.red).frame(width: g.size.width * max(0.03, progress))
                        }
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
        NavigationLink(value: Route.video(video)) {
            Thumb(urls: video.thumb.map { [$0] } ?? [], badge: video.live ? "LIVE" : duration(video.lengthSec), progress: library.progress(video.id))
        }
        .buttonStyle(.card)
        .contextMenu {
            Button(library.isLater(video.id) ? "Remove from Watch later" : "Save to Watch later", systemImage: "clock") {
                library.toggleLater(video)
            }
            if library.progress(video.id) != nil {
                Button("Remove from history", systemImage: "trash") { library.forget(video.id) }
            }
            Button("Not interested", systemImage: "hand.thumbsdown") { library.notInterested(video) }
        }
        .cardCaption {
            Text(video.title).font(.system(size: 26, weight: .semibold)).lineLimit(2)
            Text(video.byline).font(.system(size: 22)).foregroundStyle(.secondary).lineLimit(1)
        }
    }
}

struct ChannelCard: View {
    let channel: Channel
    var size: CGFloat = Theme.avatar

    var body: some View {
        VStack(spacing: 18) {
            NavigationLink(value: Route.channel(channel.id)) {
                Avatar(url: channel.avatarURL, name: channel.name, size: size)
            }
            .buttonStyle(Lift())
            VStack(spacing: 4) {
                Text(channel.name).font(.system(size: 24, weight: .semibold)).lineLimit(1)
                if !channel.subsText.isEmpty { Text(channel.subsText).font(.system(size: 20)).foregroundStyle(.secondary).lineLimit(1) }
            }
            .frame(width: max(size, 220))
        }
    }
}

struct PlaylistCard: View {
    let playlist: Playlist

    var body: some View {
        NavigationLink(value: Route.playlist(playlist.id)) {
            Thumb(urls: playlist.thumbURL.map { [$0] } ?? [], badge: playlist.countText)
                .overlay(alignment: .topLeading) {
                    Image(systemName: "list.bullet.rectangle.fill").font(.system(size: 28)).foregroundStyle(.white)
                        .padding(10).background(.black.opacity(0.6), in: RoundedRectangle(cornerRadius: 8)).padding(12)
                }
        }
        .buttonStyle(.card)
        .cardCaption {
            Text(playlist.title).font(.system(size: 26, weight: .semibold)).lineLimit(2)
            Text(playlist.author).font(.system(size: 22)).foregroundStyle(.secondary).lineLimit(1)
        }
    }
}

struct ItemCard: View {
    let item: Item

    var body: some View {
        switch item {
        case .video(let v): VideoCard(video: v)
        case .channel(let c): ChannelCard(channel: c).frame(maxWidth: .infinity)
        case .playlist(let p): PlaylistCard(playlist: p)
        }
    }
}

extension View {
    /// the title lines under a card, the same height for every card in a row
    func cardCaption<C: View>(@ViewBuilder _ lines: () -> C) -> some View {
        VStack(alignment: .leading, spacing: 16) {
            self
            VStack(alignment: .leading, spacing: 4) { lines() }
                .frame(maxWidth: .infinity, minHeight: 100, alignment: .topLeading)
        }
    }
}

/// what the remote is on grows a little and gets a teal ring (for round pictures and chips,
/// where the square .card look doesn't fit)
struct Lift: ButtonStyle {
    func makeBody(configuration: Configuration) -> some View { Body(configuration: configuration) }

    private struct Body: View {
        let configuration: ButtonStyleConfiguration
        @Environment(\.isFocused) private var focused

        var body: some View {
            configuration.label
                .overlay { Circle().strokeBorder(Theme.teal, lineWidth: focused ? 6 : 0) }
                .scaleEffect(configuration.isPressed ? 1.04 : focused ? 1.12 : 1)
                .shadow(color: .black.opacity(focused ? 0.5 : 0), radius: 20, y: 12)
                .animation(.easeOut(duration: 0.18), value: focused)
        }
    }
}

// MARK: rows and grids

/// a section title, lined up with the cards
struct Heading: View {
    let text: String
    var detail: String = ""

    init(_ text: String, detail: String = "") { self.text = text; self.detail = detail }

    var body: some View {
        HStack(alignment: .firstTextBaseline, spacing: 20) {
            Text(text).font(.system(size: 38, weight: .bold))
            if !detail.isEmpty { Text(detail).font(.system(size: 24)).foregroundStyle(.secondary) }
        }
        .padding(.horizontal, Theme.edge)
    }
}

/// a titled row of cards that scrolls sideways, asking for more as its end comes into view
struct Row: View {
    let title: String
    let items: [Item]
    var more: (() async -> Void)? = nil

    var body: some View {
        Shelf(title: title) {
            ForEach(items) { item in
                ItemCard(item: item)
                    .frame(width: Theme.card)
                    .task { if item.id == items.last?.id, let more { await more() } }
            }
        }
    }
}

/// a titled row of anything that scrolls sideways; the content sizes itself
struct Shelf<Content: View>: View {
    let title: String
    @ViewBuilder var content: Content

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            Heading(title)
            ScrollView(.horizontal) {
                LazyHStack(alignment: .top, spacing: Theme.gap) { content }
                    .padding(.horizontal, Theme.edge).padding(.vertical, 24)
            }
            .scrollClipDisabled()
        }
        .focusSection()
    }
}

/// four cards across that ask for more when the end comes into view
struct CardGrid: View {
    let items: [Item]
    var more: (() async -> Void)? = nil

    private let columns = Array(repeating: GridItem(.flexible(), spacing: Theme.gap, alignment: .top), count: 4)

    var body: some View {
        LazyVGrid(columns: columns, alignment: .leading, spacing: 50) {
            ForEach(items) { item in
                ItemCard(item: item)
                    .task { if item.id == items.last?.id, let more { await more() } }
            }
        }
        .padding(.horizontal, Theme.edge)
        .focusSection()
    }
}

/// a page that scrolls down, reaching the screen's sides (see Theme)
struct Scroller<Content: View>: View {
    @ViewBuilder var content: Content

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 56) { content }
                .padding(.vertical, 40)
                .frame(maxWidth: .infinity, alignment: .leading)
        }
        .ignoresSafeArea(edges: .horizontal)
    }
}

/// what a screen shows while it loads, or when loading failed or found nothing
struct Status: View {
    let error: String?
    var icon: String = "exclamationmark.triangle"
    var retry: (() -> Void)? = nil

    var body: some View {
        VStack(spacing: 28) {
            if let error {
                Image(systemName: icon).font(.system(size: 64)).foregroundStyle(.secondary)
                Text(error).font(.system(size: 30)).multilineTextAlignment(.center).foregroundStyle(.secondary).frame(maxWidth: 1000)
                if let retry { Button("Try again", action: retry) }
            } else {
                ProgressView().scaleEffect(1.4)
            }
        }
        .frame(maxWidth: .infinity, minHeight: 420)
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
