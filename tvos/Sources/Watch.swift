import AVKit
import MediaPlayer
import SwiftUI

/// The one player of the app. A video plays full screen, as on YouTube: OK or any arrow brings
/// up the controls (the seek bar with its pictures, the buttons, chapters and more videos under
/// them), left and right skip, Back hides the controls and then leaves. Subtitles are YouTube's
/// own tracks, drawn on top (Captions.swift). Where it stopped is saved every few seconds; when a
/// video ends, the next one in the queue plays, or Up next counts down to the next suggestion.
@MainActor
@Observable
final class PlayerModel {
    static let shared = PlayerModel()

    let player = AVPlayer()
    let sponsorBlock = SponsorBlockPlayer()
    private(set) var video: Video?
    private(set) var stream: StreamInfo?
    private(set) var details: Details?
    private(set) var related = Feed()
    private(set) var error: String?
    private(set) var loading = false
    private(set) var paused = true
    private(set) var buffering = false
    private(set) var ended = false
    /// where the video is, a few times a second
    private(set) var time: Double = 0
    private(set) var length: Double = 0
    private(set) var buffered: Double = 0
    /// what plays after this video, before Up next ("Play all", "Play next")
    var queue: [Video] = []
    /// the whole list "Play all" started, for "Repeat playlist": after the last one it starts again
    private(set) var round: [Video] = []
    var loopList = false
    var loopVideo = false
    /// the suggestion the end screen counts down to
    private(set) var upNext: Video?

    private(set) var choices: [Subtitles.Choice] = []
    private(set) var choice: Subtitles.Choice?
    /// "This video at a glance", once its captions are in (Glance.swift)
    private(set) var glance: Glance.Result?
    private(set) var rate: Float = Settings.speed
    private(set) var quality: Int = Settings.quality
    private(set) var audioOptions: [AVMediaSelectionOption] = []
    private(set) var audioOption: AVMediaSelectionOption?
    /// the sleep timer: when it pauses (nil: off); `sleepAtEnd` pauses when this video ends
    private(set) var sleepAt: Date?
    private(set) var sleepAtEnd = false
    /// "Stats for nerds"
    var stats = false
    /// how much the video was turned down (loudness matching), for the stats
    private(set) var turnedDown: Double = 0

    @ObservationIgnored private var cues: [Subtitles.Cue] = []
    @ObservationIgnored private let layers = NSHashTable<CaptionLayer>.weakObjects()
    @ObservationIgnored private var tick: Any?
    @ObservationIgnored private var status: NSKeyValueObservation?
    @ObservationIgnored private var itemStatus: NSKeyValueObservation?
    @ObservationIgnored private var endObserver: NSObjectProtocol?
    @ObservationIgnored private var task: Task<Void, Never>?
    @ObservationIgnored private var captionTask: Task<Void, Never>?
    @ObservationIgnored private var glanceTask: Task<Void, Never>?
    @ObservationIgnored private var last: Double = 0
    @ObservationIgnored private var saved: Double = 0
    @ObservationIgnored private var keywords: [String] = []
    @ObservationIgnored private var nowPlaying = RemoteInfo()

    private init() {
        tick = player.addPeriodicTimeObserver(forInterval: CMTime(value: 1, timescale: 10), queue: .main) { [weak self] t in
            MainActor.assumeIsolated { self?.ticked(t.seconds) }
        }
        status = player.observe(\.timeControlStatus, options: [.initial, .new]) { [weak self] p, _ in
            let paused = p.timeControlStatus == .paused
            let waiting = p.timeControlStatus == .waitingToPlayAtSpecifiedRate
            Task { @MainActor in
                guard let self else { return }
                if self.paused != paused { self.paused = paused }
                if self.buffering != waiting { self.buffering = waiting }
                self.nowPlaying.update(self)
            }
        }
        sponsorBlock.attach(player)
        nowPlaying.commands(self)
        try? AVAudioSession.sharedInstance().setCategory(.playback, mode: .moviePlayback)
    }

    /// [list] from its first video, in order
    func playAll(_ list: [Video], shuffled: Bool = false) {
        let list = shuffled ? list.shuffled() : list
        guard let first = list.first else { return }
        MusicPlayer.shared.stop()
        queue = Array(list.dropFirst())
        round = list
        Nav.shared.open(first)
    }

