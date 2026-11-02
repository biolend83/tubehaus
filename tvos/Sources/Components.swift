import ImageIO
import SwiftUI
import UIKit

/// The look of the Android TV app (common/src/app/tubehaus/ui/Theme.kt, its dark scheme), at the
/// same size: Android TV draws 1 dp as 2 pixels on a 1080p screen, and an Apple TV screen is
/// 1920×1080 points, so every size here is the Android one in dp times two.
enum Theme {
    static let bg = Color(hex: 0x0F0F0F)
    static let text = Color(hex: 0xF1F1F1)
    static let sub = Color(hex: 0xAAAAAA)
    static let chip = Color(hex: 0x272727)          // surfaceVariant
    static let outline = Color(hex: 0x303030)
    static let container = Color(hex: 0x212121)
    static let pill = Color(hex: 0x4A4458)          // the selected item in the side menu
    static let onPill = Color(hex: 0xE8DEF8)
    static let teal = Color(hex: 0x2DD4BF)
    static let red = Color(hex: 0xFF0033)
    static let blue = Color(hex: 0x065FD4)
    static let link = Color(hex: 0x3EA6FF)
    static let error = Color(hex: 0xFF4E45)
    static let placeholder = Color.white.opacity(0.08)
    /// how strongly what the remote is on lights up (buttons, chips, the side menu)
    static let glow = teal.opacity(0.34)
}

extension Color {
    init(hex: UInt32) {
        self.init(red: Double(hex >> 16 & 0xFF) / 255, green: Double(hex >> 8 & 0xFF) / 255, blue: Double(hex & 0xFF) / 255)
    }
}

/// Material's type scale, doubled
enum Fonts {
    static let titleLarge = Font.system(size: 40, weight: .bold)
    static let titleMedium = Font.system(size: 32, weight: .medium)
    static let titleSmall = Font.system(size: 28, weight: .medium)
    static let bodyLarge = Font.system(size: 32)
    static let bodyMedium = Font.system(size: 28)
    static let bodySmall = Font.system(size: 24)
    static let labelLarge = Font.system(size: 28, weight: .medium)
}

// MARK: focus

/// A card the remote is on gets a teal frame and grows a little, as on Android TV (tvFocus).
struct CardFocus: ButtonStyle {
    var radius: CGFloat = 24
    var scale: CGFloat = 1.04

    func makeBody(configuration: Configuration) -> some View { Focused(configuration: configuration, radius: radius, scale: scale) }

    private struct Focused: View {
        let configuration: ButtonStyleConfiguration
        let radius: CGFloat
        let scale: CGFloat
        @Environment(\.isFocused) private var focused

        var body: some View {
            configuration.label
                .contentShape(RoundedRectangle(cornerRadius: radius))
                .overlay {
                    RoundedRectangle(cornerRadius: radius).strokeBorder(Theme.teal, lineWidth: focused ? 6 : 0)
                }
                .scaleEffect(configuration.isPressed ? 1 : focused ? scale : 1)
                .animation(.easeOut(duration: 0.15), value: focused)
        }
    }
}

/// A button, chip or menu item the remote is on glows teal behind its label (Android's ripple
/// on focus). `fill` is its colour when not focused.
struct Glow<S: Shape>: ButtonStyle {
    var shape: S
    var fill: Color = .clear

    func makeBody(configuration: Configuration) -> some View { Focused(configuration: configuration, shape: shape, fill: fill) }

    private struct Focused: View {
        let configuration: ButtonStyleConfiguration
        let shape: S
        let fill: Color
        @Environment(\.isFocused) private var focused

        var body: some View {
            configuration.label
                .background { shape.fill(fill) }
                .overlay { shape.fill(focused ? Theme.glow : Color.clear).allowsHitTesting(false) }
                .contentShape(shape)
                .scaleEffect(configuration.isPressed ? 0.97 : focused ? 1.05 : 1)
                .animation(.easeOut(duration: 0.15), value: focused)
        }
    }
}

