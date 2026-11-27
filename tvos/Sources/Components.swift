import ImageIO
import SwiftUI
import UIKit

/// The look of YouTube on a TV: near-black, white text, grey for the rest, and whatever the remote
/// is on turns white (a white frame around a picture, a white pill behind a button). Red is for the
/// progress bars. The Apple TV screen is 1920×1080 points.
enum Theme {
    static let bg = Color(hex: 0x0F0F0F)
    static let raised = Color(hex: 0x1F1F1F)        // panels over the video, the expanded guide
    static let text = Color(hex: 0xF1F1F1)
    static let sub = Color(hex: 0xAAAAAA)
    static let chip = Color(hex: 0x272727)
    static let outline = Color(hex: 0x3A3A3A)
    static let container = Color(hex: 0x212121)
    static let pill = Color(hex: 0x3F3F3F)
    static let onPill = Color.white
    static let focus = Color.white
    static let onFocus = Color(hex: 0x0F0F0F)
    static let teal = Color(hex: 0x2DD4BF)          // the TubeHaus house
    static let red = Color(hex: 0xFF0033)
    static let blue = Color(hex: 0x3EA6FF)
    static let link = Color(hex: 0x3EA6FF)
    static let error = Color(hex: 0xFF4E45)
    static let placeholder = Color.white.opacity(0.08)
    /// kept for older screens: what the remote is on
    static let glow = Color.white
}

extension Color {
    init(hex: UInt32) {
        self.init(red: Double(hex >> 16 & 0xFF) / 255, green: Double(hex >> 8 & 0xFF) / 255, blue: Double(hex & 0xFF) / 255)
    }
}

enum Fonts {
    static let hero = Font.system(size: 64, weight: .bold)
    static let titleLarge = Font.system(size: 44, weight: .bold)
    static let titleMedium = Font.system(size: 34, weight: .semibold)
    static let titleSmall = Font.system(size: 28, weight: .medium)
    static let bodyLarge = Font.system(size: 32)
    static let bodyMedium = Font.system(size: 28)
    static let bodySmall = Font.system(size: 24)
    static let caption = Font.system(size: 22)
    static let labelLarge = Font.system(size: 28, weight: .medium)
    static let shelf = Font.system(size: 34, weight: .bold)
}

/// how far everything sits from the left edge: the guide's width when folded, and a gap
enum Metrics {
    static let guide: CGFloat = 132
    static let side: CGFloat = 64
    /// a card in a shelf: four and a bit across
    static let card: CGFloat = 400
}

// MARK: focus

/// whether the button this is inside is the one the remote is on (set by the styles below)
private struct LitKey: EnvironmentKey { static let defaultValue = false }

extension EnvironmentValues {
    var lit: Bool {
        get { self[LitKey.self] }
        set { self[LitKey.self] = newValue }
    }
}

/// A card the remote is on grows and gets a white frame.
struct CardFocus: ButtonStyle {
    var radius: CGFloat = 20
    var scale: CGFloat = 1.06

    func makeBody(configuration: Configuration) -> some View { Focused(configuration: configuration, radius: radius, scale: scale) }

    private struct Focused: View {
        let configuration: ButtonStyleConfiguration
        let radius: CGFloat
        let scale: CGFloat
        @Environment(\.isFocused) private var focused

        var body: some View {
            configuration.label
                .environment(\.lit, focused)
                .contentShape(RoundedRectangle(cornerRadius: radius))
                .overlay {
                    RoundedRectangle(cornerRadius: radius).strokeBorder(Theme.focus, lineWidth: focused ? 6 : 0)
                }
                .shadow(color: .black.opacity(focused ? 0.6 : 0), radius: 24, y: 12)
                .scaleEffect(configuration.isPressed ? 1 : focused ? scale : 1)
                .animation(.easeOut(duration: 0.18), value: focused)
        }
    }
}

/// A button, chip or menu line the remote is on turns white with dark writing. `fill` is its
/// colour when not focused.
struct Glow<S: Shape>: ButtonStyle {
    var shape: S
    var fill: Color = .clear
    var scale: CGFloat = 1.05

    func makeBody(configuration: Configuration) -> some View { Focused(configuration: configuration, shape: shape, fill: fill, scale: scale) }

