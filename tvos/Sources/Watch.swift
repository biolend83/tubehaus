import AVKit
import SwiftUI

/// The one player of the app. The video plays on its page (WatchView), and OK opens the same
/// player full screen in Apple's own player view (so the Siri Remote, AirPlay and the audio track
/// menu work as in any Apple TV app); Back returns to the page and it goes on playing. Subtitles
/// are YouTube's own tracks, drawn on top (Captions.swift). Where it stopped is saved every few
/// seconds; when a video ends, the next one in the queue or in Up next plays (Settings).
@MainActor
@Observable
final class PlayerModel {
    static let shared = PlayerModel()

    let player = AVPlayer()
    private(set) var video: Video?
    private(set) var stream: StreamInfo?
    private(set) var details: Details?
    private(set) var related = Feed()
    private(set) var error: String?
    private(set) var loading = false
    private(set) var paused = true
    private(set) var ended = false
    var fullscreen = false
    /// what plays after this video, before Up next ("Play all")
    var queue: [Video] = []

    private(set) var choices: [Subtitles.Choice] = []
    private(set) var choice: Subtitles.Choice?

    @ObservationIgnored private var cues: [Subtitles.Cue] = []
    private let layers = NSHashTable<CaptionLayer>.weakObjects()
    @ObservationIgnored private var tick: Any?
    @ObservationIgnored private var status: NSKeyValueObservation?
    @ObservationIgnored private var endObserver: NSObjectProtocol?
    @ObservationIgnored private var task: Task<Void, Never>?
    @ObservationIgnored private var captionTask: Task<Void, Never>?
    @ObservationIgnored private var last: Double = 0
    @ObservationIgnored private var saved: Double = 0
    @ObservationIgnored private var keywords: [String] = []
    @ObservationIgnored weak var screen: AVPlayerViewController?
    @ObservationIgnored private var rate: Float = 1

    private init() {
        tick = player.addPeriodicTimeObserver(forInterval: CMTime(value: 1, timescale: 10), queue: .main) { [weak self] t in
            MainActor.assumeIsolated { self?.ticked(t.seconds) }
        }
        status = player.observe(\.timeControlStatus, options: [.initial, .new]) { [weak self] p, _ in
            let paused = p.timeControlStatus == .paused
            Task { @MainActor in if self?.paused != paused { self?.paused = paused } }
        }
    }

    /// the page of `v` opened (or came back into view)
    func open(_ v: Video) {
        if video?.id == v.id {
            if !ended && error == nil && player.currentItem != nil && !fullscreen { player.play() }
            return
        }
        leave()
        video = v
        stream = nil
        details = nil
        related = Feed()
        error = nil
        loading = true
        ended = false
        choices = []
        choice = nil
        setCues([])
        task?.cancel()
        task = Task { await load(v) }
    }

    func retry() {
        guard let v = video else { return }
        video = nil
        open(v)
    }

    func toggle() {
        guard player.currentItem != nil else { return }
        if ended {
            ended = false
            player.seek(to: .zero)
            player.play()
        } else if player.timeControlStatus == .paused {
            player.play()
        } else {
            player.pause()
        }
    }

    /// the pages open changed: no video page left, the player stops; one under another page, it pauses
    func follow(_ path: [Route]) {
        if !path.contains(where: { $0.isVideo }) {
            close()
        } else if !(path.last?.isVideo ?? false) {
            player.pause()
        }
    }

    func close() {
        leave()
        task?.cancel()
        video = nil
        stream = nil
        details = nil
        related = Feed()
        error = nil
        fullscreen = false
        queue = []
    }

    /// back from full screen: the page goes on playing
    func leftFullscreen() {
        if video != nil && !ended && error == nil { player.play() }
    }

