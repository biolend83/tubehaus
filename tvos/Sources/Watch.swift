import AVKit
import SwiftUI

/// One video: big picture, Play (or Resume), the channel, Watch later, and what YouTube suggests next.
/// The streams are asked for as the page opens, so Play starts at once.
struct WatchView: View {
    @State var video: Video
    @State private var library = Library.shared
    @State private var stream: StreamInfo?
    @State private var details: Details?
    @State private var related = Feed()
    @State private var error: String?
    @State private var playing = false
    @State private var startAt: Double = 0

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 50) {
                HStack(alignment: .top, spacing: 60) {
                    // hqdefault exists for every video (maxresdefault doesn't, and then YouTube sends a grey placeholder)
                    AsyncImage(url: video.thumb) { img in
                        img.resizable().aspectRatio(16 / 9, contentMode: .fill)
                    } placeholder: {
                        Rectangle().fill(.quaternary)
                    }
                    .frame(width: 880, height: 495)
                    .clipShape(RoundedRectangle(cornerRadius: 16))

                    VStack(alignment: .leading, spacing: 24) {
                        Text(title).font(.title2.weight(.bold)).lineLimit(3)
                        Text(meta).foregroundStyle(.secondary).lineLimit(1)
                        if let error { Text(error).foregroundStyle(.red).lineLimit(3) }
                        HStack(spacing: 30) {
                            Button { play(from: resume) } label: {
                                Label(resume > 0 ? "Resume \(duration(Int(resume)))" : "Play", systemImage: "play.fill")
                            }
                            .disabled(stream?.hls == nil)
                            if resume > 0 {
                                Button { play(from: 0) } label: { Label("From the start", systemImage: "backward.end.fill") }
                                    .disabled(stream?.hls == nil)
                            }
                            Button { library.toggleLater(video) } label: {
                                Image(systemName: library.isLater(video.id) ? "checkmark" : "clock")
                            }
                        }
                        if stream == nil && error == nil { ProgressView() }
                        if !channelId.isEmpty {
                            HStack(spacing: 24) {
                                NavigationLink(value: Route.channel(channelId)) {
                                    HStack(spacing: 16) {
                                        AsyncImage(url: avatarURL) { $0.resizable().scaledToFill() } placeholder: { Circle().fill(.quaternary) }
                                            .frame(width: 60, height: 60).clipShape(Circle())
                                        VStack(alignment: .leading) {
                                            Text(author).font(.callout.weight(.semibold))
                                            if let s = details?.subsText, !s.isEmpty { Text(s).font(.caption).foregroundStyle(.secondary) }
                                        }
                                    }
                                }
                                Button(library.isSubscribed(channelId) ? "Subscribed" : "Subscribe") {
                                    library.toggleSubscription(Channel(id: channelId, name: author, avatar: details?.avatar ?? ""))
                                }
                            }
                        }
                    }
                }
                .padding(.horizontal, 80)

                if let d = stream?.description, !d.isEmpty {
                    Text(d).font(.callout).foregroundStyle(.secondary).lineLimit(6).padding(.horizontal, 80)
                        .focusable()
                }

                if !related.items.isEmpty {
                    Row(title: "Up next") {
                        ForEach(related.items) { ItemCard(item: $0) }
                        Color.clear.frame(width: 1).task { await related.more(YT.relatedMore) }
                    }
                }
            }
            .padding(.vertical, 40)
        }
        .task(id: video.id) { await load() }
        .fullScreenCover(isPresented: $playing) {
            if let s = stream, let url = s.hls {
                Player(url: url, title: title, artist: author, start: startAt) { position in
                    library.watched(video, at: position)
                }
                .ignoresSafeArea()
            }
        }
    }

    private var title: String { video.title.isEmpty ? (stream?.title ?? "") : video.title }
    private var author: String { video.author.isEmpty ? (stream?.author ?? "") : video.author }
    private var channelId: String { video.channelId.isEmpty ? (stream?.channelId ?? "") : video.channelId }
    private var resume: Double { library.resumeAt(video.id, length: video.lengthSec) }
    private var avatarURL: URL? {
        guard let a = details?.avatar, !a.isEmpty else { return nil }
        return URL(string: a.hasPrefix("//") ? "https:" + a : a)
    }
    private var meta: String {
        [details?.viewsText ?? video.viewsText, details?.publishedText ?? video.publishedText, details?.likesText.isEmpty == false ? "👍 " + details!.likesText : ""]
            .filter { !$0.isEmpty }.joined(separator: " · ")
    }

    private func load() async {
        error = nil
        let id = video.id
        async let d = try? YT.details(id)
        do {
            let s = try await YT.stream(id)
            stream = s
            // a video opened by id alone (or from an old list) gets its name and length from the streams
            if video.title.isEmpty { video.title = s.title }
            if video.author.isEmpty { video.author = s.author }
            if video.channelId.isEmpty { video.channelId = s.channelId }
            if video.lengthSec == 0 { video.lengthSec = s.lengthSec }
            if s.live { video.live = true }
            if s.hls == nil { error = "This video has no stream the Apple TV can play" }
        } catch {
            self.error = error.localizedDescription
        }
        details = await d
        if let det = details {
            related.items = det.related.filter(Feed.keep)
            related.next = det.relatedNext
        }
        if UserDefaults.standard.bool(forKey: "autoplay"), stream?.hls != nil { play(from: resume) }   // the CI check
    }

    private func play(from t: Double) {
        startAt = t
        playing = true
    }
}