    private struct Focused: View {
        let configuration: ButtonStyleConfiguration
        let shape: S
        let fill: Color
        let scale: CGFloat
        @Environment(\.isFocused) private var focused

        var body: some View {
            configuration.label
                .environment(\.lit, focused)
                .foregroundStyle(focused ? Theme.onFocus : Theme.text)
                .background { shape.fill(focused ? Theme.focus : fill) }
                .contentShape(shape)
                .shadow(color: .black.opacity(focused ? 0.45 : 0), radius: 16, y: 8)
                .scaleEffect(configuration.isPressed ? 0.97 : focused ? scale : 1)
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

/// grey writing that turns dark with the white behind it when focused
struct Dim: ViewModifier {
    @Environment(\.lit) private var lit
    func body(content: Content) -> some View { content.foregroundStyle(lit ? Theme.onFocus.opacity(0.75) : Theme.sub) }
}

extension View {
    func dim() -> some View { modifier(Dim()) }
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

/// a word (and an icon) that turns into a white pill when the remote is on it
struct TextButton: View {
    let title: String
    var icon: String?
    var color: Color
    let action: () -> Void

    init(_ title: String, icon: String? = nil, color: Color = Theme.text, action: @escaping () -> Void) {
        self.title = title; self.icon = icon; self.color = color; self.action = action
    }

    var body: some View {
        Button(action: action) { Face(title: title, icon: icon, color: color) }
            .buttonStyle(.glow)
    }

    private struct Face: View {
        let title: String
        let icon: String?
        let color: Color
        @Environment(\.lit) private var lit

        var body: some View {
            HStack(spacing: 12) {
                if let icon { Image(systemName: icon).font(.system(size: 28, weight: .medium)) }
                Text(title).font(Fonts.labelLarge).lineLimit(1)
            }
            .foregroundStyle(lit ? Theme.onFocus : color)
            .padding(.horizontal, 32).frame(height: 76)
        }
    }
}

/// the main button of a page: grey, white when the remote is on it
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
            HStack(spacing: 14) {
                if let icon { Image(systemName: icon).font(.system(size: 28, weight: .semibold)) }
                Text(title).font(Fonts.labelLarge).lineLimit(1)
            }
            .padding(.horizontal, 44).frame(height: 80)
            .opacity(enabled ? 1 : 0.4)
        }
        .buttonStyle(Glow(shape: Capsule(), fill: Theme.chip))
        .disabled(!enabled)
    }
}

/// a round button with one icon
struct IconButton: View {
    let icon: String
    var size: CGFloat = 48
    var fill: Color = .clear
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            Image(systemName: icon).font(.system(size: size * 0.7, weight: .medium))
                .frame(width: size * 1.75, height: size * 1.75)
        }
        .buttonStyle(Glow(shape: Circle(), fill: fill, scale: 1.1))
    }
}

/// a chip: grey, white with dark writing when picked or when the remote is on it
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
        Button(action: action) { Face(title: title, icon: icon, selected: selected) }
            .buttonStyle(Glow(shape: RoundedRectangle(cornerRadius: 14), fill: selected ? Theme.text : Theme.chip))
    }

    private struct Face: View {
        let title: String
        let icon: String?
        let selected: Bool
        @Environment(\.lit) private var lit

        var body: some View {
            HStack(spacing: 12) {
                if let icon { Image(systemName: icon).font(.system(size: 26)) }
                Text(title).font(Fonts.labelLarge).lineLimit(1)
            }
            .foregroundStyle(lit || selected ? Theme.onFocus : Theme.text)
            .padding(.horizontal, 28).frame(height: 64)
        }
    }
}

/// Subscribe (white) / Subscribed (grey)
struct SubscribeButton: View {
    let channel: Channel
    var compact = false
    @State private var library = Library.shared

    var body: some View {
        let on = library.isSubscribed(channel.id)
        Button { library.toggleSubscription(channel) } label: {
            Face(on: on, compact: compact)
        }
        .buttonStyle(Glow(shape: Capsule(), fill: on ? Theme.chip : Theme.text))
    }

    private struct Face: View {
        let on: Bool
        let compact: Bool
        @Environment(\.lit) private var lit