    private func load(_ v: Video) async {
        let id = v.id
        async let d = try? YT.details(id)
        do {
            let s = try await YT.stream(id)
            guard video?.id == id else { return }
            stream = s
            // a video opened by id alone (or from an old list) gets its name and length from the streams
            if video?.title.isEmpty == true { video?.title = s.title }
            if video?.author.isEmpty == true { video?.author = s.author }
            if video?.channelId.isEmpty == true { video?.channelId = s.channelId }
            if video?.lengthSec == 0 { video?.lengthSec = s.lengthSec }
            if video?.views == 0 { video?.views = s.views }
            if s.live { video?.live = true }
            if let url = s.hls { start(url, s) } else { error = "This video has no stream the Apple TV can play" }
        } catch {
            guard video?.id == id else { return }
            if !(error is CancellationError) { self.error = error.localizedDescription }
        }
        loading = false
        let det = await d
        guard video?.id == id, let det else { return }
        details = det
        // the streams can fail (a bot check) while the page itself still names the video
        if video?.title.isEmpty == true { video?.title = det.title }
        if video?.author.isEmpty == true { video?.author = det.author }
        if video?.channelId.isEmpty == true { video?.channelId = det.channelId }
        related.items = det.related.filter { Feed.keep($0) && $0.id != "v" + id }
        related.next = det.relatedNext
    }

    private func start(_ url: URL, _ s: StreamInfo) {
        guard let v = video else { return }
        let item = AVPlayerItem(url: url)
        item.externalMetadata = [meta(.commonIdentifierTitle, v.title), meta(.iTunesMetadataTrackSubTitle, v.author)]
        player.replaceCurrentItem(with: item)
        let at = Library.shared.resumeAt(v.id, length: v.lengthSec)
        if at > 0 { player.seek(to: CMTime(seconds: at, preferredTimescale: 600)) }
        last = at
        saved = at
        keywords = s.keywords
        player.defaultRate = rate
        if !UserDefaults.standard.bool(forKey: "noAutoplay") { player.play() }
        endObserver = NotificationCenter.default.addObserver(forName: .AVPlayerItemDidPlayToEndTime, object: item, queue: .main) { [weak self] _ in
            MainActor.assumeIsolated { self?.didEnd() }
        }
        choices = Subtitles.choices(s.captions)
        choice = Subtitles.pick(choices, tracks: s.captions, lang: Settings.captions)
        loadCaptions()
        menu()
    }

    private func ticked(_ t: Double) {
        guard t.isFinite, t > 0, let v = video else { return }
        for l in layers.allObjects { l.update(t) }
        last = t
        if abs(t - saved) >= 5 {
            saved = t
            Library.shared.watched(v, at: t)
        }
    }

    private func didEnd() {
        guard let v = video else { return }
        if let d = player.currentItem?.duration.seconds, d.isFinite { last = d }
        Library.shared.watched(v, at: last)
        if let n = next() {
            if queue.first?.id == n.id { queue.removeFirst() }
            Nav.shared.replace(with: n)     // its page opens it (WatchView)
        } else {
            ended = true
            fullscreen = false
        }
    }

    /// what plays when this one ends: the queue, then (with Autoplay) the first of Up next not seen yet
    func next() -> Video? {
        if let q = queue.first { return q }
        guard Settings.autoplayNext else { return nil }
        return related.items.compactMap(\.video).first { !$0.live && !Library.shared.finished($0.id) }
    }

    /// the video before (if any) is done with: remember it and learn from it
    private func leave() {
        guard let v = video else { return }
        let t = player.currentTime().seconds
        if t.isFinite && t > 0 { last = t }
        if last > 0 {
            Library.shared.watched(v, at: last)
            Library.shared.learn(v, keywords: keywords, watched: last, length: Double(v.lengthSec))
            if last >= 30 { Library.shared.rememberRelated(v.id, related.items.compactMap(\.video)) }
        }
        if let endObserver { NotificationCenter.default.removeObserver(endObserver) }
        endObserver = nil
        captionTask?.cancel()
        player.pause()
        player.replaceCurrentItem(with: nil)
        last = 0
        saved = 0
        keywords = []
    }