extension ButtonStyle where Self == Glow<Capsule> {
    static var glow: Glow<Capsule> { Glow(shape: Capsule()) }
}

extension ButtonStyle where Self == Glow<RoundedRectangle> {
    static func glow(radius: CGFloat, fill: Color = .clear) -> Glow<RoundedRectangle> {
        Glow(shape: RoundedRectangle(cornerRadius: radius), fill: fill)
    }
}

// MARK: pictures

/// Pictures from YouTube, made no bigger than they're shown (a 1280×720 picture is 3.5 MB
/// decoded; the TV has little memory) and kept in memory, so a row scrolled back shows them at
/// once. A missing size (YouTube answers 404, or with a small grey stand-in) counts as no
/// picture, so a list of sizes can be tried best first.
enum Images {
    static let cache: NSCache<NSURL, UIImage> = {
        let c = NSCache<NSURL, UIImage>()
        c.totalCostLimit = 160 * 1024 * 1024
        return c
    }()

    static func cached(_ urls: [URL]) -> UIImage? {
        for u in urls { if let i = cache.object(forKey: u as NSURL) { return i } }
        return nil
    }

    static func load(_ urls: [URL], maxPixels: Int = 1000, minWidth: Int = 131) async -> UIImage? {
        if let i = cached(urls) { return i }
        for u in urls {
            let img = await Task.detached(priority: .utility) { () -> UIImage? in
                guard let (d, r) = try? await URLSession.shared.data(from: u),
                      (r as? HTTPURLResponse)?.statusCode == 200 else { return nil }
                return Images.decode(d, maxPixels, minWidth)
            }.value
            if Task.isCancelled { return nil }
            if let img {
                let cost = Int(img.size.width * img.size.height * img.scale * img.scale * 4)
                cache.setObject(img, forKey: u as NSURL, cost: cost)
                return img
            }
        }
        return nil
    }

    static func decode(_ d: Data, _ maxPixels: Int, _ minWidth: Int) -> UIImage? {
        guard let src = CGImageSourceCreateWithData(d as CFData, nil),
              let props = CGImageSourceCopyPropertiesAtIndex(src, 0, nil) as? [CFString: Any],
              let w = props[kCGImagePropertyPixelWidth] as? Int, w >= minWidth else { return nil }
        let opts: [CFString: Any] = [
            kCGImageSourceCreateThumbnailFromImageAlways: true,
            kCGImageSourceCreateThumbnailWithTransform: true,
            kCGImageSourceShouldCacheImmediately: true,
            kCGImageSourceThumbnailMaxPixelSize: maxPixels,
        ]
        guard let cg = CGImageSourceCreateThumbnailAtIndex(src, 0, opts as CFDictionary) else { return nil }
        return UIImage(cgImage: cg)
    }
}

/// A picture that fills its frame (cropped, never stretched), with a quiet grey while it loads.
struct Picture: View {
    let urls: [URL]
    var maxPixels = 1000
    /// thumbnails narrower than this are YouTube's grey stand-in; channel pictures are small for real
    var minWidth = 131
    @State private var image: UIImage?

    init(_ urls: [URL], maxPixels: Int = 1000, minWidth: Int = 131) {
        self.urls = urls
        self.maxPixels = maxPixels
        self.minWidth = minWidth
        _image = State(initialValue: Images.cached(urls))
    }

    init(_ url: URL?, maxPixels: Int = 1000) { self.init(url.map { [$0] } ?? [], maxPixels: maxPixels) }

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
                let i = await Images.load(urls, maxPixels: maxPixels, minWidth: minWidth)
                if !Task.isCancelled { withAnimation(.easeOut(duration: 0.2)) { image = i } }
            }
    }
}

/// a round channel picture, or its first letter
struct Avatar: View {
    let url: URL?
    let name: String
    var size: CGFloat = 72

