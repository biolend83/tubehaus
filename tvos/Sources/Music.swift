import AVFoundation
import SwiftUI

/// The Music tab, from the connected TubeHaus server's YouTube Music (Server.swift): search with the
/// songs as they are on their albums first, albums, artists, and a player of its own that goes on
/// while you look at anything else, with lyrics that fill from left to right as they are sung.
@MainActor
@Observable
final class MusicPlayer {
    static let shared = MusicPlayer()

    let player = AVPlayer()
    private(set) var songs: [Song] = []
    private(set) var at = 0
    private(set) var time: Double = 0
    private(set) var paused = true
    private(set) var error: String?
    var repeatAll = false
    private(set) var lyrics: (synced: Bool, lines: [(Int, String)])?
    private(set) var lyricsFor = ""

    var song: Song? { songs.indices.contains(at) ? songs[at] : nil }

    @ObservationIgnored private var tick: Any?
    @ObservationIgnored private var status: NSKeyValueObservation?
    @ObservationIgnored private var endObserver: NSObjectProtocol?
    @ObservationIgnored private var task: Task<Void, Never>?

    private init() {
        tick = player.addPeriodicTimeObserver(forInterval: CMTime(value: 1, timescale: 20), queue: .main) { [weak self] t in
            MainActor.assumeIsolated { if t.seconds.isFinite { self?.time = t.seconds } }
        }
        status = player.observe(\.timeControlStatus, options: [.initial, .new]) { [weak self] p, _ in
            let paused = p.timeControlStatus == .paused
            Task { @MainActor in if self?.paused != paused { self?.paused = paused } }
        }
    }

    /// [list] from song [i] on (a video stops first: one thing plays at a time)
    func play(_ list: [Song], from i: Int = 0) {
        guard list.indices.contains(i) else { return }
        PlayerModel.shared.close()
        songs = list
        playAt(i)
    }

    func playAt(_ i: Int) {
        guard songs.indices.contains(i) else { return }
        at = i
        error = nil
        time = 0
        let s = songs[i]
        task?.cancel()
        task = Task {
            do {
                let info = try await Server.shared.stream(s.id)
                guard !Task.isCancelled, song?.id == s.id, let url = info.hls else { return }
                let item = AVPlayerItem(asset: Server.shared.asset(url))
                item.externalMetadata = [Self.meta(.commonIdentifierTitle, s.title), Self.meta(.iTunesMetadataTrackSubTitle, s.artist)]
                player.replaceCurrentItem(with: item)
                if let endObserver { NotificationCenter.default.removeObserver(endObserver) }
                endObserver = NotificationCenter.default.addObserver(forName: .AVPlayerItemDidPlayToEndTime, object: item, queue: .main) { [weak self] _ in
                    MainActor.assumeIsolated { self?.next() }
                }
                player.play()
                Library.shared.watched(s.video, at: 0)
            } catch {
                guard song?.id == s.id else { return }
                self.error = error.localizedDescription
            }
        }
        loadLyrics(s)
    }

    func toggle() { player.timeControlStatus == .paused ? player.play() : player.pause() }

    func next() {
        if at + 1 < songs.count { playAt(at + 1) }
        else if repeatAll, !songs.isEmpty { playAt(0) }
        else { player.pause() }
    }

    func previous() {
        if time > 4 || at == 0 { player.seek(to: .zero) } else { playAt(at - 1) }
    }

    func stop() {
        task?.cancel()
        player.replaceCurrentItem(with: nil)
        songs = []
        at = 0
        lyrics = nil
        lyricsFor = ""
    }

    private func loadLyrics(_ s: Song) {
        guard lyricsFor != s.id else { return }
        lyricsFor = s.id
        lyrics = nil
        Task {
            let l = try? await Server.shared.lyrics(s)
            guard lyricsFor == s.id else { return }
            lyrics = l ?? (false, [])
        }
    }

    private static func meta(_ id: AVMetadataIdentifier, _ value: String) -> AVMetadataItem {
        let m = AVMutableMetadataItem()
        m.identifier = id
        m.value = value as NSString
        m.extendedLanguageTag = "und"
        return m
    }
}

// MARK: the tab

struct MusicView: View {
    @State private var server = Server.shared
    @State private var query = UserDefaults.standard.string(forKey: "musicQ") ?? ""
    @State private var results: MusicResults?
    @State private var error: String?
    @State private var loading = false

    var body: some View {
        if !server.connected {
            Status(error: "Music comes from your TubeHaus server: connect one in Settings → TubeHaus server")
        } else {
            VStack(alignment: .leading, spacing: 0) {
                HStack(spacing: 16) {
                    TextField("Search songs, albums, artists", text: $query)
                        .font(Fonts.bodyLarge).autocorrectionDisabled()
                        .onSubmit(search)
                    IconButton(icon: "magnifyingglass", action: search)
                    if MusicPlayer.shared.song != nil {
                        IconButton(icon: "music.note") { Nav.shared.push(.nowPlaying) }
                    }
                }
                .padding(.horizontal, 32).padding(.vertical, 16)
                .focusSection()
                if let results {
                    ScrollView {
                        LazyVStack(alignment: .leading, spacing: 0) {
                            if !results.songs.isEmpty {
                                SectionTitle("Songs")
                                SongList(songs: Array(results.songs.prefix(10)))
                            }
                            if !results.albums.isEmpty { SectionTitle("Albums"); CardRow(cards: results.albums) }
                            if !results.artists.isEmpty { SectionTitle("Artists"); CardRow(cards: results.artists) }
                        }
                        .padding(.bottom, 64)
                    }
                } else {
                    Status(error: loading ? nil : (error ?? "Search for a song, an album or an artist")) { search() }
                }
            }
        }
    }