    // MARK: subtitles

    /// a subtitle layer drawn over a view of the player (the page's, the full screen one)
    func attach(_ l: CaptionLayer) {
        layers.add(l)
        l.set(cues)
    }

    private func setCues(_ c: [Subtitles.Cue]) {
        cues = c
        for l in layers.allObjects { l.set(c) }
    }

    private func loadCaptions() {
        setCues([])
        captionTask?.cancel()
        guard let c = choice else { return }
        captionTask = Task { [weak self] in
            let cues = await Subtitles.load(c.url)
            guard !Task.isCancelled, let self, self.choice == c else { return }
            self.setCues(cues)
        }
    }

    func select(_ c: Subtitles.Choice?) {
        choice = c
        Settings.captions = c?.lang ?? ""
        loadCaptions()
        menu()
    }

    /// Subtitles and Speed in the full screen player's top bar
    func menu() {
        guard let vc = screen else { return }
        var items: [UIMenuElement] = []
        if !choices.isEmpty {
            let off = UIAction(title: "Off", state: choice == nil ? .on : .off) { [weak self] _ in
                MainActor.assumeIsolated { self?.select(nil) }
            }
            let tracks = choices.map { c in
                UIAction(title: c.title, state: c == choice ? .on : .off) { [weak self] _ in
                    MainActor.assumeIsolated { self?.select(c) }
                }
            }
            items.append(UIMenu(title: "Subtitles", image: UIImage(systemName: "captions.bubble"), children: [off] + tracks))
        }
        let speeds: [(String, Float)] = [("0.5×", 0.5), ("0.75×", 0.75), ("Normal", 1), ("1.25×", 1.25), ("1.5×", 1.5), ("2×", 2)]
        items.append(UIMenu(title: "Speed", image: UIImage(systemName: "gauge.with.dots.needle.67percent"), children: speeds.map { name, r in
            UIAction(title: name, state: r == rate ? .on : .off) { [weak self] _ in
                MainActor.assumeIsolated {
                    guard let self else { return }
                    self.rate = r
                    self.player.defaultRate = r
                    if self.player.rate != 0 { self.player.rate = r }
                    self.menu()
                }
            }
        }))
        vc.transportBarCustomMenuItems = items
    }

    private func meta(_ id: AVMetadataIdentifier, _ value: String) -> AVMetadataItem {
        let m = AVMutableMetadataItem()
        m.identifier = id
        m.value = value as NSString
        m.extendedLanguageTag = "und"
        return m
    }
}

/// the player drawn on the page
final class PlayerSurface: UIView {
    override class var layerClass: AnyClass { AVPlayerLayer.self }
    var playerLayer: AVPlayerLayer { layer as! AVPlayerLayer }
    let captions = CaptionLayer(compact: true)

    override init(frame: CGRect) {
        super.init(frame: frame)
        isUserInteractionEnabled = false
        backgroundColor = .black
        playerLayer.videoGravity = .resizeAspect
        captions.translatesAutoresizingMaskIntoConstraints = false
        addSubview(captions)
        NSLayoutConstraint.activate([
            captions.leadingAnchor.constraint(equalTo: leadingAnchor),
            captions.trailingAnchor.constraint(equalTo: trailingAnchor),
            captions.topAnchor.constraint(equalTo: topAnchor),
            captions.bottomAnchor.constraint(equalTo: bottomAnchor),
        ])
    }

    required init?(coder: NSCoder) { fatalError() }
}

struct InlineVideo: UIViewRepresentable {
    let model: PlayerModel

    func makeUIView(context: Context) -> PlayerSurface {
        let v = PlayerSurface()
        v.playerLayer.player = model.player
        model.attach(v.captions)
        return v
    }

    func updateUIView(_ v: PlayerSurface, context: Context) {}