    var body: some View {
        ZStack {
            Circle().fill(Theme.chip)
            Text(String(name.prefix(1)).uppercased()).font(.system(size: size * 0.42, weight: .medium)).foregroundStyle(Theme.text)
            if let url { Picture([url], maxPixels: 300, minWidth: 1).clipShape(Circle()) }
        }
        .frame(width: size, height: size)
    }
}

func imageURL(_ s: String) -> URL? {
    s.isEmpty ? nil : URL(string: s.hasPrefix("//") ? "https:" + s : s)
}

/// The TubeHaus house: teal, a dark door and a white play mark (App.kt's HouseMark, on its 512 grid).
struct HouseMark: View {
    var body: some View {
        Canvas { ctx, size in
            // the house spans x 72…440, y 82…428 of the 512 grid; fit that box
            let s = min(size.width / 368, size.height / 346)
            let dx = (size.width - 368 * s) / 2 - 72 * s, dy = (size.height - 346 * s) / 2 - 82 * s
            func p(_ x: CGFloat, _ y: CGFloat) -> CGPoint { CGPoint(x: x * s + dx, y: y * s + dy) }
            var house = Path()
            house.move(to: p(72, 244)); house.addLine(to: p(256, 82)); house.addLine(to: p(440, 244))
            house.addLine(to: p(398, 244)); house.addLine(to: p(398, 428)); house.addLine(to: p(114, 428))
            house.addLine(to: p(114, 244)); house.closeSubpath()
            ctx.fill(house, with: .color(Theme.teal))
            var door = Path()
            door.move(to: p(204, 428)); door.addLine(to: p(204, 312))
            door.addArc(center: p(256, 312), radius: 52 * s, startAngle: .degrees(180), endAngle: .degrees(0), clockwise: false)
            door.addLine(to: p(308, 428)); door.closeSubpath()
            ctx.fill(door, with: .color(Color(hex: 0x14283F)))
            var play = Path()
            play.move(to: p(226, 286)); play.addLine(to: p(284, 312)); play.addLine(to: p(226, 338)); play.closeSubpath()
            ctx.fill(play, with: .color(.white))
        }
    }
}

struct Logo: View {
    var body: some View {
        HStack(spacing: 12) {
            HouseMark().frame(width: 52, height: 44)
            Text("TubeHaus").font(.system(size: 38, weight: .bold)).tracking(-1).foregroundStyle(Theme.text)
        }
    }
}

// MARK: buttons and chips

/// Material's text button: a label that glows when the remote is on it
struct TextButton: View {
    let title: String
    var icon: String?
    var color: Color
    let action: () -> Void

    init(_ title: String, icon: String? = nil, color: Color = Theme.text, action: @escaping () -> Void) {
        self.title = title; self.icon = icon; self.color = color; self.action = action
    }

    var body: some View {
        Button(action: action) {
            HStack(spacing: 12) {
                if let icon { Image(systemName: icon).font(.system(size: 28, weight: .medium)) }
                Text(title).font(Fonts.labelLarge).lineLimit(1)
            }
            .foregroundStyle(color)
            .padding(.horizontal, 28).frame(height: 80)
        }
        .buttonStyle(.glow)
    }
}

/// Material's filled button (white on the dark theme)
struct FilledButton: View {
    let title: String
    var icon: String?
    var enabled: Bool
    let action: () -> Void

    init(_ title: String, icon: String? = nil, enabled: Bool = true, action: @escaping () -> Void) {
        self.title = title; self.icon = icon; self.enabled = enabled; self.action = action
    }

    var body: some View {
        Button(action: action) {
            HStack(spacing: 12) {
                if let icon { Image(systemName: icon).font(.system(size: 28, weight: .medium)) }
                Text(title).font(Fonts.labelLarge).lineLimit(1)
            }
            .foregroundStyle(enabled ? Theme.bg : Theme.sub)
            .padding(.horizontal, 48).frame(height: 80)
        }
        .buttonStyle(Glow(shape: Capsule(), fill: enabled ? Theme.text : Theme.chip))
        .disabled(!enabled)
    }
}

