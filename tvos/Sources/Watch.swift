import AVKit
import SwiftUI

/// what the player is showing
struct Playing: Equatable {
    var video: Video
    var url: URL
    var captions: [Caption]
    var keywords: [String]
    var start: Double
}

/// One video: its picture, Play (or Resume), like, Watch later, the channel, the description,
/// what YouTube suggests next and the comments. The streams are asked for as the page opens,
/// so Play starts at once; when a video ends, the next one in "Up next" plays (Settings).
struct WatchView: View {
    @State var video: Video
    @State private var library = Library.shared
    @State private var stream: StreamInfo?
    @State private var details: Details?
    @State private var related = Feed()
    @State private var comments: [Comment] = []
    @State private var commentsNext: String?
    @State private var error: String?
    @State private var playing: Playing?
    @State private var autoNext = false
    @State private var reading: Reading?
    @State private var startedOnce = false

    /// a long text opened full screen (the description, a comment)
    struct Reading: Identifiable {
        var title: String
        var text: String
        var id: String { title + text.prefix(40) }
    }

    var body: some View {
        ZStack {
            // the video's own picture, blurred, behind the page
            Picture(video.pictures)
                .blur(radius: 70)
                .overlay(Color.black.opacity(0.62))
                .ignoresSafeArea()

            Scroller {
                header
                if !related.items.isEmpty {
                    Row(title: "Up next", items: related.items) { await related.more(YT.relatedMore) }
                }
                if !comments.isEmpty {
                    Shelf(title: "Comments") {
                        ForEach(comments) { c in
                            CommentCard(comment: c) { reading = Reading(title: c.author, text: c.text) }
                                .task { if c.id == comments.last?.id { await moreComments() } }
                        }
                    }
                }
            }
        }
        .task(id: video.id) { await load() }
        .sheet(item: $reading) { r in ReadingView(reading: r) }
        .fullScreenCover(isPresented: Binding(get: { playing != nil }, set: { if !$0 { playing = nil } })) {
            if let p = playing {
                Player(item: p, progress: { v, t in library.watched(v, at: t) }, done: finished, ended: next)
                    .ignoresSafeArea()
            }
        }
    }

    private var header: some View {
        HStack(alignment: .top, spacing: 64) {
            VStack(alignment: .leading, spacing: 26) {
                Text(title).font(.system(size: 50, weight: .bold)).lineLimit(3)
                if !meta.isEmpty { Text(meta).font(.system(size: 28)).foregroundStyle(.secondary).lineLimit(1) }
                if let error { Text(error).font(.system(size: 26)).foregroundStyle(.red).lineLimit(3) }

                HStack(spacing: 24) {
                    if stream == nil && error == nil {
                        ProgressView().frame(width: 220)
                    } else {
                        Button { play(from: resume) } label: {
                            Label(resume > 0 ? "Resume from \(duration(Int(resume)))" : "Play", systemImage: "play.fill")
                        }
                        .disabled(stream?.hls == nil)
                        if resume > 0 {
                            Button { play(from: 0) } label: { Label("From the start", systemImage: "backward.end.fill") }
                                .disabled(stream?.hls == nil)
                        }
                    }
                    Button { library.toggleLike(video, keywords: stream?.keywords ?? []) } label: {
                        Label(likeLabel, systemImage: library.isLiked(video.id) ? "hand.thumbsup.fill" : "hand.thumbsup")
                    }
                    Button { library.toggleLater(video) } label: {
                        Image(systemName: library.isLater(video.id) ? "checkmark" : "clock")
                    }
                }
                .focusSection()

                if !channelId.isEmpty {
                    HStack(spacing: 24) {
                        NavigationLink(value: Route.channel(channelId)) {
                            HStack(spacing: 18) {
                                Avatar(url: avatarURL, name: author, size: 64)
                                VStack(alignment: .leading, spacing: 2) {
                                    Text(author).font(.system(size: 28, weight: .semibold)).lineLimit(1)
                                    if let s = details?.subsText, !s.isEmpty { Text(s).font(.system(size: 22)).foregroundStyle(.secondary) }
                                }
                            }
                        }
                        Button {
                            library.toggleSubscription(Channel(id: channelId, name: author, avatar: details?.avatar ?? video.avatar))
                        } label: {
                            Label(library.isSubscribed(channelId) ? "Subscribed" : "Subscribe",
                                  systemImage: library.isSubscribed(channelId) ? "checkmark" : "plus")
                        }
                    }
                    .focusSection()
                }

                if let d = stream?.description, !d.isEmpty {
                    Button { reading = Reading(title: title, text: d) } label: {
                        Text(d).font(.system(size: 24)).foregroundStyle(.secondary).lineLimit(4)
                            .multilineTextAlignment(.leading)
                            .frame(maxWidth: .infinity, alignment: .leading)
                            .padding(24)
                    }
                    .buttonStyle(.card)
                }
            }
            .frame(maxWidth: .infinity, alignment: .leading)

            Picture(video.pictures)
                .aspectRatio(16 / 9, contentMode: .fit)
                .frame(width: 720)
                .clipShape(RoundedRectangle(cornerRadius: 20))
                .shadow(color: .black.opacity(0.5), radius: 30, y: 16)
        }
        .padding(.horizontal, Theme.edge)
    }