    static func dismantleUIView(_ v: PlayerSurface, coordinator: ()) {
        v.playerLayer.player = nil
    }
}

/// the same player, full screen, in Apple's player view
struct FullPlayer: UIViewControllerRepresentable {
    let model: PlayerModel

    func makeUIViewController(context: Context) -> AVPlayerViewController {
        let vc = AVPlayerViewController()
        vc.player = model.player
        if let overlay = vc.contentOverlayView {
            let captions = CaptionLayer()
            captions.translatesAutoresizingMaskIntoConstraints = false
            overlay.addSubview(captions)
            NSLayoutConstraint.activate([
                captions.leadingAnchor.constraint(equalTo: overlay.leadingAnchor),
                captions.trailingAnchor.constraint(equalTo: overlay.trailingAnchor),
                captions.topAnchor.constraint(equalTo: overlay.topAnchor),
                captions.bottomAnchor.constraint(equalTo: overlay.bottomAnchor),
            ])
            model.attach(captions)
        }
        model.screen = vc
        model.menu()
        model.player.play()
        return vc
    }

    func updateUIViewController(_ vc: AVPlayerViewController, context: Context) {}

    static func dismantleUIViewController(_ vc: AVPlayerViewController, coordinator: ()) {
        vc.player = nil
    }
}

/// a long text opened full screen (the description, a comment)
struct Reading: Identifiable {
    var title: String
    var text: String
    var id: String { title + text.prefix(40) }
}

/// One video (WatchScreen.kt on a TV): it plays at the top of its page, with the title, the
/// channel and the buttons under it, and Up next or the comments in a column on the right.
/// OK on the video: full screen. Play/Pause on the remote: pause.
struct WatchView: View {
    let video: Video
    @State private var model = PlayerModel.shared
    @State private var library = Library.shared
    @State private var side: Side = Settings.hideRelated ? .comments : .next
    @State private var comments: [Comment] = []
    @State private var commentsNext: String?
    @State private var commentsLoaded = false
    @State private var replies: [String: [Comment]] = [:]
    @State private var reading: Reading?
    @FocusState private var playerFocused: Bool

    enum Side { case next, comments }

    var body: some View {
        GeometryReader { g in
            HStack(alignment: .top, spacing: 0) {
                ScrollView {
                    VStack(alignment: .leading, spacing: 0) {
                        playerBox
                        info
                    }
                    .padding(32)
                }
                .focusSection()
                if showsSide {
                    sidePanel
                        .frame(width: min(760, 0.32 * g.size.width))
                        .focusSection()
                }
            }
        }
        .task(id: video.id) {
            model.open(video)
            playerFocused = true
            // `-open <id> -fullscreen YES`: straight to full screen (the CI check)
            if UserDefaults.standard.bool(forKey: "fullscreen"), !openedFullscreen {
                openedFullscreen = true
                try? await Task.sleep(nanoseconds: 3_000_000_000)
                model.fullscreen = true
            }
        }
        .task(id: commentsKey) { await firstComments() }
        .onPlayPauseCommand { model.toggle() }
        .sheet(item: $reading) { ReadingView(reading: $0) }
    }

    private var current: Video { model.video?.id == video.id ? model.video! : video }
    private var mine: Bool { model.video?.id == video.id }
    private var showsSide: Bool { !(Settings.hideRelated && Settings.hideComments) }
    private var commentsKey: String { "\(video.id)|\(side == .comments)|\(model.details?.commentsToken != nil)" }

    // MARK: the player