/// a round button with one icon (the search and settings icons in the top bar, ✕ on a row)
struct IconButton: View {
    let icon: String
    var size: CGFloat = 48
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            Group {
                if icon == MenuOpen.name {
                    MenuOpen().fill(Theme.text).frame(width: size * 0.75, height: size * 0.75)
                } else {
                    Image(systemName: icon).font(.system(size: size * 0.75, weight: .regular))
                }
            }
            .foregroundStyle(Theme.text)
            .frame(width: size * 1.75, height: size * 1.75)
        }
        .buttonStyle(Glow(shape: Circle()))
    }
}

/// Android's "menu open" icon (three lines and a ‹) for folding the side menu away; SF Symbols
/// has nothing like it. Drawn on Material's 24 grid.
struct MenuOpen: Shape {
    static let name = "menu.open"

    func path(in r: CGRect) -> Path {
        let k = min(r.width, r.height) / 24
        func p(_ x: CGFloat, _ y: CGFloat) -> CGPoint { CGPoint(x: r.minX + x * k, y: r.minY + y * k) }
        var path = Path()
        path.addRect(CGRect(origin: p(3, 6), size: CGSize(width: 13 * k, height: 2 * k)))
        path.addRect(CGRect(origin: p(3, 11), size: CGSize(width: 10 * k, height: 2 * k)))
        path.addRect(CGRect(origin: p(3, 16), size: CGSize(width: 13 * k, height: 2 * k)))
        path.addLines([p(21, 8.41), p(19.59, 7), p(14.59, 12), p(19.59, 17), p(21, 15.59), p(17.42, 12)])
        path.closeSubpath()
        return path
    }
}

/// Material's chips: an assist chip (outlined, with an icon) or a filter chip (filled with a ✓ when on)
struct Chip: View {
    let title: String
    var icon: String?
    var selected: Bool
    var filter: Bool
    let action: () -> Void

    init(_ title: String, icon: String? = nil, selected: Bool = false, filter: Bool = false, action: @escaping () -> Void) {
        self.title = title; self.icon = icon; self.selected = selected; self.filter = filter; self.action = action
    }

    var body: some View {
        Button(action: action) {
            HStack(spacing: 16) {
                if filter && selected {
                    Image(systemName: "checkmark").font(.system(size: 28, weight: .semibold))
                } else if let icon {
                    Image(systemName: icon).font(.system(size: 30))
                }
                Text(title).font(Fonts.labelLarge).lineLimit(1)
            }
            .foregroundStyle(selected ? Theme.onPill : (filter ? Theme.sub : Theme.text))
            .padding(.horizontal, 28).frame(height: 64)
            .overlay {
                RoundedRectangle(cornerRadius: 16).strokeBorder(selected ? Color.clear : Theme.outline, lineWidth: 2)
            }
        }
        .buttonStyle(.glow(radius: 16, fill: selected ? Theme.pill : .clear))
    }
}

/// Subscribe (white) / Subscribed (grey), as in the Android app
struct SubscribeButton: View {
    let channel: Channel
    @State private var library = Library.shared

    var body: some View {
        let on = library.isSubscribed(channel.id)
        Button { library.toggleSubscription(channel) } label: {
            Text(on ? "Subscribed" : "Subscribe")
                .font(Fonts.labelLarge)
                .foregroundStyle(on ? Theme.text : Theme.bg)
                .padding(.horizontal, 40).frame(height: 72)
        }
        .buttonStyle(Glow(shape: Capsule(), fill: on ? Theme.chip : Theme.text))
    }
}

/// the ⋮ at the end of a card; holding OK on the card opens what it stands for
struct MoreDots: View {
    var body: some View {
        Image(systemName: "ellipsis").rotationEffect(.degrees(90))
            .font(.system(size: 30, weight: .bold)).foregroundStyle(Theme.text)
            .frame(width: 48, height: 72)
    }
}

// MARK: cards and rows