    private var title: String { video.title.isEmpty ? (stream?.title ?? "") : video.title }
    private var author: String { video.author.isEmpty ? (stream?.author ?? "") : video.author }
    private var channelId: String { video.channelId.isEmpty ? (stream?.channelId ?? "") : video.channelId }
    private var resume: Double { library.resumeAt(video.id, length: video.lengthSec) }
    private var likeLabel: String {
        if let l = details?.likesText, !l.isEmpty { return l }
        return library.isLiked(video.id) ? "Liked" : "Like"
    }
    private var avatarURL: URL? {
        guard let a = details?.avatar, !a.isEmpty else { return video.avatarURL }
        return URL(string: a.hasPrefix("//") ? "https:" + a : a)
    }
    private var meta: String {
        let views = details?.viewsText.isEmpty == false ? details!.viewsText : video.viewsText
        let when = details?.publishedText.isEmpty == false ? details!.publishedText : (video.publishedText.isEmpty ? ago(video.publishedAt) : video.publishedText)
        return [views, when, video.lengthSec > 0 ? duration(video.lengthSec) : "", video.live ? "LIVE" : ""]
            .filter { !$0.isEmpty }.joined(separator: "  ·  ")
    }

    private func load() async {
        error = nil
        stream = nil
        details = nil
        comments = []
        commentsNext = nil
        let id = video.id
        async let d = try? YT.details(id)
        do {
            let s = try await YT.stream(id)
            guard id == video.id else { return }
            stream = s
            // a video opened by id alone (or from an old list) gets its name and length from the streams
            if video.title.isEmpty { video.title = s.title }
            if video.author.isEmpty { video.author = s.author }
            if video.channelId.isEmpty { video.channelId = s.channelId }
            if video.lengthSec == 0 { video.lengthSec = s.lengthSec }
            if video.views == 0 { video.views = s.views }
            if s.live { video.live = true }
            if s.hls == nil { error = "This video has no stream the Apple TV can play" }
        } catch {
            self.error = error.localizedDescription
        }
        if autoNext {
            autoNext = false
            if stream?.hls != nil { play(from: resume) } else { playing = nil }
        } else if !startedOnce, UserDefaults.standard.bool(forKey: "autoplay"), stream?.hls != nil {
            startedOnce = true
            play(from: resume)      // the CI check
        }
        let det = await d
        guard id == video.id else { return }
        details = det
        if let det {
            related.items = det.related.filter { Feed.keep($0) && $0.id != "v" + id }
            related.next = det.relatedNext
            commentsNext = det.commentsToken
            await moreComments()
        }
    }

    private func moreComments() async {
        guard let token = commentsNext else { return }
        commentsNext = nil
        guard let page = try? await YT.comments(token) else { return }
        let seen = Set(comments.map(\.id))
        comments += page.items.filter { !seen.contains($0.id) }
        commentsNext = page.next
    }