    @ViewBuilder private var playerBox: some View {
        if mine, let e = model.error {
            ZStack {
                Picture(current.pictures).overlay(Color.black.opacity(0.75))
                VStack(spacing: 24) {
                    Text(e).font(Fonts.bodyMedium).multilineTextAlignment(.center).foregroundStyle(Theme.text).frame(maxWidth: 800)
                    TextButton("Try again") { model.retry() }
                        .focused($playerFocused)
                }
            }
            .aspectRatio(16 / 9, contentMode: .fit)
            .clipShape(RoundedRectangle(cornerRadius: 24))
        } else {
            Button { model.fullscreen = true } label: {
                ZStack {
                    Color.black
                    InlineVideo(model: model)
                    if !mine || model.loading || model.stream == nil {
                        Picture(current.pictures)
                        ProgressView().scaleEffect(1.5)
                    } else if model.paused {
                        Image(systemName: model.ended ? "arrow.counterclockwise" : "play.fill")
                            .font(.system(size: 64)).foregroundStyle(.white)
                            .frame(width: 140, height: 140)
                            .background(Color.black.opacity(0.6), in: Circle())
                    }
                }
                .aspectRatio(16 / 9, contentMode: .fit)
                .clipShape(RoundedRectangle(cornerRadius: 24))
            }
            .buttonStyle(CardFocus(radius: 24, scale: 1.02))
            .focused($playerFocused)
        }
    }

    // MARK: under the player

    private var info: some View {
        VStack(alignment: .leading, spacing: 20) {
            Text(current.title).font(.system(size: 32, weight: .bold)).lineLimit(2)
            HStack(spacing: 8) {
                Text(meta).font(Fonts.bodySmall).foregroundStyle(Theme.sub).lineLimit(1)
                if let d = model.stream?.description, mine, !d.isEmpty {
                    TextButton("...more", color: Theme.text) { reading = Reading(title: current.title, text: d) }
                }
            }
            if !current.channelId.isEmpty {
                HStack(spacing: 24) {
                    Button { Nav.shared.push(.channel(current.channelId)) } label: {
                        HStack(spacing: 24) {
                            Avatar(url: avatarURL, name: current.author, size: 72)
                            VStack(alignment: .leading, spacing: 2) {
                                Text(current.author).font(Fonts.titleSmall).foregroundStyle(Theme.text).lineLimit(1)
                                if let s = model.details?.subsText, mine, !s.isEmpty {
                                    Text(s).font(Fonts.bodySmall).foregroundStyle(Theme.sub).lineLimit(1)
                                }
                            }
                        }
                        .padding(.horizontal, 16).padding(.vertical, 8)
                    }
                    .buttonStyle(.glow(radius: 20))
                    SubscribeButton(channel: Channel(id: current.channelId, name: current.author,
                                                     avatar: mine ? (model.details?.avatar ?? current.avatar) : current.avatar))
                }
                .focusSection()
            }
            ScrollView(.horizontal) {
                HStack(spacing: 16) {
                    likePill
                    ActionChip(library.isLater(current.id) ? "Saved" : "Watch later",
                               icon: library.isLater(current.id) ? "checkmark" : "clock") { library.toggleLater(current) }
                    if mine, let n = model.next() {
                        ActionChip("Next", icon: "forward.end.fill") {
                            if model.queue.first?.id == n.id { model.queue.removeFirst() }
                            Nav.shared.replace(with: n)
                        }
                    }
                }
                .padding(.vertical, 12)
            }
            .scrollClipDisabled()
            .focusSection()
        }
        .padding(.top, 24)
    }