/// a picture with the length (or LIVE) in the corner and how much was watched along the bottom
struct Thumb: View {
    let urls: [URL]
    var badge: String = ""
    var progress: Double? = nil
    var radius: CGFloat = 24

    var body: some View {
        Color.clear
            .aspectRatio(16 / 9, contentMode: .fit)
            .overlay { Picture(urls) }
            .overlay(alignment: .bottomTrailing) {
                if !badge.isEmpty {
                    Text(badge)
                        .font(.system(size: 24, weight: .medium).monospacedDigit())
                        .padding(.horizontal, 10).padding(.vertical, 4)
                        .background(badge == "LIVE" ? Theme.red : Color.black.opacity(0.8), in: RoundedRectangle(cornerRadius: 8))
                        .foregroundStyle(.white)
                        .padding(12)
                }
            }
            .overlay(alignment: .bottom) {
                if let progress {
                    GeometryReader { g in
                        ZStack(alignment: .leading) {
                            Rectangle().fill(Color.white.opacity(0.35))
                            Rectangle().fill(Theme.red).frame(width: g.size.width * max(0.03, progress))
                        }
                    }
                    .frame(height: 6)
                }
            }
            .clipShape(RoundedRectangle(cornerRadius: radius))
    }
}

/// what holding OK on a video offers (Android's ⋮ menu)
struct VideoMenu: ViewModifier {
    let video: Video
    var history = false
    @State private var library = Library.shared

    func body(content: Content) -> some View {
        content.contextMenu {
            Button(library.isLater(video.id) ? "Remove from Watch later" : "Save to Watch later", systemImage: "clock") {
                library.toggleLater(video)
            }
            if history {
                Button("Remove from history", systemImage: "trash") { library.forget(video.id) }
            }
            Button("Not interested", systemImage: "hand.thumbsdown") { library.notInterested(video) }
            if !video.channelId.isEmpty {
                Button("Don't recommend this channel", systemImage: "nosign") { library.hideChannel(video) }
            }
        }
    }
}

extension View {
    func videoMenu(_ v: Video, history: Bool = false) -> some View { modifier(VideoMenu(video: v, history: history)) }
}

/// The big card of Home and Subscriptions: the picture, then the channel's picture, the title
/// (two lines) and "Channel · views · when" (two lines) and ⋮.
struct VideoCard: View {
    let video: Video
    @State private var library = Library.shared

    var body: some View {
        Button { Nav.shared.open(video) } label: {
            VStack(alignment: .leading, spacing: 0) {
                Thumb(urls: video.cardPictures, badge: video.badge, progress: library.progress(video.id))
                HStack(alignment: .top, spacing: 24) {
                    Avatar(url: video.avatarURL ?? library.avatar(of: video.channelId), name: video.author, size: 72)
                    VStack(alignment: .leading, spacing: 4) {
                        Text(video.title).font(Fonts.titleSmall).foregroundStyle(Theme.text).lineLimit(2)
                        Text(video.byline).font(Fonts.bodySmall).foregroundStyle(Theme.sub).lineLimit(2)
                    }
                    .frame(maxWidth: .infinity, alignment: .leading)
                    MoreDots()
                }
                .padding(.leading, 24).padding(.top, 20).padding(.trailing, 8).padding(.bottom, 24)
            }
            .frame(maxWidth: .infinity, alignment: .leading)
        }
        .buttonStyle(CardFocus(radius: 24))
        .videoMenu(video, history: library.progress(video.id) != nil)
    }
}

/// a video as a line of a list: the picture on the left, the words on the right
struct VideoRow: View {
    let video: Video
    var thumb: CGFloat = 336
    var history = false
    var dots = true
    var action: (() -> Void)? = nil
    @State private var library = Library.shared