    private func play(from t: Double) {
        guard let s = stream, let url = s.hls else { return }
        playing = Playing(video: video, url: url, captions: s.captions, keywords: s.keywords, start: t)
    }

    /// the player left a video (closed, or went on to the next): remember it and learn from it
    private func finished(_ v: Video, _ position: Double, _ keywords: [String]) {
        guard position > 0 else { return }
        library.watched(v, at: position)
        library.learn(v, keywords: keywords, watched: position, length: Double(v.lengthSec))
        if v.id == video.id, position >= 30 {
            library.rememberRelated(v.id, related.items.compactMap(\.video))
        }
    }

    /// the video played to its end: on to the next one in "Up next", or back to this page
    private func next() {
        guard Settings.autoplayNext,
              let n = related.items.compactMap(\.video).first(where: { !$0.live && !library.finished($0.id) }) else {
            playing = nil
            return
        }
        autoNext = true
        video = n
    }
}

struct CommentCard: View {
    let comment: Comment
    let open: () -> Void

    var body: some View {
        Button(action: open) {
            VStack(alignment: .leading, spacing: 14) {
                HStack(spacing: 14) {
                    Avatar(url: comment.avatarURL, name: comment.author, size: 48)
                    VStack(alignment: .leading, spacing: 0) {
                        Text(comment.author).font(.system(size: 22, weight: .semibold)).lineLimit(1)
                        Text([comment.pinned ? "Pinned" : "", comment.published].filter { !$0.isEmpty }.joined(separator: " · "))
                            .font(.system(size: 18)).foregroundStyle(.secondary).lineLimit(1)
                    }
                }
                Text(comment.text).font(.system(size: 22)).lineLimit(5).multilineTextAlignment(.leading)
                Spacer(minLength: 0)
                HStack(spacing: 24) {
                    if !comment.likes.isEmpty { Label(comment.likes, systemImage: "hand.thumbsup") }
                    if comment.replies > 0 { Label("\(comment.replies)", systemImage: "bubble.left") }
                }
                .font(.system(size: 18)).foregroundStyle(.secondary)
            }
            .padding(26)
            .frame(width: 600, height: 300, alignment: .topLeading)
        }
        .buttonStyle(.card)
    }
}