    private func search() {
        let q = query.trimmingCharacters(in: .whitespaces)
        guard !q.isEmpty else { return }
        UserDefaults.standard.set(q, forKey: "musicQ")
        loading = true
        results = nil
        Task {
            do { results = try await Server.shared.musicSearch(q); error = nil }
            catch { self.error = error.localizedDescription }
            loading = false
        }
    }
}

/// songs one under another: the cover, the name, the artist and album; OK plays from that one on
struct SongList: View {
    let songs: [Song]

    var body: some View {
        ForEach(Array(songs.enumerated()), id: \.element.id) { i, s in
            Button {
                MusicPlayer.shared.play(songs, from: i)
                Nav.shared.push(.nowPlaying)
            } label: {
                HStack(spacing: 24) {
                    AsyncImage(url: s.cover) { $0.resizable().scaledToFill() } placeholder: { Theme.placeholder }
                        .frame(width: 96, height: 96).clipShape(RoundedRectangle(cornerRadius: 12))
                    VStack(alignment: .leading, spacing: 4) {
                        Text(s.title).font(Fonts.bodyLarge).lineLimit(1)
                        Text([s.artist, s.album].filter { !$0.isEmpty }.joined(separator: " · "))
                            .font(Fonts.bodySmall).foregroundStyle(Theme.sub).lineLimit(1)
                    }
                    Spacer()
                    if s.lengthSec > 0 { Text(duration(s.lengthSec)).font(Fonts.bodySmall).foregroundStyle(Theme.sub) }
                }
                .padding(.horizontal, 32).padding(.vertical, 12)
            }
            .buttonStyle(.glow(radius: 16))
        }
    }
}

/// albums or artists side by side; OK opens one
struct CardRow: View {
    let cards: [MusicCard]

    var body: some View {
        ScrollView(.horizontal) {
            LazyHStack(spacing: 32) {
                ForEach(cards) { c in
                    Button {
                        Nav.shared.push(c.kind == "artist" ? .musicArtist(c.id) : .musicAlbum(c.id))
                    } label: {
                        VStack(alignment: .leading, spacing: 12) {
                            AsyncImage(url: imageURL(c.art)) { $0.resizable().scaledToFill() } placeholder: { Theme.placeholder }
                                .frame(width: 280, height: 280)
                                .clipShape(c.kind == "artist" ? AnyShape(Circle()) : AnyShape(RoundedRectangle(cornerRadius: 16)))
                            Text(c.title).font(Fonts.bodyMedium).lineLimit(1)
                            Text(c.sub).font(Fonts.bodySmall).foregroundStyle(Theme.sub).lineLimit(1)
                        }
                        .frame(width: 280)
                        .padding(12)
                    }
                    .buttonStyle(CardFocus())
                }
            }
            .padding(.horizontal, 32).padding(.vertical, 16)
        }
        .scrollClipDisabled()
    }
}

/// an album's or an artist's page: the cover, Play and Shuffle, the songs (and an artist's albums and singles)
struct MusicPageView: View {
    let id: String
    let artist: Bool
    @State private var page: MusicPage?
    @State private var error: String?

    var body: some View {
        if let page {
            ScrollView {
                LazyVStack(alignment: .leading, spacing: 0) {
                    HStack(spacing: 48) {
                        AsyncImage(url: imageURL(page.art)) { $0.resizable().scaledToFill() } placeholder: { Theme.placeholder }
                            .frame(width: 360, height: 360)
                            .clipShape(artist ? AnyShape(Circle()) : AnyShape(RoundedRectangle(cornerRadius: 24)))
                        VStack(alignment: .leading, spacing: 16) {
                            Text(page.title).font(.system(size: 56, weight: .bold)).lineLimit(2)
                            Text(page.sub).font(Fonts.bodyMedium).foregroundStyle(Theme.sub).lineLimit(2)
                            HStack(spacing: 24) {
                                if !page.songs.isEmpty {
                                    FilledButton("Play", icon: "play.fill") { MusicPlayer.shared.play(page.songs); Nav.shared.push(.nowPlaying) }
                                        .firstFocus()
                                    TextButton("Shuffle", icon: "shuffle") { MusicPlayer.shared.play(page.songs.shuffled()); Nav.shared.push(.nowPlaying) }
                                }
                            }
                        }
                    }
                    .padding(48)
                    .focusSection()
                    SongList(songs: page.songs)
                    ForEach(page.shelves.indices, id: \.self) { i in
                        SectionTitle(page.shelves[i].0)
                        CardRow(cards: page.shelves[i].1)
                    }
                }
                .padding(.bottom, 64)
            }
        } else {
            Status(error: error) { load() }.task { load() }
        }
    }