    /// the player of `v` opened (or came back into view)
    func open(_ v: Video) {
        if video?.id == v.id {
            if !ended && error == nil && player.currentItem != nil { player.play() }
            return
        }
        leave()
        video = v
        sponsorBlock.open(v.id)
        stream = nil
        details = nil
        related = Feed()
        error = nil
        loading = true
        ended = false
        upNext = nil
        time = 0
        length = Double(v.lengthSec)
        buffered = 0
        choices = []
        choice = nil
        audioOptions = []
        audioOption = nil
        loopVideo = false
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
            replay()
        } else if player.timeControlStatus == .paused {
            player.play()
        } else {
            player.pause()
        }
    }

    func play() { if player.currentItem != nil && !ended { player.play() } }
    func pause() { player.pause() }

    func replay() {
        ended = false
        upNext = nil
        sponsorBlock.replay()
        player.seek(to: .zero)
        player.play()
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
        queue = []
        round = []
        loopList = false
        upNext = nil
        nowPlaying.clear()
    }

    private func load(_ v: Video) async {
        let id = v.id
        async let d = try? YT.details(id)
        do {
            // through the TubeHaus server when one is connected: its own HLS, no bot check for this TV
            let s = Server.shared.connected ? try await Server.shared.stream(id) : try await YT.stream(id)
            guard video?.id == id else { return }
            stream = s
            // a video opened by id alone (or from an old list) gets its name and length from the streams
            if video?.title.isEmpty == true { video?.title = s.title }
            if video?.author.isEmpty == true { video?.author = s.author }
            if video?.channelId.isEmpty == true { video?.channelId = s.channelId }
            if video?.lengthSec == 0 { video?.lengthSec = s.lengthSec }
            if video?.views == 0 { video?.views = s.views }
            if s.live { video?.live = true }
            if length == 0 { length = Double(s.lengthSec) }
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
        related.loaded = true
        nowPlaying.update(self)
    }

    private func start(_ url: URL, _ s: StreamInfo) {
        guard let v = video else { return }
        MusicPlayer.shared.stop()
        let item = AVPlayerItem(asset: Server.shared.asset(url))
        item.externalMetadata = [meta(.commonIdentifierTitle, v.title), meta(.iTunesMetadataTrackSubTitle, v.author)]
        applyQuality(item)
        let originalAudio = Settings.originalAudio
        player.appliesMediaSelectionCriteriaAutomatically = !originalAudio
        player.replaceCurrentItem(with: item)
        watchItem(item, originalLanguage: originalAudio ? s.originalAudioLanguage : nil, pickOriginal: originalAudio)
        // loudness matching: a loud video turned down (never up), as YouTube does
        let db = s.loudnessDb ?? 0
        turnedDown = Settings.soundNorm && db > 0 ? min(20, db) : 0
        player.volume = Float(pow(10, -turnedDown / 20))
        let at = Library.shared.resumeAt(v.id, length: v.lengthSec)
        if at > 0 { player.seek(to: CMTime(seconds: at, preferredTimescale: 600)) }
        last = at
        saved = at
        time = at
        keywords = s.keywords
        player.defaultRate = rate
        if !UserDefaults.standard.bool(forKey: "noAutoplay") { player.play() }
        endObserver = NotificationCenter.default.addObserver(forName: .AVPlayerItemDidPlayToEndTime, object: item, queue: .main) { [weak self] _ in
            MainActor.assumeIsolated { self?.didEnd() }
        }
        choices = Subtitles.choices(s.captions)
        choice = Subtitles.pick(choices, tracks: s.captions, lang: Settings.captions)
        loadCaptions()
        loadGlance(s.captions, length: Double(v.lengthSec))
        nowPlaying.update(self)
    }

    /// once the item is ready: its length, its audio tracks (the original one picked when asked)
    private func watchItem(_ item: AVPlayerItem, originalLanguage: String?, pickOriginal: Bool) {
        itemStatus = item.observe(\.status, options: [.initial, .new]) { [weak self] item, _ in
            guard item.status == .readyToPlay else { return }
            Task { @MainActor in
                guard let self, self.player.currentItem === item, self.itemStatus != nil else { return }
                self.itemStatus = nil
                let d = item.duration.seconds
                if d.isFinite, d > 0 { self.length = d }
                guard let group = try? await item.asset.loadMediaSelectionGroup(for: .audible),
                      self.player.currentItem === item else { return }
                self.audioOptions = group.options
                if pickOriginal {
                    let original = group.options.first { $0.displayName.lowercased().hasSuffix(" - original") }
                    let tag = originalLanguage?.lowercased()
                    let exact = tag.flatMap { tag in group.options.first { $0.extendedLanguageTag?.lowercased() == tag } }
                    let matches = tag.map { tag in
                        group.options.filter {
                            $0.extendedLanguageTag?.lowercased().split(separator: "-").first == tag.split(separator: "-").first
                        }
                    } ?? []
                    if let option = original ?? exact ?? (matches.count == 1 ? matches.first : nil) ?? group.defaultOption {
                        item.select(option, in: group)
                    }
                }
                self.audioOption = item.currentMediaSelection.selectedMediaOption(in: group)
            }
        }
    }

    func selectAudio(_ o: AVMediaSelectionOption) {
        guard let item = player.currentItem else { return }
        Task {
            guard let group = try? await item.asset.loadMediaSelectionGroup(for: .audible) else { return }
            item.select(o, in: group)
            audioOption = o
        }
    }

    // MARK: quality, speed, sleep

    static let qualities = [0, 2160, 1440, 1080, 720, 480, 360, 240]

    static func qualityName(_ q: Int) -> String { q == 0 ? "Auto" : q >= 2160 ? "\(q)p 4K" : "\(q)p" }

    func setQuality(_ q: Int) {
        quality = q
        Settings.quality = q
        if let item = player.currentItem { applyQuality(item) }
    }

    private func applyQuality(_ item: AVPlayerItem) {
        if quality == 0 {
            item.preferredMaximumResolution = .zero
            item.preferredPeakBitRate = 0
        } else {
            item.preferredMaximumResolution = CGSize(width: Double(quality) * 16 / 9, height: Double(quality))
            item.preferredPeakBitRate = 0
        }
    }

    static let speeds: [Float] = [0.25, 0.5, 0.75, 1, 1.25, 1.5, 1.75, 2]

    static func speedName(_ r: Float) -> String { r == 1 ? "Normal" : String(format: "%g×", r) }

    func setRate(_ r: Float) {
        rate = r
        Settings.speed = r
        player.defaultRate = r
        if player.rate != 0 { player.rate = r }
        nowPlaying.update(self)
    }

    /// minutes until it pauses (0: off, -1: at the end of this video)
    func setSleep(_ minutes: Int) {
        sleepAtEnd = minutes == -1
        sleepAt = minutes > 0 ? Date().addingTimeInterval(Double(minutes) * 60) : nil
    }

    // MARK: moving about

    /// to `s` seconds and playing
    func seek(_ s: Double, play: Bool = true) {
        let target = max(0, length > 0 ? min(s, length - 0.5) : s)
        player.seek(to: CMTime(seconds: target, preferredTimescale: 600), toleranceBefore: .zero, toleranceAfter: .zero)
        time = target
        if ended { ended = false; upNext = nil }
        if play { player.play() }
        nowPlaying.update(self)
    }

    func skip(_ by: Double) { seek(time + by, play: !paused) }

    /// to the live edge of a live video
    func toLive() {
        guard let r = player.currentItem?.seekableTimeRanges.last?.timeRangeValue else { return }
        player.seek(to: r.end)
        player.play()
    }

    private func ticked(_ t: Double) {
        guard t.isFinite, t >= 0, let v = video else { return }
        for l in layers.allObjects { l.update(t) }
        if abs(t - time) >= 0.25 { time = t }
        if let r = player.currentItem?.loadedTimeRanges.last?.timeRangeValue {
            let b = r.end.seconds
            if b.isFinite, abs(b - buffered) > 1 { buffered = b }
        }
        if let s = sleepAt, Date() >= s {
            sleepAt = nil
            player.pause()
        }
        last = t
        if t > 0, abs(t - saved) >= 5 {
            saved = t
            Library.shared.watched(v, at: t)
            nowPlaying.update(self)
        }
    }

    private func didEnd() {
        guard let v = video else { return }
        if let d = player.currentItem?.duration.seconds, d.isFinite { last = d }
        Library.shared.watched(v, at: last)
        if loopVideo {
            sponsorBlock.replay()
            player.seek(to: .zero)
            player.play()
            return
        }
        if sleepAtEnd {
            sleepAtEnd = false
            ended = true
            return
        }
        if let q = queue.first {
            queue.removeFirst()
            Nav.shared.replace(with: q)     // its page opens it (WatchView)
        } else if loopList, round.count > 1, let first = round.first {
            queue = Array(round.dropFirst())
            Nav.shared.replace(with: first)
        } else {
            ended = true
            upNext = Settings.autoplayNext ? suggestion() : nil
        }
    }

    /// the first of Up next not seen yet
    func suggestion() -> Video? {
        related.items.compactMap(\.video).first { !$0.live && !Library.shared.finished($0.id) && $0.id != video?.id }
    }

    /// what Next plays: the queue, then Up next
    func next() -> Video? { queue.first ?? suggestion() }

    func playNext() {
        guard let n = next() else { return }
        if queue.first?.id == n.id { queue.removeFirst() }
        Nav.shared.replace(with: n)
    }

    func cancelUpNext() { upNext = nil }

    /// the video before (if any) is done with: remember it and learn from it
    private func leave() {
        sponsorBlock.close()
        itemStatus = nil
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
        glanceTask?.cancel()
        glance = nil
        player.pause()
        player.replaceCurrentItem(with: nil)
        last = 0
        saved = 0
        keywords = []
    }

    // MARK: subtitles

    /// a subtitle layer drawn over the video
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
    }

    /// CC: the language used last, else the TV's, else the first track; off when on
    func toggleCaptions() {
        if choice != nil { select(nil); return }
        let mine = String((Locale.preferredLanguages.first ?? "en").prefix(2))
        let pick = choices.first { $0.lang == Settings.captions } ?? choices.first { $0.lang.hasPrefix(mine) } ?? choices.first
        select(pick)
    }

    func setCaptionSize(_ i: Int) {
        Settings.captionSize = i
        for l in layers.allObjects { l.resize() }
    }

    /// the subtitles move up while the controls are shown
    func liftCaptions(_ up: Bool) {
        for l in layers.allObjects { l.lift(up) }
    }

    /// the words and moments of the video, from a track in the TV's language (one made by people
    /// first), else the first track; nothing when the video has no captions
    private func loadGlance(_ tracks: [Caption], length: Double) {
        glance = nil
        glanceTask?.cancel()
        guard Settings.glance, !tracks.isEmpty else { return }
        let mine = String((Locale.preferredLanguages.first ?? "en").prefix(2))
        let t = tracks.first { $0.lang.hasPrefix(mine) && !$0.auto } ?? tracks.first { $0.lang.hasPrefix(mine) }
            ?? tracks.first { !$0.auto } ?? tracks[0]
        let id = video?.id
        glanceTask = Task { [weak self] in
            let cues = await Subtitles.load(t.url + "&fmt=vtt")
            guard !Task.isCancelled, let self, self.video?.id == id else { return }
            self.glance = Glance.analyze(cues, length: length)
        }
    }

    // MARK: stats for nerds

    var statsLines: [(String, String)] {
        guard let item = player.currentItem else { return [] }
        let e = item.accessLog()?.events.last
        let size = item.presentationSize
        var out: [(String, String)] = [
            ("Video ID", video?.id ?? ""),
            ("Picture", size.width > 0 ? "\(Int(size.width))×\(Int(size.height))" : "–"),
            ("Most lines allowed", Self.qualityName(quality)),
        ]
        if let e {
            out.append(("Stream bitrate", e.indicatedBitrate > 0 ? String(format: "%.2f Mbps", e.indicatedBitrate / 1e6) : "–"))
            out.append(("Connection", e.observedBitrate > 0 ? String(format: "%.1f Mbps", e.observedBitrate / 1e6) : "–"))
            out.append(("Dropped frames", "\(max(0, e.numberOfDroppedVideoFrames))"))
            out.append(("Stalls", "\(max(0, e.numberOfStalls))"))
            out.append(("Media requests","\(max(0, e.numberOfMediaRequests))"))
        }
        out.append(("Buffer", String(format: "%.0f s", max(0, buffered - time))))
        out.append(("Volume", turnedDown > 0 ? String(format: "−%.1f dB (loudness)", turnedDown) : "100%"))
        out.append(("Speed", Self.speedName(rate)))
        out.append(("Through", Server.shared.connected ? "TubeHaus server" : "YouTube directly"))
        return out
    }

    private func meta(_ id: AVMetadataIdentifier, _ value: String) -> AVMetadataItem {
        let m = AVMutableMetadataItem()
        m.identifier = id
        m.value = value as NSString
        m.extendedLanguageTag = "und"
        return m
    }
}