    private var likePill: some View {
        let liked = library.isLiked(current.id), disliked = library.isDisliked(current.id)
        return HStack(spacing: 0) {
            Button { library.toggleLike(current, keywords: model.stream?.keywords ?? []) } label: {
                HStack(spacing: 14) {
                    Image(systemName: liked ? "hand.thumbsup.fill" : "hand.thumbsup").font(.system(size: 36))
                    Text(likes).font(Fonts.labelLarge)
                }
                .foregroundStyle(Theme.text)
                .padding(.leading, 32).padding(.trailing, 24).frame(height: 72)
            }
            .buttonStyle(Glow(shape: UnevenRoundedRectangle(topLeadingRadius: 36, bottomLeadingRadius: 36)))
            Rectangle().fill(Theme.sub.opacity(0.5)).frame(width: 2, height: 40)
            Button { library.toggleDislike(current) } label: {
                Image(systemName: disliked ? "hand.thumbsdown.fill" : "hand.thumbsdown").font(.system(size: 36))
                    .foregroundStyle(Theme.text)
                    .padding(.leading, 24).padding(.trailing, 32).frame(height: 72)
            }
            .buttonStyle(Glow(shape: UnevenRoundedRectangle(bottomTrailingRadius: 36, topTrailingRadius: 36)))
        }
        .background(Theme.chip, in: Capsule())
    }

    private var likes: String {
        if mine, let l = model.details?.likesText, !l.isEmpty { return l }
        return "Like"
    }

    private var avatarURL: URL? {
        if mine, let a = model.details?.avatar, !a.isEmpty { return imageURL(a) }
        return current.avatarURL
    }

    private var meta: String {
        let d = mine ? model.details : nil
        let views = d?.viewsText.isEmpty == false ? d!.viewsText : current.viewsText
        let when = d?.publishedText.isEmpty == false ? d!.publishedText : (current.publishedText.isEmpty ? ago(current.publishedAt) : current.publishedText)
        return [views, when].filter { !$0.isEmpty }.joined(separator: " · ")
    }

    // MARK: beside the player

    private var sidePanel: some View {
        VStack(alignment: .leading, spacing: 0) {
            HStack(spacing: 16) {
                if !Settings.hideRelated { Chip("Up next", selected: side == .next, filter: true) { side = .next } }
                if !Settings.hideComments { Chip("Comments", selected: side == .comments, filter: true) { side = .comments } }
            }
            .padding(.horizontal, 16).padding(.top, 32).padding(.bottom, 16)
            .focusSection()
            if side == .next {
                upNext
            } else {
                commentList
            }
        }
    }

    private var upNext: some View {
        ScrollView {
            LazyVStack(alignment: .leading, spacing: 4) {
                let items = mine ? model.related.items : []
                if items.isEmpty {
                    Status(error: mine && model.details != nil ? "Nothing to show" : nil).frame(height: 400)
                }
                ForEach(items) { item in
                    Group {
                        if let v = item.video {
                            VideoRow(video: v, thumb: 256, dots: false) { Nav.shared.replace(with: v) }
                        } else {
                            ItemRow(item: item)
                        }
                    }
                    .onAppear {
                        if items.suffix(3).contains(where: { $0.id == item.id }) { Task { await model.related.more(YT.relatedMore) } }
                    }
                }
            }
            .padding(.horizontal, 8).padding(.bottom, 32)
        }
    }

    private var commentList: some View {
        ScrollView {
            LazyVStack(alignment: .leading, spacing: 8) {
                if comments.isEmpty {
                    Status(error: commentsLoaded ? "No comments" : nil).frame(height: 400)
                }
                ForEach(comments) { c in
                    VStack(alignment: .leading, spacing: 4) {
                        CommentView(comment: c, creator: c.channelId == current.channelId && !c.channelId.isEmpty) {
                            reading = Reading(title: c.author, text: c.text)
                        }
                        if c.replies > 0, c.repliesToken != nil {
                            TextButton(replies[c.id] == nil ? "\(c.replies) replies" : "Hide replies", color: Theme.link) {
                                Task { await toggleReplies(c) }
                            }
                            .padding(.leading, 96)
                        }
                        ForEach(replies[c.id] ?? []) { r in
                            CommentView(comment: r, creator: r.channelId == current.channelId && !r.channelId.isEmpty) {
                                reading = Reading(title: r.author, text: r.text)
                            }
                            .padding(.leading, 80)
                        }
                    }
                    .onAppear { if c.id == comments.last?.id { Task { await moreComments() } } }
                }
            }
            .padding(.horizontal, 8).padding(.bottom, 32)
        }
    }