/// Apple's own player, full screen, on the HLS stream (so AirPlay, subtitles and the remote all work
/// as in any other Apple TV app). Where it stopped is saved every few seconds and when it closes.
struct Player: UIViewControllerRepresentable {
    let url: URL
    let title: String
    let artist: String
    let start: Double
    let save: (Double) -> Void

    func makeUIViewController(context: Context) -> AVPlayerViewController {
        let item = AVPlayerItem(url: url)
        item.externalMetadata = [meta(.commonIdentifierTitle, title), meta(.iTunesMetadataTrackSubTitle, artist)]
        let player = AVPlayer(playerItem: item)
        let vc = AVPlayerViewController()
        vc.player = player
        context.coordinator.attach(player, item: item)
        if start > 0 { player.seek(to: CMTime(seconds: start, preferredTimescale: 600)) }
        player.play()
        return vc
    }

    func updateUIViewController(_ vc: AVPlayerViewController, context: Context) {}

    static func dismantleUIViewController(_ vc: AVPlayerViewController, coordinator: Coordinator) {
        coordinator.finish()
        vc.player?.pause()
        vc.player = nil
    }

    func makeCoordinator() -> Coordinator { Coordinator(save: save) }

    private func meta(_ id: AVMetadataIdentifier, _ value: String) -> AVMetadataItem {
        let m = AVMutableMetadataItem()
        m.identifier = id
        m.value = value as NSString
        m.extendedLanguageTag = "und"
        return m
    }

    final class Coordinator {
        let save: (Double) -> Void
        private weak var player: AVPlayer?
        private var observer: Any?
        private var ended: NSObjectProtocol?
        private var last: Double = 0

        init(save: @escaping (Double) -> Void) { self.save = save }

        func attach(_ p: AVPlayer, item: AVPlayerItem) {
            player = p
            observer = p.addPeriodicTimeObserver(forInterval: CMTime(seconds: 5, preferredTimescale: 1), queue: .main) { [weak self] t in
                guard let self, t.seconds.isFinite, t.seconds > 0 else { return }
                self.last = t.seconds
                self.save(t.seconds)
            }
            ended = NotificationCenter.default.addObserver(forName: .AVPlayerItemDidPlayToEndTime, object: item, queue: .main) { [weak self] _ in
                guard let self else { return }
                self.last = item.duration.seconds.isFinite ? item.duration.seconds : self.last
                self.save(self.last)
            }
        }

        func finish() {
            if let p = player {
                let t = p.currentTime().seconds
                if t.isFinite && t > 0 { last = t }
                if let observer { p.removeTimeObserver(observer) }
            }
            observer = nil
            if let ended { NotificationCenter.default.removeObserver(ended) }
            if last > 0 { save(last) }
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
        ScrollView {
            VStack(alignment: .leading, spacing: 50) {
                if let p = page {
                    HStack(spacing: 40) {
                        AsyncImage(url: url(p.avatar)) { $0.resizable().scaledToFill() } placeholder: { Circle().fill(.quaternary) }
                            .frame(width: 160, height: 160).clipShape(Circle())
                        VStack(alignment: .leading, spacing: 10) {
                            Text(p.name).font(.title2.weight(.bold))
                            if !p.subsText.isEmpty { Text(p.subsText).foregroundStyle(.secondary) }
                        }
                        Spacer()
                        Button(library.isSubscribed(id) ? "Subscribed" : "Subscribe") {
                            library.toggleSubscription(Channel(id: id, name: p.name, avatar: p.avatar))
                        }
                    }
                    .padding(.horizontal, 80)
                    CardGrid(items: videos.items) { await videos.more(YT.browseMore) }
                } else {
                    Status(error: error) { Task { await load() } }
                }
            }
            .padding(.vertical, 40)
        }
        .task(id: id) { await load() }
    }

    private func load() async {
        error = nil
        do {
            let p = try await YT.channel(id)
            page = p
            videos.items = p.videos.items.filter(Feed.keep)
            videos.next = p.videos.next
            // keep the saved name and picture up to date
            if library.isSubscribed(id), let old = library.subscriptions.first(where: { $0.id == id }), old.name != p.name || old.avatar != p.avatar {
                library.toggleSubscription(old)
                library.toggleSubscription(Channel(id: id, name: p.name, avatar: p.avatar))
            }
        } catch {
            self.error = error.localizedDescription
        }
    }

    private func url(_ s: String) -> URL? { URL(string: s.hasPrefix("//") ? "https:" + s : s) }
}

struct PlaylistView: View {
    let id: String
    @State private var page: PlaylistPage?
    @State private var videos = Feed()
    @State private var error: String?

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 50) {
                if let p = page {
                    VStack(alignment: .leading, spacing: 10) {
                        Text(p.title).font(.title2.weight(.bold))
                        Text([p.author, p.countText].filter { !$0.isEmpty }.joined(separator: " · ")).foregroundStyle(.secondary)
                    }
                    .padding(.horizontal, 80)
                    CardGrid(items: videos.items) { await videos.more(YT.browseMore) }
                } else {
                    Status(error: error) { Task { await load() } }
                }
            }
            .padding(.vertical, 40)
        }
        .task(id: id) { await load() }
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