/// What the TV's Control Center and the Remote app on a phone show and can do.
@MainActor
private struct RemoteInfo {
    private var wired = false

    mutating func commands(_ m: PlayerModel) {
        guard !wired else { return }
        wired = true
        let c = MPRemoteCommandCenter.shared()
        c.playCommand.addTarget { _ in Task { @MainActor in m.play() }; return .success }
        c.pauseCommand.addTarget { _ in Task { @MainActor in m.pause() }; return .success }
        c.togglePlayPauseCommand.addTarget { _ in Task { @MainActor in m.toggle() }; return .success }
        c.skipForwardCommand.preferredIntervals = [NSNumber(value: Settings.seekStep)]
        c.skipBackwardCommand.preferredIntervals = [NSNumber(value: Settings.seekStep)]
        c.skipForwardCommand.addTarget { _ in Task { @MainActor in m.skip(Double(Settings.seekStep)) }; return .success }
        c.skipBackwardCommand.addTarget { _ in Task { @MainActor in m.skip(-Double(Settings.seekStep)) }; return .success }
        c.nextTrackCommand.addTarget { _ in Task { @MainActor in m.playNext() }; return .success }
        c.changePlaybackPositionCommand.addTarget { e in
            if let e = e as? MPChangePlaybackPositionCommandEvent {
                let t = e.positionTime
                Task { @MainActor in m.seek(t, play: !m.paused) }
            }
            return .success
        }
    }