    var body: some View {
        Button { if let action { action() } else { Nav.shared.open(video) } } label: {
            HStack(alignment: .top, spacing: 24) {
                Thumb(urls: video.cardPictures, badge: video.badge, progress: library.progress(video.id), radius: 16)
                    .frame(width: thumb)
                VStack(alignment: .leading, spacing: 6) {
                    Text(video.title).font(Fonts.titleSmall).foregroundStyle(Theme.text).lineLimit(2)
                    Text(video.author).font(Fonts.bodySmall).foregroundStyle(Theme.sub).lineLimit(1)
                    if !rest.isEmpty {
                        Text(rest).font(Fonts.bodySmall).foregroundStyle(Theme.sub).lineLimit(1)
                    }
                }
                .frame(maxWidth: .infinity, alignment: .leading)
                if dots { MoreDots() }
            }
            .padding(.horizontal, 24).padding(.vertical, 12)
            .frame(maxWidth: .infinity, alignment: .leading)
        }
        .buttonStyle(CardFocus(radius: 20, scale: 1.02))
        .videoMenu(video, history: history)
    }

    private var rest: String {
        [video.viewsText, video.publishedText.isEmpty ? ago(video.publishedAt) : video.publishedText]
            .filter { !$0.isEmpty }.joined(separator: " · ")
    }
}

/// a channel in a list: its picture, name and subscribers, and Subscribe
struct ChannelRow: View {
    let channel: Channel

    var body: some View {
        HStack(spacing: 24) {
            Button { Nav.shared.push(.channel(channel.id)) } label: {
                HStack(spacing: 24) {
                    Avatar(url: channel.avatarURL, name: channel.name, size: 176)
                        .frame(width: 336)
                    VStack(alignment: .leading, spacing: 6) {
                        Text(channel.name).font(Fonts.titleMedium).foregroundStyle(Theme.text).lineLimit(1)
                        if !channel.subsText.isEmpty {
                            Text(channel.subsText).font(Fonts.bodySmall).foregroundStyle(Theme.sub).lineLimit(1)
                        }
                    }
                    .frame(maxWidth: .infinity, alignment: .leading)
                }
                .padding(.horizontal, 24).padding(.vertical, 12)
            }
            .buttonStyle(CardFocus(radius: 20, scale: 1.02))
            SubscribeButton(channel: channel)
                .padding(.trailing, 24)
        }
    }
}

struct PlaylistRow: View {
    let playlist: Playlist
    var thumb: CGFloat = 336

    var body: some View {
        Button { Nav.shared.push(.playlist(playlist.id)) } label: {
            HStack(alignment: .top, spacing: 24) {
                PlaylistThumb(playlist: playlist, radius: 16).frame(width: thumb)
                VStack(alignment: .leading, spacing: 6) {
                    Text(playlist.title).font(Fonts.titleSmall).foregroundStyle(Theme.text).lineLimit(2)
                    Text([playlist.author, "Playlist"].filter { !$0.isEmpty }.joined(separator: " · "))
                        .font(Fonts.bodySmall).foregroundStyle(Theme.sub).lineLimit(1)
                }
                .frame(maxWidth: .infinity, alignment: .leading)
            }
            .padding(.horizontal, 24).padding(.vertical, 12)
        }
        .buttonStyle(CardFocus(radius: 20, scale: 1.02))
    }
}

/// a playlist's picture, with the list mark and how many videos it has
struct PlaylistThumb: View {
    let playlist: Playlist
    var radius: CGFloat = 24

    var body: some View {
        Thumb(urls: playlist.thumbURL.map { [$0] } ?? [], radius: radius)
            .overlay(alignment: .bottomTrailing) {
                Label(playlist.countText, systemImage: "list.bullet")
                    .font(.system(size: 24, weight: .medium))
                    .padding(.horizontal, 12).padding(.vertical, 4)
                    .background(Color.black.opacity(0.8), in: RoundedRectangle(cornerRadius: 8))
                    .foregroundStyle(.white)
                    .padding(12)
            }
    }
}

/// a channel or playlist in the grid of cards (search results shown as a grid)
struct ChannelCard: View {
    let channel: Channel