    private func load() {
        error = nil
        Task {
            do { page = try await artist ? Server.shared.musicArtist(id) : Server.shared.musicAlbum(id) }
            catch { self.error = error.localizedDescription }
        }
    }
}

// MARK: now playing

/// the cover and the buttons on the left, the lyrics on the right: the line being sung in the
/// middle, filling from left to right as it is sung
struct NowPlaying: View {
    @State private var m = MusicPlayer.shared

    var body: some View {
        HStack(alignment: .top, spacing: 64) {
            VStack(alignment: .leading, spacing: 24) {
                if let s = m.song {
                    AsyncImage(url: s.cover) { $0.resizable().scaledToFill() } placeholder: { Theme.placeholder }
                        .frame(width: 560, height: 560).clipShape(RoundedRectangle(cornerRadius: 28))
                    Text(s.title).font(.system(size: 48, weight: .bold)).lineLimit(2)
                    Text([s.artist, s.album].filter { !$0.isEmpty }.joined(separator: " · ")).font(Fonts.bodyMedium).foregroundStyle(Theme.sub).lineLimit(1)
                    if let e = m.error { Text(e).font(Fonts.bodySmall).foregroundStyle(Theme.error) }
                    HStack(spacing: 16) {
                        IconButton(icon: "backward.fill") { m.previous() }
                        IconButton(icon: m.paused ? "play.fill" : "pause.fill") { m.toggle() }.firstFocus()
                        IconButton(icon: "forward.fill") { m.next() }
                        IconButton(icon: m.repeatAll ? "repeat.circle.fill" : "repeat") { m.repeatAll.toggle() }
                    }
                    .focusSection()
                } else {
                    Status(error: "Nothing is playing")
                }
            }
            .frame(width: 600)
            Lyrics()
        }
        .padding(64)
        .onPlayPauseCommand { m.toggle() }
    }
}

struct Lyrics: View {
    @State private var m = MusicPlayer.shared

    var body: some View {
        Group {
            if let l = m.lyrics {
                if l.lines.isEmpty {
                    Text("No lyrics found for this song").font(Fonts.bodyLarge).foregroundStyle(Theme.sub)
                        .frame(maxWidth: .infinity, maxHeight: .infinity)
                } else if l.synced {
                    timed(l.lines)
                } else {
                    ScrollView {
                        VStack(alignment: .leading, spacing: 12) {
                            ForEach(l.lines.indices, id: \.self) { i in Text(l.lines[i].1).font(Fonts.bodyLarge) }
                        }
                    }
                }
            } else {
                ProgressView().frame(maxWidth: .infinity, maxHeight: .infinity)
            }
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
    }

    private func timed(_ lines: [(Int, String)]) -> some View {
        let ms = Int(m.time * 1000) + 100
        let k = lines.lastIndex { $0.0 <= ms } ?? -1
        return ScrollViewReader { proxy in
            ScrollView {
                VStack(alignment: .leading, spacing: 28) {
                    Color.clear.frame(height: 360)
                    ForEach(lines.indices, id: \.self) { i in
                        Line(text: lines[i].1.isEmpty ? "♪" : lines[i].1, state: i < k ? .past : i == k ? .now : .later,
                             fill: i == k ? fill(lines, i, ms) : 0)
                            .id(i)
                    }
                    Color.clear.frame(height: 360)
                }
            }
            .scrollDisabled(true)
            .onChange(of: k) { _, k in withAnimation(.easeInOut(duration: 0.4)) { proxy.scrollTo(max(0, k), anchor: .center) } }
            .onAppear { proxy.scrollTo(max(0, k), anchor: .center) }
        }
        .mask(LinearGradient(colors: [.clear, .black, .black, .clear], startPoint: .top, endPoint: .bottom))
    }

    /// how far the song is between this line's start and the next one's (at most 12 s)
    private func fill(_ lines: [(Int, String)], _ i: Int, _ ms: Int) -> Double {
        let from = lines[i].0
        let to = min(i + 1 < lines.count ? lines[i + 1].0 : from + 5000, from + 12000)
        return max(0, min(1, Double(ms - from) / Double(max(1, to - from))))
    }

    enum Phase { case past, now, later }

    /// one line; the one being sung is drawn twice, the bright copy cut to the part already sung
    struct Line: View {
        let text: String
        let state: Phase
        let fill: Double

        var body: some View {
            let base = Text(text).font(.system(size: 44, weight: .heavy)).fixedSize(horizontal: false, vertical: true)
            switch state {
            case .now:
                base.foregroundStyle(Theme.text.opacity(0.38))
                    .overlay(alignment: .leading) {
                        base.foregroundStyle(Theme.text)
                            .mask(GeometryReader { g in Rectangle().frame(width: g.size.width * fill) })
                    }
                    .scaleEffect(1.03, anchor: .leading)
            case .past:
                base.foregroundStyle(Theme.text.opacity(0.55))
            case .later:
                base.foregroundStyle(Theme.text.opacity(0.3))
            }
        }
    }
}