        var body: some View {
            HStack(spacing: 10) {
                if on { Image(systemName: "bell").font(.system(size: 24, weight: .semibold)) }
                Text(on ? "Subscribed" : "Subscribe").font(Fonts.labelLarge)
            }
            .foregroundStyle(lit ? Theme.onFocus : on ? Theme.text : Theme.onFocus)
            .padding(.horizontal, compact ? 28 : 40).frame(height: compact ? 64 : 72)
        }
    }
}

// MARK: cards

/// a picture with the length (or LIVE) in the corner and how much was watched along the bottom
struct Thumb: View {
    let urls: [URL]
    var badge: String = ""
    var progress: Double? = nil
    var radius: CGFloat = 16

    var body: some View {
        Color.clear
            .aspectRatio(16 / 9, contentMode: .fit)
            .overlay { Picture(urls) }
            .overlay(alignment: .bottomTrailing) {
                if !badge.isEmpty {
                    Text(badge)
                        .font(.system(size: 22, weight: .semibold).monospacedDigit())
                        .padding(.horizontal, 10).padding(.vertical, 3)
                        .background(badge == "LIVE" ? Theme.red : Color.black.opacity(0.82), in: RoundedRectangle(cornerRadius: 6))
                        .foregroundStyle(.white)
                        .padding(10)
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

/// what holding OK on a video offers
struct VideoMenu: ViewModifier {
    let video: Video
    var history = false
    @State private var library = Library.shared

    func body(content: Content) -> some View {
        content.contextMenu {
            Button("Play", systemImage: "play.fill") { Nav.shared.open(video) }
            Button(library.isLater(video.id) ? "Remove from Watch later" : "Save to Watch later", systemImage: "clock") {
                library.toggleLater(video)
            }
            Button("Play next", systemImage: "text.line.first.and.arrowtriangle.forward") {
                PlayerModel.shared.queue.insert(video, at: 0)
            }
            Button("Add to queue", systemImage: "text.badge.plus") { PlayerModel.shared.queue.append(video) }
            if !video.channelId.isEmpty {
                Button("Go to channel", systemImage: "person.crop.square") { Nav.shared.push(.channel(video.channelId)) }
            }
            if history {
                Button("Remove from history", systemImage: "trash") { library.forget(video.id) }
            }
            Button("Not interested", systemImage: "hand.thumbsdown") { library.notInterested(video) }
            if !video.channelId.isEmpty {
                Button("Don't recommend channel", systemImage: "nosign") { library.hideChannel(video) }
            }
        }
    }
}

extension View {
    func videoMenu(_ v: Video, history: Bool = false) -> some View { modifier(VideoMenu(video: v, history: history)) }
}

/// The card of every shelf and grid: the picture (the white frame goes round it), then the
/// title (two lines) and "Channel · views · when".
struct VideoTile: View {
    let video: Video
    var width: CGFloat? = Metrics.card
    var history = false
    var showAuthor = true
    var action: (() -> Void)? = nil
    @State private var library = Library.shared

    var body: some View {
        Button { if let action { action() } else { Nav.shared.open(video) } } label: {
            Face(video: video, progress: library.progress(video.id), showAuthor: showAuthor)
                .frame(width: width)
        }
        .buttonStyle(TileStyle())
        .videoMenu(video, history: history || library.progress(video.id) != nil)
    }

    struct Face: View {
        let video: Video
        let progress: Double?
        var showAuthor = true
        @Environment(\.lit) private var lit

        var body: some View {
            VStack(alignment: .leading, spacing: 14) {
                Thumb(urls: video.cardPictures, badge: video.badge, progress: progress, radius: 16)
                    .overlay { RoundedRectangle(cornerRadius: 16).strokeBorder(Theme.focus, lineWidth: lit ? 6 : 0) }
                    .shadow(color: .black.opacity(lit ? 0.7 : 0), radius: 22, y: 12)
                    .scaleEffect(lit ? 1.06 : 1, anchor: .bottom)
                VStack(alignment: .leading, spacing: 6) {
                    Text(video.title.isEmpty ? " " : video.title)
                        .font(.system(size: 26, weight: .semibold)).foregroundStyle(Theme.text)
                        .lineLimit(2).multilineTextAlignment(.leading)
                        .frame(maxWidth: .infinity, minHeight: 66, alignment: .topLeading)
                    Text(showAuthor ? video.byline : video.byline(withAuthor: false))
                        .font(Fonts.caption).foregroundStyle(Theme.sub).lineLimit(1)
                }
                .offset(y: lit ? 10 : 0)
            }
            .animation(.easeOut(duration: 0.18), value: lit)
        }
    }
}

/// a tile's focus: nothing of its own, it tells the face (which draws the frame round the picture)
struct TileStyle: ButtonStyle {
    func makeBody(configuration: Configuration) -> some View { Lit(configuration: configuration) }

    private struct Lit: View {
        let configuration: ButtonStyleConfiguration
        @Environment(\.isFocused) private var focused

        var body: some View {
            configuration.label
                .environment(\.lit, focused)
                .scaleEffect(configuration.isPressed ? 0.98 : 1)
        }
    }
}

/// a Short in a shelf: tall
struct ShortTile: View {
    let video: Video

    var body: some View {
        Button { Nav.shared.go(.shorts, start: video) } label: { Face(video: video) }
            .buttonStyle(TileStyle())
    }

    private struct Face: View {
        let video: Video
        @Environment(\.lit) private var lit

        var body: some View {
            VStack(alignment: .leading, spacing: 12) {
                Picture([URL(string: "https://i.ytimg.com/vi/\(video.id)/oar2.jpg"), video.thumb].compactMap { $0 })
                    .frame(width: 260, height: 462)
                    .clipShape(RoundedRectangle(cornerRadius: 16))
                    .overlay { RoundedRectangle(cornerRadius: 16).strokeBorder(Theme.focus, lineWidth: lit ? 6 : 0) }
                    .scaleEffect(lit ? 1.06 : 1, anchor: .bottom)
                Text(video.title).font(.system(size: 24, weight: .semibold)).lineLimit(2)
                    .frame(width: 260, alignment: .leading)
                    .offset(y: lit ? 12 : 0)
            }
            .animation(.easeOut(duration: 0.18), value: lit)
        }
    }
}

/// a channel as a round picture with its name under it
struct ChannelTile: View {
    let channel: Channel
    var size: CGFloat = 200

    var body: some View {
        Button { Nav.shared.push(.channel(channel.id)) } label: { Face(channel: channel, size: size) }
            .buttonStyle(TileStyle())
    }

    private struct Face: View {
        let channel: Channel
        let size: CGFloat
        @Environment(\.lit) private var lit

        var body: some View {
            VStack(spacing: 18) {
                Avatar(url: channel.avatarURL, name: channel.name, size: size)
                    .overlay { Circle().strokeBorder(Theme.focus, lineWidth: lit ? 6 : 0) }
                    .scaleEffect(lit ? 1.08 : 1)
                Text(channel.name).font(.system(size: 26, weight: .semibold)).lineLimit(1)
                if !channel.subsText.isEmpty { Text(channel.subsText).font(Fonts.caption).foregroundStyle(Theme.sub).lineLimit(1) }
            }
            .frame(width: max(size, 240))
            .animation(.easeOut(duration: 0.18), value: lit)
        }
    }
}

/// a playlist's picture, with the list mark and how many videos it has
struct PlaylistThumb: View {
    let playlist: Playlist
    var radius: CGFloat = 16

    var body: some View {
        Thumb(urls: playlist.thumbURL.map { [$0] } ?? [], radius: radius)
            .overlay(alignment: .trailing) {
                VStack(spacing: 8) {
                    Image(systemName: "list.bullet").font(.system(size: 34, weight: .semibold))
                    Text(playlist.countText.replacingOccurrences(of: " videos", with: "")).font(.system(size: 24, weight: .semibold))
                }
                .foregroundStyle(.white)
                .frame(maxHeight: .infinity)
                .frame(width: 140)
                .background(Color.black.opacity(0.7))
            }
            .clipShape(RoundedRectangle(cornerRadius: radius))
    }
}

struct PlaylistTile: View {
    let playlist: Playlist
    var width: CGFloat? = Metrics.card

    var body: some View {
        Button { Nav.shared.push(.playlist(playlist.id)) } label: { Face(playlist: playlist).frame(width: width) }
            .buttonStyle(TileStyle())
    }

    private struct Face: View {
        let playlist: Playlist
        @Environment(\.lit) private var lit

        var body: some View {
            VStack(alignment: .leading, spacing: 14) {
                PlaylistThumb(playlist: playlist)
                    .overlay { RoundedRectangle(cornerRadius: 16).strokeBorder(Theme.focus, lineWidth: lit ? 6 : 0) }
                    .scaleEffect(lit ? 1.06 : 1, anchor: .bottom)
                VStack(alignment: .leading, spacing: 6) {
                    Text(playlist.title).font(.system(size: 26, weight: .semibold)).lineLimit(2)
                        .frame(maxWidth: .infinity, minHeight: 66, alignment: .topLeading)
                    Text([playlist.author, "Playlist"].filter { !$0.isEmpty }.joined(separator: " · "))
                        .font(Fonts.caption).foregroundStyle(Theme.sub).lineLimit(1)
                }
                .offset(y: lit ? 10 : 0)
            }
            .animation(.easeOut(duration: 0.18), value: lit)
        }
    }
}

struct ItemTile: View {
    let item: Item
    var width: CGFloat? = Metrics.card

    var body: some View {
        switch item {
        case .video(let v): VideoTile(video: v, width: width)
        case .channel(let c): ChannelTile(channel: c, size: 200).frame(width: width)
        case .playlist(let p): PlaylistTile(playlist: p, width: width)
        }
    }
}

/// kept names: the cards of the older screens
typealias VideoCard = VideoTile

struct SmallCard: View {
    let video: Video
    var body: some View { VideoTile(video: video, width: 340, history: true) }
}

/// a video as a line of a list: the picture on the left, the words on the right
struct VideoRow: View {
    let video: Video
    var thumb: CGFloat = 400
    var history = false
    var dots = true
    var index: Int? = nil
    var action: (() -> Void)? = nil
    @State private var library = Library.shared

    var body: some View {
        Button { if let action { action() } else { Nav.shared.open(video) } } label: {
            HStack(alignment: .center, spacing: 32) {
                if let index {
                    Text("\(index)").font(Fonts.bodyMedium.monospacedDigit()).dim().frame(width: 56)
                }
                Thumb(urls: video.cardPictures, badge: video.badge, progress: library.progress(video.id), radius: 14)
                    .frame(width: thumb)
                VStack(alignment: .leading, spacing: 8) {
                    Text(video.title).font(.system(size: 28, weight: .semibold)).lineLimit(2).multilineTextAlignment(.leading)
                    Text(video.author).font(Fonts.bodySmall).dim().lineLimit(1)
                    if !rest.isEmpty { Text(rest).font(Fonts.bodySmall).dim().lineLimit(1) }
                }
                .frame(maxWidth: .infinity, alignment: .leading)
            }
            .padding(.horizontal, 20).padding(.vertical, 14)
            .frame(maxWidth: .infinity, alignment: .leading)
        }
        .buttonStyle(.glow(radius: 20))
        .videoMenu(video, history: history)
    }

    private var rest: String {
        [video.viewsText, video.publishedText.isEmpty ? ago(video.publishedAt) : video.publishedText]
            .filter { !$0.isEmpty }.joined(separator: " · ")
    }
}

struct ChannelRow: View {
    let channel: Channel

    var body: some View {
        HStack(spacing: 32) {
            Button { Nav.shared.push(.channel(channel.id)) } label: {
                HStack(spacing: 32) {
                    Avatar(url: channel.avatarURL, name: channel.name, size: 160).frame(width: 400)
                    VStack(alignment: .leading, spacing: 8) {
                        Text(channel.name).font(Fonts.titleMedium).lineLimit(1)
                        if !channel.subsText.isEmpty { Text(channel.subsText).font(Fonts.bodySmall).dim().lineLimit(1) }
                    }
                    .frame(maxWidth: .infinity, alignment: .leading)
                }
                .padding(.horizontal, 20).padding(.vertical, 14)
            }
            .buttonStyle(.glow(radius: 20))
            SubscribeButton(channel: channel).padding(.trailing, 20)
        }
    }
}

struct PlaylistRow: View {
    let playlist: Playlist

    var body: some View {
        Button { Nav.shared.push(.playlist(playlist.id)) } label: {
            HStack(alignment: .center, spacing: 32) {
                PlaylistThumb(playlist: playlist, radius: 14).frame(width: 400)
                VStack(alignment: .leading, spacing: 8) {
                    Text(playlist.title).font(.system(size: 28, weight: .semibold)).lineLimit(2)
                    Text([playlist.author, "Playlist"].filter { !$0.isEmpty }.joined(separator: " · ")).font(Fonts.bodySmall).dim().lineLimit(1)
                }
                .frame(maxWidth: .infinity, alignment: .leading)
            }
            .padding(.horizontal, 20).padding(.vertical, 14)
        }
        .buttonStyle(.glow(radius: 20))
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

// MARK: shelves and grids

/// A row of cards with its title above, scrolling sideways. `more` is asked for near its end.
struct Shelf<Content: View>: View {
    let title: String
    var subtitle: String = ""
    var icon: String? = nil
    var spacing: CGFloat = 40
    let content: Content

    init(_ title: String, subtitle: String = "", icon: String? = nil, spacing: CGFloat = 40, @ViewBuilder content: () -> Content) {
        self.title = title; self.subtitle = subtitle; self.icon = icon; self.spacing = spacing; self.content = content()
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            if !title.isEmpty {
                HStack(alignment: .firstTextBaseline, spacing: 16) {
                    if let icon { Image(systemName: icon).font(.system(size: 30, weight: .semibold)).foregroundStyle(Theme.sub) }
                    Text(title).font(Fonts.shelf).foregroundStyle(Theme.text)
                    if !subtitle.isEmpty { Text(subtitle).font(Fonts.bodySmall).foregroundStyle(Theme.sub) }
                }
                .padding(.leading, Metrics.side)
            }
            ScrollView(.horizontal) {
                LazyHStack(alignment: .top, spacing: spacing) { content }
                    .padding(.horizontal, Metrics.side).padding(.top, 28).padding(.bottom, 36)
            }
            .scrollClipDisabled()
        }
        .focusSection()
    }
}

/// a shelf of videos
struct VideoShelf: View {
    let title: String
    var subtitle: String = ""
    var icon: String? = nil
    let videos: [Video]
    var history = false
    var more: (() async -> Void)? = nil

    var body: some View {
        if !videos.isEmpty {
            Shelf(title, subtitle: subtitle, icon: icon) {
                ForEach(videos) { v in
                    VideoTile(video: v, history: history)
                        .onAppear {
                            if let more, videos.suffix(3).contains(where: { $0.id == v.id }) { Task { await more() } }
                        }
                }
            }
        }
    }
}

/// Cards four across, asking for more as the end comes into view. `header` sits above them,
/// inside the same scrolling page.
struct ItemGrid<Header: View>: View {
    let items: [Item]
    let more: (() async -> Void)?
    let focusFirst: Bool
    var columns: Int = 4
    let header: Header
    @FocusState private var focused: String?

    init(items: [Item], more: (() async -> Void)? = nil, focusFirst: Bool = false, columns: Int = 4, @ViewBuilder header: () -> Header) {
        self.items = items; self.more = more; self.focusFirst = focusFirst; self.columns = columns; self.header = header()
    }

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 0) {
                header
                LazyVGrid(columns: Array(repeating: GridItem(.flexible(), spacing: 40, alignment: .top), count: columns),
                          alignment: .leading, spacing: 56) {
                    ForEach(items) { item in
                        ItemTile(item: item, width: nil)
                            .focused($focused, equals: item.id)
                            .onAppear { nearEnd(item) }
                    }
                }
                .padding(.horizontal, Metrics.side).padding(.top, 28).padding(.bottom, 80)
                .focusSection()
            }
        }
        .scrollClipDisabled()
        .onAppear { if focusFirst, focused == nil { focused = items.first?.id } }
        .onChange(of: items.first?.id) { if focusFirst, focused == nil { focused = items.first?.id } }
    }

    private func nearEnd(_ item: Item) {
        guard let more, items.suffix(columns * 2).contains(where: { $0.id == item.id }) else { return }
        Task { await more() }
    }
}

extension ItemGrid where Header == EmptyView {
    init(items: [Item], more: (() async -> Void)? = nil, focusFirst: Bool = false, columns: Int = 4) {
        self.init(items: items, more: more, focusFirst: focusFirst, columns: columns) { EmptyView() }
    }
}

/// A list of rows, one under another, asking for more near the end.
struct ItemList<Header: View>: View {
    let items: [Item]
    let more: (() async -> Void)?
    let focusFirst: Bool
    var numbered = false
    let header: Header
    @FocusState private var focused: String?

    init(items: [Item], more: (() async -> Void)? = nil, focusFirst: Bool = false, numbered: Bool = false, @ViewBuilder header: () -> Header) {
        self.items = items; self.more = more; self.focusFirst = focusFirst; self.numbered = numbered; self.header = header()
    }

    var body: some View {
        ScrollView {
            LazyVStack(alignment: .leading, spacing: 6) {
                header
                ForEach(Array(items.enumerated()), id: \.element.id) { i, item in
                    Group {
                        if numbered, let v = item.video { VideoRow(video: v, index: i + 1) } else { ItemRow(item: item) }
                    }
                    .focused($focused, equals: item.id)
                    .onAppear {
                        if let more, items.suffix(4).contains(where: { $0.id == item.id }) { Task { await more() } }
                    }
                }
            }
            .padding(.horizontal, Metrics.side - 20).padding(.vertical, 24)
        }
        .onAppear { if focusFirst, focused == nil { focused = items.first?.id } }
        .onChange(of: items.first?.id) { if focusFirst, focused == nil { focused = items.first?.id } }
    }
}

/// Where the remote starts when a page opens. Only the first time: coming back to the page keeps
/// the remote where it was.
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
    @ViewBuilder func firstFocus(_ on: Bool) -> some View { if on { modifier(FirstFocus()) } else { self } }
}

/// a section's title
struct SectionTitle: View {
    let text: String
    init(_ text: String) { self.text = text }