    func update(_ m: PlayerModel) {
        guard let v = m.video, MusicPlayer.shared.song == nil else { return }
        var info: [String: Any] = [
            MPMediaItemPropertyTitle: v.title,
            MPMediaItemPropertyArtist: v.author,
            MPNowPlayingInfoPropertyElapsedPlaybackTime: m.time,
            MPNowPlayingInfoPropertyPlaybackRate: m.paused ? 0 : Double(m.rate),
            MPNowPlayingInfoPropertyMediaType: MPNowPlayingInfoMediaType.video.rawValue,
        ]
        if m.length > 0 { info[MPMediaItemPropertyPlaybackDuration] = m.length }
        if v.live { info[MPNowPlayingInfoPropertyIsLiveStream] = true }
        if let img = Images.cached(v.cardPictures) {
            info[MPMediaItemPropertyArtwork] = MPMediaItemArtwork(boundsSize: img.size) { _ in img }
        }
        MPNowPlayingInfoCenter.default().nowPlayingInfo = info
    }

    func clear() {
        if MusicPlayer.shared.song == nil { MPNowPlayingInfoCenter.default().nowPlayingInfo = nil }
    }
}

/// the video itself, with the subtitles over it
final class PlayerSurface: UIView {
    override class var layerClass: AnyClass { AVPlayerLayer.self }
    var playerLayer: AVPlayerLayer { layer as! AVPlayerLayer }
    let captions = CaptionLayer()

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

struct VideoSurface: UIViewRepresentable {
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

/// a long text opened full screen (the description, a comment)
struct Reading: Identifiable {
    var title: String
    var text: String
    var id: String { title + text.prefix(40) }
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

/// launch option `-fullscreen YES` is kept for the CI check (the player is always full screen now)
@MainActor var openedFullscreen = false
