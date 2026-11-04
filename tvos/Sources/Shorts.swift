import AVKit
import SwiftUI

/// The Shorts tab (ShortsScreen.kt): one Short at a time, full height on black, looping. Up and
/// down on the remote go to the one before or after; OK pauses. The next two are asked for ahead.
@MainActor
@Observable
final class ShortsModel {
    static let shared = ShortsModel()

    private(set) var items: [Video] = []
    private(set) var index = 0
    private(set) var error: String?
    private(set) var loading = true
    private(set) var ready = false
    private(set) var paused = false
    private(set) var progress: Double = 0

    let player = AVQueuePlayer()
    @ObservationIgnored private var looper: AVPlayerLooper?
    @ObservationIgnored private var urls: [String: URL] = [:]
    @ObservationIgnored private var tick: Any?
    @ObservationIgnored private var playing: Task<Void, Never>?
    @ObservationIgnored private var watched: Double = 0
    @ObservationIgnored private var fetchingMore = false

    var current: Video? { items.indices.contains(index) ? items[index] : nil }

    private init() {
        tick = player.addPeriodicTimeObserver(forInterval: CMTime(value: 1, timescale: 4), queue: .main) { [weak self] t in
            MainActor.assumeIsolated { self?.ticked(t.seconds) }
        }
    }

    func start() async {
        guard items.isEmpty else { player.play(); return }
        loading = true
        error = nil
        items = await Recommender.shared.shorts()
        loading = false
        if items.isEmpty { error = "Couldn't reach YouTube"; return }
        go(0)
    }

    func stop() {
        learn()
        player.pause()
    }

    func move(_ by: Int) {
        let i = index + by
        guard items.indices.contains(i) else { return }
        go(i)
    }

    func toggle() {
        if player.timeControlStatus == .paused { player.play(); paused = false } else { player.pause(); paused = true }
    }

    private func go(_ i: Int) {
        learn()
        index = i
        ready = false
        paused = false
        progress = 0
        watched = 0
        player.pause()
        looper?.disableLooping()
        looper = nil
        player.removeAllItems()
        let v = items[i]
        playing?.cancel()
        playing = Task {
            let found = await self.url(v.id)
            guard let found, !Task.isCancelled, current?.id == v.id else {
                if current?.id == v.id && !Task.isCancelled { error = "This Short can't be played"; ready = false }
                return
            }
            error = nil
            looper = AVPlayerLooper(player: player, templateItem: AVPlayerItem(url: found))
            player.play()
            ready = true
            // the next two, ahead of time
            for n in [i + 1, i + 2] where items.indices.contains(n) { _ = await self.url(items[n].id) }
            if items.count - i < 6 { await more() }
        }
    }

    private func url(_ id: String) async -> URL? {
        if let u = urls[id] { return u }
        guard let s = try? await YT.stream(id), let u = s.hls else { return nil }
        urls[id] = u
        return u
    }

    private func more() async {
        guard !fetchingMore else { return }
        fetchingMore = true
        defer { fetchingMore = false }
        let got = await Recommender.shared.shorts(skip: Set(items.map(\.id)))
        let have = Set(items.map(\.id))
        items += got.filter { !have.contains($0.id) }
    }

    private func ticked(_ t: Double) {
        guard t.isFinite, let d = player.currentItem?.duration.seconds, d.isFinite, d > 0 else { return }
        progress = min(1, t / d)
        watched += 0.25
    }

    /// what was watched of the Short being left
    private func learn() {
        guard let v = current, watched > 0 else { return }
        Library.shared.learn(v, keywords: [], watched: watched, length: Double(max(v.lengthSec, 1)))
        watched = 0
    }
}

struct ShortsView: View {
    @State private var model = ShortsModel.shared
    @FocusState private var focused: Bool

    var body: some View {
        GeometryReader { g in
            ZStack {
                Color.black
                if let v = model.current {
                    HStack(spacing: 48) {
                        Button { model.toggle() } label: { short(v, height: g.size.height - 48) }
                            .buttonStyle(CardFocus(radius: 24, scale: 1))
                            .focused($focused)
                            .onMoveCommand { d in
                                if d == .up { model.move(-1) } else if d == .down { model.move(1) }
                            }
                        VStack(spacing: 32) {
                            round("chevron.up", enabled: model.index > 0) { model.move(-1) }
                            round("chevron.down", enabled: model.index < model.items.count - 1) { model.move(1) }
                        }
                        .focusSection()
                    }
                } else if model.loading {
                    ProgressView().scaleEffect(1.5)
                } else {
                    Status(error: model.error ?? "No Shorts to show") { Task { await model.start() } }
                }
            }
            .frame(width: g.size.width, height: g.size.height)
        }
        // black to the edges of the screen, not just inside the TV's safe margins
        .background(Color.black.ignoresSafeArea())
        .task {
            await model.start()
            focused = true
        }
        .onDisappear { model.stop() }
        .onPlayPauseCommand { model.toggle() }
    }

    private func short(_ v: Video, height: CGFloat) -> some View {
        ZStack(alignment: .bottomLeading) {
            Color.black
            if !model.ready {
                Picture([URL(string: "https://i.ytimg.com/vi/\(v.id)/oar2.jpg"), v.thumb].compactMap { $0 })
                    .overlay { if model.error == nil { ProgressView().scaleEffect(1.5) } }
            }
            ShortSurface(player: model.player)
                .opacity(model.ready ? 1 : 0)
            LinearGradient(colors: [.clear, .black.opacity(0.75)], startPoint: .center, endPoint: .bottom)
            VStack(alignment: .leading, spacing: 12) {
                // on a dark pill: the picture behind it can be white, and red on white is hard to read
                if let e = model.error {
                    Text(e).font(Fonts.bodyMedium).foregroundStyle(.white)
                        .padding(.horizontal, 20).padding(.vertical, 10)
                        .background(Capsule().fill(Color.black.opacity(0.8)))
                }
                if !v.author.isEmpty { Text(v.author.hasPrefix("@") ? v.author : "@" + v.author).font(Fonts.titleSmall) }
                Text(v.title).font(Fonts.bodyMedium).lineLimit(2)
                GeometryReader { b in
                    ZStack(alignment: .leading) {
                        Capsule().fill(Color.white.opacity(0.3))
                        Capsule().fill(Color.white).frame(width: b.size.width * model.progress)
                    }
                }
                .frame(height: 6)
            }
            .foregroundStyle(.white)
            .padding(28)
            if model.paused {
                Image(systemName: "play.fill").font(.system(size: 72)).foregroundStyle(.white)
                    .frame(maxWidth: .infinity, maxHeight: .infinity)
            }
        }
        .frame(width: height * 9 / 16, height: height)
        .clipShape(RoundedRectangle(cornerRadius: 24))
    }

    private func round(_ icon: String, enabled: Bool, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            Image(systemName: icon).font(.system(size: 40, weight: .semibold))
                .foregroundStyle(enabled ? Theme.text : Theme.sub.opacity(0.5))
                .frame(width: 96, height: 96)
        }
        .buttonStyle(Glow(shape: Circle(), fill: Theme.chip))
        .disabled(!enabled)
    }
}

struct ShortSurface: UIViewRepresentable {
    let player: AVPlayer

    func makeUIView(context: Context) -> PlayerSurface {
        let v = PlayerSurface()
        v.playerLayer.player = player
        return v
    }

    func updateUIView(_ v: PlayerSurface, context: Context) {}

    static func dismantleUIView(_ v: PlayerSurface, coordinator: ()) {
        v.playerLayer.player = nil
    }
}