    private func firstComments() async {
        guard side == .comments, mine, comments.isEmpty, let token = model.details?.commentsToken else {
            if mine && model.details != nil && model.details?.commentsToken == nil { commentsLoaded = true }
            return
        }
        commentsNext = token
        await moreComments()
        commentsLoaded = true
    }

    private func moreComments() async {
        guard let token = commentsNext else { return }
        commentsNext = nil
        guard let page = try? await YT.comments(token) else { return }
        let seen = Set(comments.map(\.id))
        comments += page.items.filter { !seen.contains($0.id) }
        commentsNext = page.next
    }

    private func toggleReplies(_ c: Comment) async {
        if replies[c.id] != nil { replies[c.id] = nil; return }
        guard let token = c.repliesToken, let page = try? await YT.comments(token) else { return }
        replies[c.id] = page.items
    }
}

/// launch option `-fullscreen YES` is acted on once
@MainActor private var openedFullscreen = false

/// a rounded grey button with an icon and a word (Watch later, Next)
struct ActionChip: View {
    let title: String
    let icon: String
    let action: () -> Void

    init(_ title: String, icon: String, action: @escaping () -> Void) {
        self.title = title; self.icon = icon; self.action = action
    }

    var body: some View {
        Button(action: action) {
            HStack(spacing: 14) {
                Image(systemName: icon).font(.system(size: 34))
                Text(title).font(Fonts.labelLarge).lineLimit(1)
            }
            .foregroundStyle(Theme.text)
            .padding(.horizontal, 32).frame(height: 72)
        }
        .buttonStyle(Glow(shape: Capsule(), fill: Theme.chip))
    }
}

struct CommentView: View {
    let comment: Comment
    var creator = false
    let open: () -> Void

    var body: some View {
        Button(action: open) {
            HStack(alignment: .top, spacing: 24) {
                Avatar(url: comment.avatarURL, name: comment.author, size: 64)
                VStack(alignment: .leading, spacing: 6) {
                    Text([comment.pinned ? "📌" : "", comment.author, comment.published].filter { !$0.isEmpty }.joined(separator: " · "))
                        .font(.system(size: 24, weight: creator ? .bold : .regular))
                        .foregroundStyle(creator ? Theme.text : Theme.sub).lineLimit(1)
                    Text(comment.text).font(Fonts.bodyMedium).foregroundStyle(Theme.text).lineLimit(6)
                        .multilineTextAlignment(.leading)
                    if !comment.likes.isEmpty {
                        Label(comment.likes, systemImage: "hand.thumbsup").font(Fonts.bodySmall).foregroundStyle(Theme.sub)
                    }
                }
                .frame(maxWidth: .infinity, alignment: .leading)
            }
            .padding(16)
        }
        .buttonStyle(.glow(radius: 16))
    }
}

/// a long text, a paragraph at a time so the remote can scroll through it
struct ReadingView: View {
    let reading: Reading

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 24) {
                Text(reading.title).font(Fonts.titleLarge)
                ForEach(Array(paragraphs.enumerated()), id: \.offset) { _, p in
                    Text(p).font(Fonts.bodyMedium).frame(maxWidth: .infinity, alignment: .leading)
                        .focusable()
                }
            }
            .padding(60)
            .frame(maxWidth: 1300)
        }
    }

    private var paragraphs: [String] {
        let p = reading.text.components(separatedBy: "\n\n").map { $0.trimmingCharacters(in: .whitespacesAndNewlines) }.filter { !$0.isEmpty }
        return p.isEmpty ? [reading.text] : p
    }
}

/// A channel: its banner, picture, name, handle and subscribers, Subscribe, then Videos /
/// Shorts / Live / Playlists as cards.
struct ChannelView: View {
    let id: String
    @State private var library = Library.shared
    @State private var page: ChannelPage?
    @State private var videos = Feed()
    @State private var tab = YT.videosTab
    @State private var error: String?