/// a long text, a paragraph at a time so the remote can scroll through it
struct ReadingView: View {
    let reading: WatchView.Reading

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 24) {
                Text(reading.title).font(.system(size: 40, weight: .bold))
                ForEach(Array(paragraphs.enumerated()), id: \.offset) { _, p in
                    Text(p).font(.system(size: 28)).frame(maxWidth: .infinity, alignment: .leading)
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

/// Apple's own player, full screen, on the HLS stream (so the Siri Remote, AirPlay and the audio
/// track menu all work as in any other Apple TV app), with YouTube's subtitles drawn on top
/// (see Captions.swift) and a speed menu. Where it stopped is saved every few seconds.
struct Player: UIViewControllerRepresentable {
    let item: Playing
    /// every few seconds while it plays
    let progress: (Video, Double) -> Void
    /// when a video is left (closed, or replaced by the next)
    let done: (Video, Double, [String]) -> Void
    /// when it played to the end
    let ended: () -> Void

    func makeUIViewController(context: Context) -> AVPlayerViewController {
        let vc = AVPlayerViewController()
        context.coordinator.attach(vc)
        context.coordinator.play(item)
        return vc
    }

    func updateUIViewController(_ vc: AVPlayerViewController, context: Context) {
        context.coordinator.parent = self
        if context.coordinator.current?.video.id != item.video.id { context.coordinator.play(item) }
    }

    static func dismantleUIViewController(_ vc: AVPlayerViewController, coordinator: Coordinator) {
        coordinator.finish()
        vc.player = nil
    }

    func makeCoordinator() -> Coordinator { Coordinator(self) }

    final class Coordinator {
        var parent: Player
        private(set) var current: Playing?
        private let player = AVPlayer()
        private let captions = CaptionLayer()
        private weak var vc: AVPlayerViewController?
        private var tick: Any?
        private var endObserver: NSObjectProtocol?
        private var last: Double = 0
        private var saved: Double = 0
        private var choices: [Subtitles.Choice] = []
        private var choice: Subtitles.Choice?
        private var loading: Task<Void, Never>?
        private static var rate: Float = 1

        init(_ parent: Player) { self.parent = parent }

        func attach(_ vc: AVPlayerViewController) {
            self.vc = vc
            vc.player = player
            if let overlay = vc.contentOverlayView {
                captions.translatesAutoresizingMaskIntoConstraints = false
                overlay.addSubview(captions)
                NSLayoutConstraint.activate([
                    captions.leadingAnchor.constraint(equalTo: overlay.leadingAnchor),
                    captions.trailingAnchor.constraint(equalTo: overlay.trailingAnchor),
                    captions.topAnchor.constraint(equalTo: overlay.topAnchor),
                    captions.bottomAnchor.constraint(equalTo: overlay.bottomAnchor),
                ])
            }
            tick = player.addPeriodicTimeObserver(forInterval: CMTime(value: 1, timescale: 10), queue: .main) { [weak self] t in
                guard let self, t.seconds.isFinite, t.seconds > 0 else { return }
                self.captions.update(t.seconds)
                self.last = t.seconds
                if abs(t.seconds - self.saved) >= 5, let v = self.current?.video {
                    self.saved = t.seconds
                    self.parent.progress(v, t.seconds)
                }
            }
        }

        func play(_ p: Playing) {
            leave()
            current = p
            last = p.start
            saved = p.start
            let item = AVPlayerItem(url: p.url)
            item.externalMetadata = [meta(.commonIdentifierTitle, p.video.title), meta(.iTunesMetadataTrackSubTitle, p.video.author)]
            player.replaceCurrentItem(with: item)
            if p.start > 0 { player.seek(to: CMTime(seconds: p.start, preferredTimescale: 600)) }
            player.defaultRate = Self.rate
            player.play()
            endObserver = NotificationCenter.default.addObserver(forName: .AVPlayerItemDidPlayToEndTime, object: item, queue: .main) { [weak self] _ in
                guard let self, let v = self.current?.video else { return }
                if item.duration.seconds.isFinite { self.last = item.duration.seconds }
                self.parent.progress(v, self.last)
                self.parent.ended()
            }
            choices = Subtitles.choices(p.captions)
            choice = Subtitles.pick(choices, tracks: p.captions, lang: Settings.captions)
            loadCaptions()
            menu()
        }

        /// the video before (if any) is done with
        private func leave() {
            if let c = current, last > 0 { parent.done(c.video, last, c.keywords) }
            if let endObserver { NotificationCenter.default.removeObserver(endObserver) }
            endObserver = nil
            loading?.cancel()
            captions.set([])
        }

        func finish() {
            let t = player.currentTime().seconds
            if t.isFinite && t > 0 { last = t }
            leave()
            current = nil
            if let tick { player.removeTimeObserver(tick) }
            tick = nil
            player.pause()
        }

        private func loadCaptions() {
            captions.set([])
            guard let c = choice else { return }
            loading = Task { @MainActor [weak self] in
                let cues = await Subtitles.load(c.url)
                guard !Task.isCancelled, let self, self.choice == c else { return }
                self.captions.set(cues)
            }
        }

        private func select(_ c: Subtitles.Choice?) {
            choice = c
            Settings.captions = c?.lang ?? ""
            loading?.cancel()
            loadCaptions()
            menu()
        }

        /// Subtitles and Speed in the player's top bar
        private func menu() {
            var items: [UIMenuElement] = []
            if !choices.isEmpty {
                let off = UIAction(title: "Off", state: choice == nil ? .on : .off) { [weak self] _ in self?.select(nil) }
                let tracks = choices.map { c in
                    UIAction(title: c.title, state: c == choice ? .on : .off) { [weak self] _ in self?.select(c) }
                }
                items.append(UIMenu(title: "Subtitles", image: UIImage(systemName: "captions.bubble"), children: [off] + tracks))
            }
            let speeds: [(String, Float)] = [("0.5×", 0.5), ("0.75×", 0.75), ("Normal", 1), ("1.25×", 1.25), ("1.5×", 1.5), ("2×", 2)]
            items.append(UIMenu(title: "Speed", image: UIImage(systemName: "gauge.with.dots.needle.67percent"), children: speeds.map { name, r in
                UIAction(title: name, state: r == Self.rate ? .on : .off) { [weak self] _ in
                    guard let self else { return }
                    Self.rate = r
                    self.player.defaultRate = r
                    if self.player.rate != 0 { self.player.rate = r }
                    self.menu()
                }
            }))
            vc?.transportBarCustomMenuItems = items
        }

        private func meta(_ id: AVMetadataIdentifier, _ value: String) -> AVMetadataItem {
            let m = AVMutableMetadataItem()
            m.identifier = id
            m.value = value as NSString
            m.extendedLanguageTag = "und"
            return m
        }
    }
}

struct ChannelView: View {
    let id: String
    @State private var library = Library.shared
    @State private var page: ChannelPage?
    @State private var videos = Feed()
    @State private var error: String?

    var body: some View {
        Scroller {
            if let p = page {
                VStack(alignment: .leading, spacing: 0) {
                    if !p.banner.isEmpty {
                        Picture(URL(string: p.banner.hasPrefix("//") ? "https:" + p.banner : p.banner))
                            .frame(height: 260)
                            .clipShape(RoundedRectangle(cornerRadius: 20))
                            .padding(.horizontal, Theme.edge)
                            .padding(.bottom, 36)
                    }
                    HStack(spacing: 40) {
                        Avatar(url: URL(string: p.avatar.hasPrefix("//") ? "https:" + p.avatar : p.avatar), name: p.name, size: 150)
                        VStack(alignment: .leading, spacing: 10) {
                            Text(p.name).font(.system(size: 50, weight: .bold)).lineLimit(1)
                            if !p.subsText.isEmpty { Text(p.subsText).font(.system(size: 26)).foregroundStyle(.secondary) }
                        }
                        Spacer()
                        Button {
                            library.toggleSubscription(Channel(id: id, name: p.name, avatar: p.avatar))
                        } label: {
                            Label(library.isSubscribed(id) ? "Subscribed" : "Subscribe", systemImage: library.isSubscribed(id) ? "checkmark" : "plus")
                        }
                    }
                    .padding(.horizontal, Theme.edge)
                }
                if videos.items.isEmpty {
                    Status(error: "This channel has no videos", icon: "film")
                } else {
                    CardGrid(items: videos.items) { await videos.more(YT.browseMore) }
                }
            } else {
                Status(error: error) { Task { await load() } }
            }
        }
        .task(id: id) { if page == nil { await load() } }
    }

    private func load() async {
        error = nil
        do {
            let p = try await YT.channel(id)
            page = p
            videos.items = p.videos.items.filter(Feed.keep)
            videos.next = p.videos.next
            library.refresh(Channel(id: id, name: p.name, avatar: p.avatar))   // keeps the saved name and picture up to date
        } catch {
            self.error = error.localizedDescription
        }
    }
}

struct PlaylistView: View {
    let id: String
    @State private var page: PlaylistPage?
    @State private var videos = Feed()
    @State private var error: String?

    var body: some View {
        Scroller {
            if let p = page {
                VStack(alignment: .leading, spacing: 10) {
                    Text(p.title).font(.system(size: 50, weight: .bold)).lineLimit(2)
                    Text([p.author, p.countText].filter { !$0.isEmpty }.joined(separator: " · ")).font(.system(size: 26)).foregroundStyle(.secondary)
                }
                .padding(.horizontal, Theme.edge)
                CardGrid(items: videos.items) { await videos.more(YT.browseMore) }
            } else {
                Status(error: error) { Task { await load() } }
            }
        }
        .task(id: id) { if page == nil { await load() } }
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