    var body: some View {
        VStack(spacing: 20) {
            Button { Nav.shared.push(.channel(channel.id)) } label: {
                VStack(spacing: 20) {
                    Avatar(url: channel.avatarURL, name: channel.name, size: 240)
                    Text(channel.name).font(Fonts.titleMedium).foregroundStyle(Theme.text).lineLimit(1)
                    if !channel.subsText.isEmpty { Text(channel.subsText).font(Fonts.bodySmall).foregroundStyle(Theme.sub).lineLimit(1) }
                }
                .padding(24)
                .frame(maxWidth: .infinity)
            }
            .buttonStyle(CardFocus(radius: 24))
            SubscribeButton(channel: channel)
        }
    }
}

struct PlaylistCard: View {
    let playlist: Playlist

    var body: some View {
        Button { Nav.shared.push(.playlist(playlist.id)) } label: {
            VStack(alignment: .leading, spacing: 0) {
                PlaylistThumb(playlist: playlist)
                VStack(alignment: .leading, spacing: 4) {
                    Text(playlist.title).font(Fonts.titleSmall).foregroundStyle(Theme.text).lineLimit(2)
                    Text([playlist.author, "Playlist"].filter { !$0.isEmpty }.joined(separator: " · "))
                        .font(Fonts.bodySmall).foregroundStyle(Theme.sub).lineLimit(1)
                }
                .padding(.horizontal, 24).padding(.top, 20).padding(.bottom, 24)
            }
        }
        .buttonStyle(CardFocus(radius: 24))
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

struct ItemRow: View {
    let item: Item

    var body: some View {
        switch item {
        case .video(let v): VideoRow(video: v)
        case .channel(let c): ChannelRow(channel: c)
        case .playlist(let p): PlaylistRow(playlist: p)
        }
    }
}

/// Cards two across (at least 640 points wide each, like Android's Adaptive(320.dp)), asking for
/// more as the end comes into view. `header` sits above them, inside the same scrolling page.
struct ItemGrid<Header: View>: View {
    let items: [Item]
    let more: (() async -> Void)?
    let focusFirst: Bool
    let header: Header
    @FocusState private var focused: String?

    init(items: [Item], more: (() async -> Void)? = nil, focusFirst: Bool = false, @ViewBuilder header: () -> Header) {
        self.items = items; self.more = more; self.focusFirst = focusFirst; self.header = header()
    }

    private let columns = [GridItem(.adaptive(minimum: 640), spacing: 32, alignment: .top)]

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 0) {
                header
                LazyVGrid(columns: columns, alignment: .leading, spacing: 8) {
                    ForEach(items) { item in
                        ItemCard(item: item)
                            .focused($focused, equals: item.id)
                            .onAppear { nearEnd(item) }
                    }
                }
                .padding(.horizontal, 32).padding(.vertical, 16)
                .focusSection()
            }
        }
        .onAppear { if focusFirst, focused == nil { focused = items.first?.id } }
        .onChange(of: items.first?.id) { if focusFirst, focused == nil { focused = items.first?.id } }
    }

    private func nearEnd(_ item: Item) {
        guard let more, items.suffix(4).contains(where: { $0.id == item.id }) else { return }
        Task { await more() }
    }
}

extension ItemGrid where Header == EmptyView {
    init(items: [Item], more: (() async -> Void)? = nil, focusFirst: Bool = false) {
        self.init(items: items, more: more, focusFirst: focusFirst) { EmptyView() }
    }
}

/// A list of rows, one under another, asking for more near the end.
struct ItemList<Header: View>: View {
    let items: [Item]
    let more: (() async -> Void)?
    let focusFirst: Bool
    let header: Header
    @FocusState private var focused: String?

    init(items: [Item], more: (() async -> Void)? = nil, focusFirst: Bool = false, @ViewBuilder header: () -> Header) {
        self.items = items; self.more = more; self.focusFirst = focusFirst; self.header = header()
    }