    var body: some View {
        if page == nil {
            Status(error: error) { Task { await load() } }
                .task(id: id) { await load() }
        } else if let p = page {
            ItemGrid(items: videos.items, more: { await videos.more(YT.browseMore) }, focusFirst: true) {
                if !p.banner.isEmpty {
                    Picture(imageURL(p.banner))
                        .aspectRatio(6.2, contentMode: .fit)
                        .clipShape(RoundedRectangle(cornerRadius: 24))
                        .padding(.horizontal, 32).padding(.top, 24)
                }
                HStack(spacing: 32) {
                    Avatar(url: imageURL(p.avatar), name: p.name, size: 144)
                    VStack(alignment: .leading, spacing: 6) {
                        Text(p.name).font(Fonts.titleLarge).lineLimit(1)
                        Text([p.handle, p.subsText].filter { !$0.isEmpty }.joined(separator: " · "))
                            .font(Fonts.bodySmall).foregroundStyle(Theme.sub)
                    }
                    Spacer()
                    SubscribeButton(channel: Channel(id: id, name: p.name, avatar: p.avatar))
                }
                .padding(32)
                .focusSection()
                HStack(spacing: 16) {
                    ForEach(YT.channelTabs.filter { !(Settings.hideShorts && $0.name == "Shorts") }) { t in
                        Chip(t.name, selected: tab == t.params, filter: true) {
                            guard tab != t.params else { return }
                            tab = t.params
                            Task { await loadTab() }
                        }
                    }
                }
                .padding(.horizontal, 32)
                .focusSection()
                if videos.items.isEmpty {
                    Status(error: videos.loaded ? (videos.error ?? "Nothing here") : nil).frame(height: 400)
                }
            }
        }
    }

    private func load() async {
        error = nil
        do {
            let p = try await YT.channel(id)
            page = p
            videos.items = p.videos.items.filter(Feed.keep)
            videos.next = p.videos.next
            videos.loaded = true
            library.refresh(Channel(id: id, name: p.name, avatar: p.avatar))   // keeps the saved name and picture up to date
        } catch {
            self.error = error.localizedDescription
        }
    }

    private func loadTab() async {
        let f = Feed()
        videos = f
        let t = tab
        let channel = id
        await f.load { try await YT.channel(channel, tab: t).videos }
    }
}

/// A playlist: its title, who made it and how many videos, Play all, then the videos as a list.
struct PlaylistView: View {
    let id: String
    @State private var page: PlaylistPage?
    @State private var videos = Feed()
    @State private var error: String?

    var body: some View {
        if let p = page {
            ItemList(items: videos.items, more: { await videos.more(YT.browseMore) }, focusFirst: true) {
                HStack(alignment: .center, spacing: 32) {
                    VStack(alignment: .leading, spacing: 6) {
                        Text(p.title).font(Fonts.titleLarge).lineLimit(2)
                        Text([p.author, p.countText].filter { !$0.isEmpty }.joined(separator: " · "))
                            .font(Fonts.bodySmall).foregroundStyle(Theme.sub)
                    }
                    Spacer()
                    let list = videos.items.compactMap(\.video)
                    if let first = list.first {
                        FilledButton("Play all", icon: "play.fill") {
                            PlayerModel.shared.queue = Array(list.dropFirst())
                            Nav.shared.open(first)
                        }
                    }
                }
                .padding(.horizontal, 24).padding(.vertical, 16)
            }
        } else {
            Status(error: error) { Task { await load() } }
                .task(id: id) { await load() }
        }
    }

    private func load() async {
        error = nil
        do {
            let p = try await YT.playlist(id)
            page = p
            videos.items = p.videos.items
            videos.next = p.videos.next
        } catch {
            self.error = error.localizedDescription
        }
    }
}