    var body: some View {
        Text(text).font(Fonts.shelf).foregroundStyle(Theme.text)
            .padding(.horizontal, Metrics.side).padding(.top, 32).padding(.bottom, 8)
    }
}

/// a page's big title, with what's under it
struct PageTitle: View {
    let title: String
    var sub: String = ""

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            Text(title).font(Fonts.titleLarge).lineLimit(2)
            if !sub.isEmpty { Text(sub).font(Fonts.bodySmall).foregroundStyle(Theme.sub).lineLimit(2) }
        }
        .padding(.horizontal, Metrics.side).padding(.top, 48).padding(.bottom, 8)
        .frame(maxWidth: .infinity, alignment: .leading)
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
                Image(systemName: "sparkles.tv").font(.system(size: 72)).foregroundStyle(Theme.sub)
                Text(error).font(Fonts.bodyLarge).multilineTextAlignment(.center).foregroundStyle(Theme.sub).frame(maxWidth: 1000)
                if let retry { FilledButton("Try again", icon: "arrow.clockwise", action: retry) }
            } else {
                ProgressView().scaleEffect(1.6)
            }
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .padding(64)
    }
}

/// grey boxes where cards will be, while a page loads
struct ShelfPlaceholder: View {
    var count = 5

    var body: some View {
        VStack(alignment: .leading, spacing: 28) {
            RoundedRectangle(cornerRadius: 8).fill(Theme.placeholder).frame(width: 320, height: 34)
            HStack(spacing: 40) {
                ForEach(0..<count, id: \.self) { _ in
                    VStack(alignment: .leading, spacing: 14) {
                        RoundedRectangle(cornerRadius: 16).fill(Theme.placeholder).frame(width: Metrics.card, height: Metrics.card * 9 / 16)
                        RoundedRectangle(cornerRadius: 6).fill(Theme.placeholder).frame(width: Metrics.card * 0.85, height: 26)
                        RoundedRectangle(cornerRadius: 6).fill(Theme.placeholder).frame(width: Metrics.card * 0.5, height: 22)
                    }
                }
            }
        }
        .padding(.leading, Metrics.side).padding(.vertical, 24)
        .opacity(0.8)
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

/// Material's switch, dark theme: white track when on, grey outline when off
struct Switch: View {
    let on: Bool
    @Environment(\.lit) private var lit

    var body: some View {
        ZStack(alignment: on ? .trailing : .leading) {
            Capsule().fill(on ? (lit ? Theme.onFocus : Theme.text) : Theme.chip)
            Capsule().strokeBorder(on ? Color.clear : Theme.sub, lineWidth: 4)
            Circle().fill(on ? (lit ? Theme.focus : Theme.bg) : Theme.sub)
                .frame(width: on ? 44 : 30, height: on ? 44 : 30)
                .padding(.horizontal, on ? 8 : 14)
        }
        .frame(width: 100, height: 60)
        .animation(.easeOut(duration: 0.15), value: on)
    }
}