    var body: some View {
        ScrollView {
            LazyVStack(alignment: .leading, spacing: 8) {
                header
                ForEach(items) { item in
                    ItemRow(item: item)
                        .focused($focused, equals: item.id)
                        .onAppear {
                            if let more, items.suffix(4).contains(where: { $0.id == item.id }) { Task { await more() } }
                        }
                }
            }
            .padding(.horizontal, 32).padding(.vertical, 16)
        }
        .onAppear { if focusFirst, focused == nil { focused = items.first?.id } }
        .onChange(of: items.first?.id) { if focusFirst, focused == nil { focused = items.first?.id } }
    }
}

/// Where the remote starts when a page opens. Left alone, tvOS starts at the top left - the
/// TubeHaus logo or the side menu - where Android starts on the page itself. Only the first time:
/// coming back to the page keeps the remote where it was.
struct FirstFocus: ViewModifier {
    @FocusState private var focused: Bool
    @State private var done = false

    func body(content: Content) -> some View {
        content
            .focused($focused)
            .task {
                guard !done else { return }
                done = true
                try? await Task.sleep(nanoseconds: 150_000_000)
                focused = true
            }
    }
}

extension View {
    func firstFocus() -> some View { modifier(FirstFocus()) }
}

/// a section's title (Library)
struct SectionTitle: View {
    let text: String
    init(_ text: String) { self.text = text }

    var body: some View {
        Text(text).font(Fonts.titleMedium).foregroundStyle(Theme.text)
            .padding(.horizontal, 32).padding(.top, 24).padding(.bottom, 8)
    }
}

/// Things laid out left to right, wrapping to the next line (Compose's FlowRow).
struct FlowLayout: Layout {
    var spacing: CGFloat = 16
    var center = true

    func sizeThatFits(proposal: ProposedViewSize, subviews: Subviews, cache: inout ()) -> CGSize {
        let rows = arrange(proposal.width ?? .infinity, subviews)
        let h = rows.map(\.height).reduce(0, +) + spacing * CGFloat(max(0, rows.count - 1))
        let w = rows.map(\.width).max() ?? 0
        return CGSize(width: proposal.width ?? w, height: h)
    }

    func placeSubviews(in bounds: CGRect, proposal: ProposedViewSize, subviews: Subviews, cache: inout ()) {
        var y = bounds.minY
        for row in arrange(bounds.width, subviews) {
            var x = bounds.minX + (center ? (bounds.width - row.width) / 2 : 0)
            for i in row.items {
                let s = subviews[i].sizeThatFits(.unspecified)
                subviews[i].place(at: CGPoint(x: x, y: y + (row.height - s.height) / 2), proposal: ProposedViewSize(s))
                x += s.width + spacing
            }
            y += row.height + spacing
        }
    }

    private struct Line { var items: [Int] = []; var width: CGFloat = 0; var height: CGFloat = 0 }

    private func arrange(_ width: CGFloat, _ subviews: Subviews) -> [Line] {
        var rows: [Line] = [Line()]
        for i in subviews.indices {
            let s = subviews[i].sizeThatFits(.unspecified)
            let last = rows.count - 1
            let add = rows[last].items.isEmpty ? s.width : rows[last].width + spacing + s.width
            if add > width && !rows[last].items.isEmpty {
                rows.append(Line(items: [i], width: s.width, height: s.height))
            } else {
                rows[last].items.append(i)
                rows[last].width = add
                rows[last].height = max(rows[last].height, s.height)
            }
        }
        return rows
    }
}

/// what a screen shows while it loads, or when loading failed or found nothing
struct Status: View {
    let error: String?
    var retry: (() -> Void)? = nil

    var body: some View {
        VStack(spacing: 32) {
            if let error {
                Text(error).font(Fonts.bodyLarge).multilineTextAlignment(.center).foregroundStyle(Theme.sub).frame(maxWidth: 1000)
                if let retry { TextButton("Try again", action: retry) }
            } else {
                ProgressView().scaleEffect(1.4)
            }
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .padding(64)
    }
}

/// a page of items with "more" handled one at a time
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
            if !(error is CancellationError) { self.error = error.localizedDescription }
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
