import AVKit
import SwiftUI

/// what the player screen is showing over the video
@MainActor
@Observable
final class PlayerUI {
    enum Panel: Hashable {
        case info, comments, settings, captions, captionSize, quality, speed, audio, sleep, queue

        /// the settings' own pages go back to Settings
        var parent: Panel? {
            switch self {
            case .quality, .speed, .audio, .sleep, .captionSize: return .settings
            default: return nil
            }
        }
    }

    var shown = false
    var panel: Panel?
    /// where the seek bar is being moved to (nil: not moving)
    var scrubTo: Double?
    /// the shelves under the controls are in view
    var expanded = false
    var lastInput = Date()
    @ObservationIgnored var presses = 0
    @ObservationIgnored var lastPress = Date.distantPast
    @ObservationIgnored var commit: Task<Void, Never>?

    func poke() { lastInput = Date() }
}

/// One video, full screen (WatchScreen.kt on a TV, laid out as YouTube's TV app).
struct WatchView: View {
    let video: Video
    @State private var model = PlayerModel.shared
    @State private var ui = PlayerUI()
    @State private var library = Library.shared
    @State private var comments: [Comment] = []
    @State private var commentsNext: String?
    @State private var commentsLoaded = false
    @State private var replies: [String: [Comment]] = [:]
    @State private var countdown = 0
    @FocusState private var focus: Spot?

    enum Spot: Hashable {
        case surface, bar, button(String), chapter(Double), queued(String), related(String), skip, panel, endPlay, endCancel, endReplay
    }

    var body: some View {
        ZStack {
            Color.black
            VideoSurface(model: model)
                .opacity(mine && model.stream != nil ? 1 : 0)
            if mine, let e = model.error {
                errorView(e)
            } else if !mine || model.stream == nil {
                loadingView
            } else {
                if !ui.shown && !model.ended { catcher }
                if ui.shown && !model.ended { controls.disabled(ui.panel != nil) }
                if model.ended { endScreen.disabled(ui.panel != nil) }
                skipPill
                if model.buffering && !model.paused && !ui.shown {
                    ProgressView().scaleEffect(1.6)
                }
                if model.stats { statsBox }
            }
            if let p = ui.panel { panelView(p) }
        }
        .ignoresSafeArea()
        .task(id: video.id) {
            ui.panel = nil
            ui.expanded = false
            ui.scrubTo = nil
            comments = []; commentsNext = nil; commentsLoaded = false; replies = [:]
            model.open(video)
            show()
        }
        .task {
            // the controls go away a few seconds after the last press, while the video plays
            while !Task.isCancelled {
                try? await Task.sleep(nanoseconds: 1_000_000_000)
                if ui.shown, ui.panel == nil, !model.paused, ui.scrubTo == nil, !model.ended,
                   Date().timeIntervalSince(ui.lastInput) > 6 { hide() }
            }
        }
        .onPlayPauseCommand {
            if model.ended { model.replay() } else { model.toggle() }
            show(focusBar: !ui.shown)
        }
        .onExitCommand { back() }
        .onChange(of: focus) { _, f in
            ui.poke()
            withAnimation(.easeOut(duration: 0.25)) { ui.expanded = isShelf(f) }
        }
        .onChange(of: ui.shown) { _, s in model.liftCaptions(s) }
        .onChange(of: model.paused) { _, p in if p && !model.ended && mine && model.stream != nil { show(focusBar: !ui.shown) } }
        .onChange(of: model.ended) { _, e in
            if e { ui.shown = false; startCountdown() }
        }
        .onChange(of: model.upNext?.id) { _, _ in startCountdown() }
        .onChange(of: mine && model.stream != nil) { _, ready in
            if ready { ui.shown = false; show() }
        }
    }

    private var current: Video { model.video?.id == video.id ? model.video! : video }
    private var mine: Bool { model.video?.id == video.id }

    // MARK: showing and hiding

    private func show(focusBar: Bool = true) {
        ui.poke()
        if ui.shown { return }
        withAnimation(.easeOut(duration: 0.2)) { ui.shown = true }
        if focusBar { later { focus = model.video?.live == true ? .button("play") : .bar } }
    }

    private func hide() {
        withAnimation(.easeOut(duration: 0.3)) {
            ui.shown = false
            ui.expanded = false
        }
        later { focus = .surface }
    }

    /// after the views it needs are on screen
    private func later(_ f: @escaping () -> Void) {
        Task { @MainActor in
            try? await Task.sleep(nanoseconds: 60_000_000)
            f()
        }
    }

    private func back() {
        if let p = ui.panel {
            if let parent = p.parent { ui.panel = parent; later { focus = .panel } } else { closePanel() }
        } else if ui.scrubTo != nil {
            ui.commit?.cancel()
            ui.scrubTo = nil
        } else if ui.expanded {
            later { focus = .bar }
        } else if ui.shown && !model.paused {
            hide()
        } else {
            Nav.shared.back()
        }
    }

    private func open(_ p: PlayerUI.Panel) {
        ui.poke()
        ui.panel = p
        later { focus = .panel }
        if p == .comments { Task { await firstComments() } }
    }

    private func closePanel() {
        let was = ui.panel
        ui.panel = nil
        later {
            switch was {
            case .info: focus = .button("info")
            case .comments: focus = .button("comments")
            case .captions: focus = .button("cc")
            case .queue: focus = .button("queue")
            default: focus = model.ended ? .endReplay : .button("settings")
            }
        }
    }

    private func isShelf(_ f: Spot?) -> Bool {
        switch f {
        case .some(.chapter), .some(.related), .some(.queued): return ui.shown
        default: return false
        }
    }

    // MARK: with the controls hidden

    /// the whole screen as one button: OK plays and pauses (or skips, when a skip is offered),
    /// left and right move through the video, up and down bring the controls
    private var catcher: some View {
        Button {
            if model.sponsorBlock.action != nil { model.sponsorBlock.perform() } else { model.toggle(); show() }
        } label: {
            Rectangle().fill(Color.clear).contentShape(Rectangle())
                .frame(maxWidth: .infinity, maxHeight: .infinity)
        }
        .buttonStyle(Bare())
        .focused($focus, equals: .surface)
        .onMoveCommand { d in
            switch d {
            case .left: show(); scrub(-1)
            case .right: show(); scrub(1)
            default: show()
            }
        }
    }

    private func scrub(_ dir: Int) {
        guard model.length > 0, model.video?.live != true else { return }
        let now = Date()
        ui.presses = now.timeIntervalSince(ui.lastPress) < 0.6 ? ui.presses + 1 : 0
        ui.lastPress = now
        let base = Double(Settings.seekStep)
        let step = ui.presses > 10 ? base * 6 : ui.presses > 4 ? base * 3 : base
        let from = ui.scrubTo ?? model.time
        ui.scrubTo = max(0, min(model.length - 1, from + Double(dir) * step))
        ui.poke()
        ui.commit?.cancel()
        ui.commit = Task { @MainActor in
            try? await Task.sleep(nanoseconds: 900_000_000)
            guard !Task.isCancelled else { return }
            commitScrub()
        }
    }

    private func commitScrub() {
        ui.commit?.cancel()
        guard let t = ui.scrubTo else { return }
        model.seek(t, play: !model.paused)
        ui.scrubTo = nil
        ui.poke()
    }

    // MARK: the controls

    private var controls: some View {
        GeometryReader { g in
            ZStack(alignment: .topLeading) {
                LinearGradient(colors: [.black.opacity(0.75), .clear], startPoint: .top, endPoint: .init(x: 0.5, y: 0.3))
                LinearGradient(colors: [.clear, .black.opacity(ui.expanded ? 0.95 : 0.85)], startPoint: .init(x: 0.5, y: ui.expanded ? 0.1 : 0.5), endPoint: .bottom)
                topInfo
                    .opacity(ui.expanded ? 0 : 1)
                VStack(alignment: .leading, spacing: 0) {
                    Spacer().frame(height: g.size.height - 330)
                    barBlock
                    buttonRow.padding(.top, 28)
                    shelves.padding(.top, 56)
                }
                .offset(y: ui.expanded ? -shelfLift : 0)
            }
            .frame(width: g.size.width, height: g.size.height, alignment: .topLeading)
        }
        .transition(.opacity)
    }

    /// how far everything moves up when the remote goes down into the shelves
    private var shelfLift: CGFloat {
        if case .some(.related) = focus, !(model.details?.chapters.isEmpty ?? true) || !model.queue.isEmpty { return 900 }
        return 520
    }

    private var topInfo: some View {
        HStack(alignment: .top, spacing: 28) {
            Avatar(url: avatarURL, name: current.author, size: 84)
            VStack(alignment: .leading, spacing: 8) {
                Text(current.title).font(.system(size: 44, weight: .bold)).lineLimit(2)
                Text([current.author, viewsAndDate].filter { !$0.isEmpty }.joined(separator: " · "))
                    .font(Fonts.bodyMedium).foregroundStyle(Theme.sub).lineLimit(1)
            }
            Spacer(minLength: 40)
            if model.sleepAt != nil || model.sleepAtEnd {
                Label(sleepText, systemImage: "moon.zzz.fill").font(Fonts.bodySmall).foregroundStyle(Theme.sub)
            }
        }
        .padding(.horizontal, 96).padding(.top, 64)
        .foregroundStyle(.white)
    }

    private var sleepText: String {
        if model.sleepAtEnd { return "Pauses at the end" }
        guard let s = model.sleepAt else { return "" }
        return "Pauses in \(max(1, Int(s.timeIntervalSinceNow / 60) + 1)) min"
    }

    // MARK: the seek bar

    private var barBlock: some View {
        VStack(alignment: .leading, spacing: 14) {
            HStack {
                if let c = Chapter.at(ui.scrubTo ?? model.time, in: model.details?.chapters ?? []), !c.title.isEmpty {
                    Text(c.title).font(.system(size: 28, weight: .semibold)).lineLimit(1)
                }
                Spacer()
            }
            .frame(height: 36)
            if current.live {
                HStack(spacing: 16) {
                    Circle().fill(Theme.red).frame(width: 18, height: 18)
                    Text("LIVE").font(.system(size: 28, weight: .bold))
                }
                .frame(height: 60)
            } else {
                Button { if ui.scrubTo != nil { commitScrub() } else { model.toggle() } } label: {
                    SeekBar(model: model, target: ui.scrubTo, segments: model.sponsorBlock.segments)
                }
                .buttonStyle(Bare())
                .focused($focus, equals: .bar)
                .onMoveCommand { d in
                    if d == .left { scrub(-1) } else if d == .right { scrub(1) }
                }
                HStack {
                    Text(duration(Int(ui.scrubTo ?? model.time)).ifEmpty("0:00")).monospacedDigit()
                    Text("/").foregroundStyle(Theme.sub)
                    Text(duration(Int(model.length))).monospacedDigit().foregroundStyle(Theme.sub)
                    Spacer()
                    if model.rate != 1 { Text(PlayerModel.speedName(model.rate)).foregroundStyle(Theme.sub) }
                }
                .font(.system(size: 26, weight: .medium))
            }
        }
        .padding(.horizontal, 96)
        .foregroundStyle(.white)
    }

    // MARK: the buttons

    private var buttonRow: some View {
        HStack(spacing: 20) {
            PlayerButton(id: "play", icon: model.paused ? "play.fill" : "pause.fill", label: model.paused ? "Play" : "Pause", focus: $focus) {
                model.toggle(); ui.poke()
            }
            PlayerButton(id: "back", icon: "gobackward.\(Settings.seekStep == 15 ? 15 : Settings.seekStep == 5 ? 5 : 10)", label: "Back \(Settings.seekStep) s", focus: $focus) {
                model.skip(-Double(Settings.seekStep)); ui.poke()
            }
            PlayerButton(id: "fwd", icon: "goforward.\(Settings.seekStep == 15 ? 15 : Settings.seekStep == 5 ? 5 : 10)", label: "Ahead \(Settings.seekStep) s", focus: $focus) {
                model.skip(Double(Settings.seekStep)); ui.poke()
            }
            if model.next() != nil {
                PlayerButton(id: "next", icon: "forward.end.fill", label: "Next", focus: $focus) { model.playNext() }
            }
            if current.live {
                PlayerButton(id: "live", icon: "dot.radiowaves.left.and.right", label: "Go live", focus: $focus) { model.toLive() }
            }
            Spacer(minLength: 24)
            if !model.choices.isEmpty {
                PlayerButton(id: "cc", icon: model.choice == nil ? "captions.bubble" : "captions.bubble.fill", label: "Subtitles", on: model.choice != nil, focus: $focus) {
                    if model.choices.count > 1 { open(.captions) } else { model.toggleCaptions() }
                }
            }
            PlayerButton(id: "like", icon: library.isLiked(current.id) ? "hand.thumbsup.fill" : "hand.thumbsup",
                         label: likes, on: library.isLiked(current.id), focus: $focus) {
                library.toggleLike(current, keywords: model.stream?.keywords ?? []); ui.poke()
            }
            PlayerButton(id: "dislike", icon: library.isDisliked(current.id) ? "hand.thumbsdown.fill" : "hand.thumbsdown",
                         label: "Dislike", on: library.isDisliked(current.id), focus: $focus) {
                library.toggleDislike(current); ui.poke()
            }
            PlayerButton(id: "later", icon: library.isLater(current.id) ? "checkmark" : "clock", label: library.isLater(current.id) ? "Saved" : "Watch later",
                         on: library.isLater(current.id), focus: $focus) {
                library.toggleLater(current); ui.poke()
            }
            if !current.channelId.isEmpty {
                ChannelButton(name: current.author, avatar: avatarURL, subscribed: library.isSubscribed(current.channelId), focus: $focus) {
                    Nav.shared.push(.channel(current.channelId))
                }
            }
            PlayerButton(id: "info", icon: "text.alignleft", label: "Description", focus: $focus) { open(.info) }
            if !Settings.hideComments && model.details?.commentsToken != nil {
                PlayerButton(id: "comments", icon: "text.bubble", label: commentsLabel, focus: $focus) { open(.comments) }
            }
            if !model.queue.isEmpty {
                PlayerButton(id: "queue", icon: "list.bullet", label: "Queue (\(model.queue.count))", focus: $focus) { open(.queue) }
            }
            PlayerButton(id: "settings", icon: "gearshape", label: "Settings", focus: $focus) { open(.settings) }
        }
        .padding(.horizontal, 96)
        .focusSection()
    }

    private var likes: String {
        if let l = model.details?.likesText, !l.isEmpty { return l }
        return "Like"
    }

    private var commentsLabel: String {
        if let c = model.details?.commentsCount, !c.isEmpty { return "Comments · \(c)" }
        return "Comments"
    }

    private var avatarURL: URL? {
        if let a = model.details?.avatar, !a.isEmpty { return imageURL(a) }
        return current.avatarURL ?? library.avatar(of: current.channelId)
    }

    private var viewsAndDate: String {
        let d = model.details
        let views = d?.viewsText.isEmpty == false ? d!.viewsText : current.viewsText
        let when = d?.publishedText.isEmpty == false ? d!.publishedText : (current.publishedText.isEmpty ? ago(current.publishedAt) : current.publishedText)
        return [views, when].filter { !$0.isEmpty }.joined(separator: " · ")
    }

    // MARK: under the buttons

    @ViewBuilder private var shelves: some View {
        VStack(alignment: .leading, spacing: 8) {
            if !model.queue.isEmpty {
                Shelf("Queue", subtitle: "\(model.queue.count) videos") {
                    ForEach(Array(model.queue.enumerated()), id: \.element.id) { i, v in
                        VideoTile(video: v, width: 360) {
                            model.queue.removeFirst(i + 1)
                            Nav.shared.replace(with: v)
                        }
                        .focused($focus, equals: .queued(v.id))
                    }
                }
            }
            if let chapters = model.details?.chapters, !chapters.isEmpty {
                Shelf("Chapters", subtitle: "\(chapters.count)") {
                    ForEach(chapters) { c in
                        ChapterTile(chapter: c, board: model.stream?.storyboard, length: model.length,
                                    now: Chapter.at(model.time, in: chapters)?.id == c.id) {
                            model.seek(c.start, play: true)
                            later { focus = .bar }
                        }
                        .focused($focus, equals: .chapter(c.start))
                    }
                }
            }
            if !Settings.hideRelated {
                let vids = model.related.items.compactMap(\.video)
                if vids.isEmpty {
                    Shelf("Up next") { ShelfPlaceholder(count: 4).padding(.leading, -Metrics.side) }
                } else {
                    Shelf("Up next") {
                        ForEach(vids) { v in
                            VideoTile(video: v, width: 360) { Nav.shared.replace(with: v) }
                                .focused($focus, equals: .related(v.id))
                                .onAppear {
                                    if vids.suffix(3).contains(where: { $0.id == v.id }) { Task { await model.related.more(YT.relatedMore) } }
                                }
                        }
                    }
                }
            }
        }
        .padding(.leading, 96 - Metrics.side)
    }

    // MARK: skip (SponsorBlock)

    @ViewBuilder private var skipPill: some View {
        if let a = model.sponsorBlock.action, !model.ended, ui.panel == nil {
            VStack {
                Spacer()
                HStack {
                    Spacer()
                    Button { model.sponsorBlock.perform(); ui.poke() } label: {
                        HStack(spacing: 14) {
                            if let n = model.sponsorBlock.notice {
                                Text("Skipped \(n.category.label.lowercased())").foregroundStyle(Theme.sub)
                                Rectangle().fill(Theme.sub.opacity(0.5)).frame(width: 2, height: 30)
                            }
                            Text(a.title).font(.system(size: 30, weight: .semibold))
                            Image(systemName: "forward.end.fill").font(.system(size: 26))
                            if !ui.shown {
                                Text("OK").font(.system(size: 20, weight: .bold))
                                    .padding(.horizontal, 10).padding(.vertical, 4)
                                    .overlay(RoundedRectangle(cornerRadius: 6).strokeBorder(Theme.sub, lineWidth: 2))
                            }
                        }
                        .padding(.horizontal, 36).frame(height: 84)
                    }
                    .buttonStyle(Glow(shape: RoundedRectangle(cornerRadius: 12), fill: .black.opacity(0.75)))
                    .focused($focus, equals: .skip)
                    .disabled(!ui.shown)
                }
            }
            .padding(.trailing, 96)
            .padding(.bottom, ui.shown ? (ui.expanded ? 1200 : 420) : 120)
            .animation(.easeOut(duration: 0.2), value: ui.shown)
        }
    }

    // MARK: before it plays, and when it can't

    private var loadingView: some View {
        ZStack {
            Picture(current.pictures).opacity(0.5)
            LinearGradient(colors: [.clear, .black.opacity(0.9)], startPoint: .center, endPoint: .bottom)
            VStack(alignment: .leading, spacing: 16) {
                Spacer()
                Text(current.title).font(.system(size: 52, weight: .bold)).lineLimit(2)
                if !current.author.isEmpty { Text(current.author).font(Fonts.bodyLarge).foregroundStyle(Theme.sub) }
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            .padding(96)
            ProgressView().scaleEffect(2)
            Button {} label: { Color.clear }.buttonStyle(Bare()).focused($focus, equals: .surface)
        }
    }

    private func errorView(_ e: String) -> some View {
        ZStack {
            Picture(current.pictures).opacity(0.25)
            Color.black.opacity(0.5)
            VStack(spacing: 36) {
                Image(systemName: "exclamationmark.triangle.fill").font(.system(size: 80)).foregroundStyle(Theme.sub)
                Text(current.title).font(Fonts.titleMedium).lineLimit(2).multilineTextAlignment(.center)
                Text(e).font(Fonts.bodyMedium).multilineTextAlignment(.center).foregroundStyle(Theme.sub).frame(maxWidth: 1100)
                HStack(spacing: 24) {
                    FilledButton("Try again", icon: "arrow.clockwise") { model.retry() }.firstFocus()
                    if let n = model.next() {
                        TextButton("Play the next one", icon: "forward.end") { Nav.shared.replace(with: n) }
                    }
                    TextButton("Back") { Nav.shared.back() }
                }
            }
            .padding(96)
        }
    }

    // MARK: when it ends

    private func startCountdown() {
        guard model.ended, model.upNext != nil else { return }
        countdown = max(1, Settings.countdown)
        later { focus = .endPlay }
    }

    @ViewBuilder private var endScreen: some View {
        ZStack {
            Color.black.opacity(0.7)
            if let n = model.upNext {
                HStack(alignment: .center, spacing: 72) {
                    VStack(alignment: .leading, spacing: 20) {
                        Text("Up next").font(Fonts.titleSmall).foregroundStyle(Theme.sub)
                        Text(n.title).font(.system(size: 52, weight: .bold)).lineLimit(3)
                        Text(n.byline).font(Fonts.bodyMedium).foregroundStyle(Theme.sub).lineLimit(1)
                        HStack(spacing: 24) {
                            Button { model.playNext() } label: {
                                HStack(spacing: 14) {
                                    Image(systemName: "play.fill")
                                    Text(countdown > 0 ? "Play now · \(countdown)" : "Play now")
                                }
                                .font(Fonts.labelLarge).padding(.horizontal, 44).frame(height: 80)
                            }
                            .buttonStyle(Glow(shape: Capsule(), fill: Theme.chip))
                            .focused($focus, equals: .endPlay)
                            Button { model.cancelUpNext(); later { focus = .endReplay } } label: {
                                Text("Cancel").font(Fonts.labelLarge).padding(.horizontal, 44).frame(height: 80)
                            }
                            .buttonStyle(Glow(shape: Capsule(), fill: Theme.chip))
                            .focused($focus, equals: .endCancel)
                        }
                        .padding(.top, 20)
                    }
                    .frame(maxWidth: 820, alignment: .leading)
                    ZStack(alignment: .bottom) {
                        Thumb(urls: n.cardPictures, badge: n.badge, radius: 24)
                        GeometryReader { g in
                            Rectangle().fill(Theme.red)
                                .frame(width: g.size.width * (1 - Double(countdown) / Double(max(1, Settings.countdown))), height: 8)
                                .frame(maxHeight: .infinity, alignment: .bottom)
                                .animation(.linear(duration: 1), value: countdown)
                        }
                    }
                    .frame(width: 800)
                    .clipShape(RoundedRectangle(cornerRadius: 24))
                }
                .padding(96)
                .task(id: n.id) {
                    while countdown > 0 && !Task.isCancelled {
                        try? await Task.sleep(nanoseconds: 1_000_000_000)
                        guard !Task.isCancelled, model.upNext?.id == n.id else { return }
                        countdown -= 1
                    }
                    if !Task.isCancelled, model.upNext?.id == n.id { model.playNext() }
                }
            } else {
                VStack(alignment: .leading, spacing: 0) {
                    Spacer()
                    HStack(spacing: 24) {
                        Button { model.replay(); show() } label: {
                            HStack(spacing: 14) {
                                Image(systemName: "arrow.counterclockwise")
                                Text("Watch again")
                            }
                            .font(Fonts.labelLarge).padding(.horizontal, 44).frame(height: 80)
                        }
                        .buttonStyle(Glow(shape: Capsule(), fill: Theme.chip))
                        .focused($focus, equals: .endReplay)
                        TextButton("Back to browsing") { Nav.shared.back() }
                    }
                    .padding(.horizontal, 96).padding(.bottom, 40)
                    .focusSection()
                    let vids = model.related.items.compactMap(\.video)
                    if !vids.isEmpty && !Settings.hideRelated {
                        Shelf("More videos") {
                            ForEach(vids.prefix(20)) { v in
                                VideoTile(video: v, width: 380) { Nav.shared.replace(with: v) }
                            }
                        }
                        .padding(.leading, 96 - Metrics.side)
                        .padding(.bottom, 40)
                    }
                }
                .onAppear { later { if focus == nil || focus == .surface || focus == .endPlay { focus = .endReplay } } }
            }
        }
    }

    // MARK: stats

    private var statsBox: some View {
        TimelineView(.periodic(from: .now, by: 1)) { _ in
            VStack(alignment: .leading, spacing: 6) {
                ForEach(Array(model.statsLines.enumerated()), id: \.offset) { _, line in
                    HStack(spacing: 20) {
                        Text(line.0).foregroundStyle(Theme.sub).frame(width: 260, alignment: .leading)
                        Text(line.1)
                    }
                }
            }
            .font(.system(size: 22, design: .monospaced))
            .padding(28)
            .background(Color.black.opacity(0.72), in: RoundedRectangle(cornerRadius: 14))
            .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .topLeading)
            .padding(.top, ui.shown ? 220 : 64).padding(.leading, 96)
        }
        .allowsHitTesting(false)
    }

    // MARK: panels

    private func panelView(_ p: PlayerUI.Panel) -> some View {
        HStack(spacing: 0) {
            Spacer()
            VStack(alignment: .leading, spacing: 0) {
                switch p {
                case .settings: settingsPanel
                case .captions: captionsPanel
                case .captionSize: choicePanel("Subtitle size", [(0, "Small"), (1, "Medium"), (2, "Large")].map { e in
                    (e.1, e.0 == Settings.captionSize, { model.setCaptionSize(e.0); ui.panel = .settings })
                })
                case .quality: choicePanel("Quality", PlayerModel.qualities.map { q in (PlayerModel.qualityName(q), q == model.quality, { model.setQuality(q); ui.panel = .settings }) },
                                           note: "The Apple TV picks the best the connection allows, up to this.")
                case .speed: choicePanel("Playback speed", PlayerModel.speeds.map { r in (PlayerModel.speedName(r), r == model.rate, { model.setRate(r); ui.panel = .settings }) })
                case .audio: choicePanel("Audio track", model.audioOptions.map { o in (o.displayName, o == model.audioOption, { model.selectAudio(o); ui.panel = .settings }) })
                case .sleep: choicePanel("Sleep timer", [(0, "Off"), (15, "15 minutes"), (30, "30 minutes"), (45, "45 minutes"), (60, "1 hour"), (90, "1½ hours"), (-1, "End of this video")].map { e in
                    (e.1, sleepIs(e.0), { model.setSleep(e.0); ui.panel = .settings })
                })
                case .info: infoPanel
                case .comments: commentsPanel
                case .queue: queuePanel
                }
            }
            .frame(width: p == .info || p == .comments ? 900 : 720)
            .frame(maxHeight: .infinity, alignment: .top)
            .background(Theme.raised.opacity(0.98))
            .focusSection()
            .defaultFocus($focus, .panel)
        }
        .background(Color.black.opacity(0.35))
        .transition(.move(edge: .trailing))
    }

    private func sleepIs(_ m: Int) -> Bool {
        if m == -1 { return model.sleepAtEnd }
        if m == 0 { return model.sleepAt == nil && !model.sleepAtEnd }
        return false
    }

    private func panelTitle(_ t: String) -> some View {
        Text(t).font(Fonts.titleMedium).padding(.horizontal, 48).padding(.top, 64).padding(.bottom, 24)
    }

    private var settingsPanel: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 6) {
                let _ = refresh
                panelTitle("Settings")
                PanelRow(icon: "slider.horizontal.3", title: "Quality", value: PlayerModel.qualityName(model.quality)) { ui.panel = .quality }
                    .focused($focus, equals: .panel)
                PanelRow(icon: "gauge.with.dots.needle.67percent", title: "Playback speed", value: PlayerModel.speedName(model.rate)) { ui.panel = .speed }
                if !model.choices.isEmpty {
                    PanelRow(icon: "captions.bubble", title: "Subtitles", value: model.choice?.title ?? "Off") { ui.panel = .captions }
                }
                PanelRow(icon: "textformat.size", title: "Subtitle size", value: ["Small", "Medium", "Large"][max(0, min(2, Settings.captionSize))]) { ui.panel = .captionSize }
                if model.audioOptions.count > 1 {
                    PanelRow(icon: "waveform", title: "Audio track", value: model.audioOption?.displayName ?? "") { ui.panel = .audio }
                }
                PanelRow(icon: "moon.zzz", title: "Sleep timer", value: model.sleepAtEnd ? "End of video" : model.sleepAt == nil ? "Off" : sleepText) { ui.panel = .sleep }
                PanelToggle(icon: "repeat.1", title: "Loop this video", on: model.loopVideo) { model.loopVideo.toggle() }
                if model.round.count > 1 {
                    PanelToggle(icon: "repeat", title: "Repeat playlist", on: model.loopList) { model.loopList.toggle() }
                }
                PanelToggle(icon: "play.square.stack", title: "Autoplay", on: Settings.autoplayNext) { Settings.autoplayNext.toggle(); refresh += 1 }
                PanelToggle(icon: "speaker.wave.2", title: "Loudness matching", on: Settings.soundNorm) {
                    Settings.soundNorm.toggle()
                    model.retryVolume()
                    refresh += 1
                }
                PanelToggle(icon: "chart.bar.xaxis", title: "Stats for nerds", on: model.stats) { model.stats.toggle() }
            }
            .padding(.bottom, 64)
        }
    }

    private var captionsPanel: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 6) {
                panelTitle("Subtitles")
                PanelRow(title: "Off", checked: model.choice == nil) { model.select(nil); closePanel() }
                    .focused($focus, equals: .panel)
                ForEach(model.choices, id: \.self) { c in
                    PanelRow(title: c.title, checked: c == model.choice) { model.select(c); closePanel() }
                }
                PanelRow(icon: "textformat.size", title: "Subtitle size", value: ["Small", "Medium", "Large"][max(0, min(2, Settings.captionSize))]) { ui.panel = .captionSize }
                    .padding(.top, 24)
            }
            .padding(.bottom, 64)
        }
    }

    private func choicePanel(_ title: String, _ rows: [(String, Bool, () -> Void)], note: String = "") -> some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 6) {
                panelTitle(title)
                ForEach(Array(rows.enumerated()), id: \.offset) { i, r in
                    if i == (rows.firstIndex { $0.1 } ?? 0) {
                        PanelRow(title: r.0, checked: r.1, action: r.2).focused($focus, equals: .panel)
                    } else {
                        PanelRow(title: r.0, checked: r.1, action: r.2)
                    }
                }
                if !note.isEmpty {
                    Text(note).font(Fonts.bodySmall).foregroundStyle(Theme.sub).padding(.horizontal, 48).padding(.top, 20)
                }
            }
            .padding(.bottom, 64)
        }
    }

    private var queuePanel: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 6) {
                HStack {
                    panelTitle("Queue")
                    Spacer()
                    TextButton("Clear", color: Theme.error) { model.queue.removeAll(); closePanel() }.padding(.trailing, 32).padding(.top, 40)
                }
                ForEach(Array(model.queue.enumerated()), id: \.element.id) { i, v in
                    VideoRow(video: v, thumb: 240, index: i + 1) {
                        model.queue.removeFirst(i + 1)
                        ui.panel = nil
                        Nav.shared.replace(with: v)
                    }
                    .focused($focus, equals: i == 0 ? .panel : .queued(v.id))
                }
            }
            .padding(.horizontal, 16).padding(.bottom, 64)
        }
    }

    private var infoPanel: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 24) {
                Text(current.title).font(.system(size: 40, weight: .bold)).padding(.top, 64)
                HStack(spacing: 40) {
                    stat(model.details?.likesText ?? "", "Likes")
                    stat(model.details?.viewsText.replacingOccurrences(of: " views", with: "") ?? current.viewsText, "Views")
                    stat(model.details?.publishedText ?? current.publishedText, "")
                }
                if !current.channelId.isEmpty {
                    HStack(spacing: 24) {
                        Button { Nav.shared.push(.channel(current.channelId)) } label: {
                            HStack(spacing: 20) {
                                Avatar(url: avatarURL, name: current.author, size: 80)
                                VStack(alignment: .leading, spacing: 4) {
                                    Text(current.author).font(Fonts.titleSmall).lineLimit(1)
                                    if let s = model.details?.subsText, !s.isEmpty { Text(s).font(Fonts.bodySmall).dim() }
                                }
                            }
                            .padding(.horizontal, 20).padding(.vertical, 12)
                        }
                        .buttonStyle(.glow(radius: 20))
                        .focused($focus, equals: .panel)
                        SubscribeButton(channel: Channel(id: current.channelId, name: current.author, avatar: model.details?.avatar ?? current.avatar), compact: true)
                    }
                }
                if let g = model.glance, !(g.keywords.isEmpty && g.moments.isEmpty) {
                    VStack(alignment: .leading, spacing: 12) {
                        Label("At a glance", systemImage: "sparkles").font(Fonts.titleSmall)
                        if !g.keywords.isEmpty {
                            Text(g.keywords.map { "#" + $0 }.joined(separator: "  ")).font(Fonts.bodySmall).foregroundStyle(Theme.sub)
                        }
                        ForEach(g.moments) { m in
                            momentRow(m.start, m.text)
                        }
                    }
                }
                if let chapters = model.details?.chapters, !chapters.isEmpty {
                    VStack(alignment: .leading, spacing: 8) {
                        Text("Chapters").font(Fonts.titleSmall)
                        ForEach(chapters) { c in momentRow(c.start, c.title) }
                    }
                }
                if let d = model.stream?.description, !d.isEmpty {
                    VStack(alignment: .leading, spacing: 18) {
                        ForEach(Array(paragraphs(d).enumerated()), id: \.offset) { i, p in
                            if i == 0 && current.channelId.isEmpty {
                                Text(p).font(Fonts.bodyMedium).frame(maxWidth: .infinity, alignment: .leading).focusable().focused($focus, equals: .panel)
                            } else {
                                Text(p).font(Fonts.bodyMedium).frame(maxWidth: .infinity, alignment: .leading).focusable()
                            }
                        }
                    }
                }
            }
            .padding(.horizontal, 56).padding(.bottom, 80)
        }
    }

    private func stat(_ value: String, _ label: String) -> some View {
        VStack(alignment: .leading, spacing: 2) {
            Text(value.isEmpty ? "–" : value).font(.system(size: 34, weight: .bold))
            if !label.isEmpty { Text(label).font(Fonts.bodySmall).foregroundStyle(Theme.sub) }
        }
    }

    private func momentRow(_ start: Double, _ text: String) -> some View {
        Button { model.seek(start); ui.panel = nil; later { focus = .bar } } label: {
            HStack(alignment: .firstTextBaseline, spacing: 20) {
                Text(duration(Int(start)).ifEmpty("0:00")).font(Fonts.labelLarge.monospacedDigit()).foregroundStyle(Theme.blue)
                    .frame(width: 120, alignment: .leading)
                Text(text).font(Fonts.bodySmall).lineLimit(2).frame(maxWidth: .infinity, alignment: .leading)
            }
            .padding(.horizontal, 20).padding(.vertical, 12)
        }
        .buttonStyle(.glow(radius: 14))
    }

    private func paragraphs(_ d: String) -> [String] {
        let p = d.components(separatedBy: "\n\n").map { $0.trimmingCharacters(in: .whitespacesAndNewlines) }.filter { !$0.isEmpty }
        return p.isEmpty ? [d] : p
    }

    private var commentsPanel: some View {
        ScrollView {
            LazyVStack(alignment: .leading, spacing: 8) {
                HStack(alignment: .firstTextBaseline) {
                    Text("Comments").font(Fonts.titleMedium)
                    if let c = model.details?.commentsCount, !c.isEmpty { Text(c).font(Fonts.bodyMedium).foregroundStyle(Theme.sub) }
                }
                .padding(.top, 64).padding(.horizontal, 24)
                if let sorts = model.details?.commentSorts, sorts.count > 1 {
                    HStack(spacing: 16) {
                        ForEach(Array(sorts.enumerated()), id: \.offset) { i, s in
                            Chip(s.0, selected: sortIndex == i) { Task { await sortComments(i, s.1) } }
                        }
                    }
                    .padding(.horizontal, 24).padding(.vertical, 12)
                    .focusSection()
                }
                if comments.isEmpty {
                    Status(error: commentsLoaded ? "No comments" : nil).frame(height: 400)
                }
                ForEach(comments) { c in
                    VStack(alignment: .leading, spacing: 4) {
                        Group {
                            if c.id == comments.first?.id {
                                CommentView(comment: c, creator: c.channelId == current.channelId && !c.channelId.isEmpty).focused($focus, equals: .panel)
                            } else {
                                CommentView(comment: c, creator: c.channelId == current.channelId && !c.channelId.isEmpty)
                            }
                        }
                        if c.replies > 0, c.repliesToken != nil {
                            TextButton(replies[c.id] == nil ? "\(c.replies) replies" : "Hide replies", icon: replies[c.id] == nil ? "chevron.down" : "chevron.up", color: Theme.link) {
                                Task { await toggleReplies(c) }
                            }
                            .padding(.leading, 96)
                        }
                        ForEach(replies[c.id] ?? []) { r in
                            CommentView(comment: r, creator: r.channelId == current.channelId && !r.channelId.isEmpty)
                                .padding(.leading, 80)
                        }
                    }
                    .onAppear { if c.id == comments.last?.id { Task { await moreComments() } } }
                }
            }
            .padding(.horizontal, 16).padding(.bottom, 64)
        }
    }

    @State private var sortIndex = 0
    /// the panels' switches kept in Settings, drawn again when changed
    @State private var refresh = 0

    private func sortComments(_ i: Int, _ token: String) async {
        sortIndex = i
        comments = []
        replies = [:]
        commentsLoaded = false
        commentsNext = token
        await moreComments()
        commentsLoaded = true
    }

    private func firstComments() async {
        guard comments.isEmpty, let token = model.details?.commentsToken else {
            if model.details != nil && model.details?.commentsToken == nil { commentsLoaded = true }
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

extension String {
    func ifEmpty(_ s: String) -> String { isEmpty ? s : self }
}

extension PlayerModel {
    /// loudness matching turned on or off while a video plays
    func retryVolume() {
        let db = stream?.loudnessDb ?? 0
        let down = Settings.soundNorm && db > 0 ? min(20, db) : 0
        player.volume = Float(pow(10, -down / 20))
    }
}

/// a button that draws nothing of its own
struct Bare: ButtonStyle {
    func makeBody(configuration: Configuration) -> some View { configuration.label.contentShape(Rectangle()) }
}

/// The seek bar: red up to where it is, lighter up to what's loaded, chapter gaps, SponsorBlock
/// parts in their colours, "Most replayed" above it and the picture of the moment while moving.
struct SeekBar: View {
    let model: PlayerModel
    let target: Double?
    let segments: [SponsorBlock.Segment]
    @Environment(\.isFocused) private var focused

    var body: some View {
        GeometryReader { g in
            let w = g.size.width
            let len = max(1, model.length)
            let pos = (target ?? model.time) / len
            let chapters = model.details?.chapters ?? []
            ZStack(alignment: .bottomLeading) {
                if focused, let heat = model.details?.heat {
                    HeatCurve(heat: heat, length: len)
                        .fill(Color.white.opacity(0.22))
                        .frame(width: w, height: 70)
                        .offset(y: -22)
                }
                ZStack(alignment: .leading) {
                    Rectangle().fill(Color.white.opacity(0.28))
                    Rectangle().fill(Color.white.opacity(0.5)).frame(width: w * min(1, model.buffered / len))
                    Rectangle().fill(Theme.red).frame(width: w * min(1, model.time / len))
                    if let target {
                        Rectangle().fill(Color.white.opacity(0.85)).frame(width: max(0, w * (target - model.time) / len))
                            .offset(x: w * model.time / len)
                            .opacity(target > model.time ? 1 : 0)
                    }
                    ForEach(segments, id: \.id) { s in
                        Rectangle().fill(color(s.category))
                            .frame(width: max(4, w * (s.end - s.start) / len))
                            .offset(x: w * s.start / len)
                    }
                    ForEach(chapters.dropFirst()) { c in
                        Rectangle().fill(Color.black).frame(width: 4).offset(x: w * c.start / len - 2)
                    }
                }
                .frame(height: focused ? 12 : 6)
                .clipShape(Capsule())
                Circle().fill(Theme.red)
                    .frame(width: focused ? 32 : 0, height: focused ? 32 : 0)
                    .offset(x: w * pos - (focused ? 16 : 0), y: focused ? 10 : 3)
                if let target {
                    preview(target, x: w * pos, width: w)
                }
            }
            .frame(width: w, height: 40, alignment: .bottom)
            .animation(.easeOut(duration: 0.15), value: focused)
        }
        .frame(height: 40)
    }

    @ViewBuilder private func preview(_ t: Double, x: CGFloat, width: CGFloat) -> some View {
        let pw: CGFloat = 384, ph: CGFloat = 216
        VStack(spacing: 10) {
            if let board = model.stream?.storyboard {
                StoryboardFrame(board: board, time: t, length: model.length)
                    .frame(width: pw, height: ph)
                    .clipShape(RoundedRectangle(cornerRadius: 12))
                    .overlay(RoundedRectangle(cornerRadius: 12).strokeBorder(Color.white, lineWidth: 4))
            }
            if let c = Chapter.at(t, in: model.details?.chapters ?? []), !c.title.isEmpty {
                Text(c.title).font(.system(size: 24, weight: .semibold)).lineLimit(1).frame(maxWidth: pw)
            }
            Text(duration(Int(t)).ifEmpty("0:00")).font(.system(size: 30, weight: .bold).monospacedDigit())
                .padding(.horizontal, 16).padding(.vertical, 4)
                .background(Color.black.opacity(0.7), in: Capsule())
        }
        .fixedSize()
        .position(x: min(max(x, pw / 2), width - pw / 2), y: -ph / 2 - 40)
    }

    private func color(_ c: SponsorBlock.Category) -> Color {
        switch c {
        case .sponsor: return Color(hex: 0x00D400)
        case .selfpromo: return Color(hex: 0xFFFF00)
        case .interaction: return Color(hex: 0xCC00FF)
        case .intro: return Color(hex: 0x00FFFF)
        case .outro: return Color(hex: 0x0202ED)
        case .preview: return Color(hex: 0x008FD6)
        case .musicOfftopic: return Color(hex: 0xFF9900)
        case .filler: return Color(hex: 0x7300FF)
        case .highlight: return Color(hex: 0xFF1684)
        }
    }
}

/// "Most replayed" as a soft hill over the seek bar
struct HeatCurve: Shape {
    let heat: Heat
    let length: Double

    func path(in r: CGRect) -> Path {
        var p = Path()
        let n = heat.points.count
        guard n > 1 else { return p }
        let scale = min(1, heat.span / max(1, length))
        p.move(to: CGPoint(x: 0, y: r.maxY))
        for (i, v) in heat.points.enumerated() {
            let x = r.width * scale * (Double(i) + 0.5) / Double(n)
            p.addLine(to: CGPoint(x: x, y: r.maxY - r.height * v))
        }
        p.addLine(to: CGPoint(x: r.width * scale, y: r.maxY))
        p.closeSubpath()
        return p
    }
}

/// a round button of the player, its name shown under it while the remote is on it
struct PlayerButton: View {
    let id: String
    let icon: String
    let label: String
    var on = false
    var focus: FocusState<WatchView.Spot?>.Binding
    let action: () -> Void

    var body: some View {
        Button(action: action) { Face(icon: icon, label: label, on: on) }
            .buttonStyle(TileStyle())
            .focused(focus, equals: .button(id))
    }

    private struct Face: View {
        let icon: String
        let label: String
        let on: Bool
        @Environment(\.lit) private var lit

        var body: some View {
            VStack(spacing: 12) {
                Image(systemName: icon).font(.system(size: 34, weight: .semibold))
                    .foregroundStyle(lit ? Theme.onFocus : .white)
                    .frame(width: 88, height: 88)
                    .background(Circle().fill(lit ? Theme.focus : on ? Color.white.opacity(0.32) : Color.white.opacity(0.14)))
                    .scaleEffect(lit ? 1.12 : 1)
                Text(label).font(.system(size: 22, weight: .semibold)).lineLimit(1).fixedSize()
                    .opacity(lit ? 1 : 0)
            }
            .frame(width: 96)
            .animation(.easeOut(duration: 0.15), value: lit)
        }
    }
}

/// the channel's picture and name as one button among the player's
struct ChannelButton: View {
    let name: String
    let avatar: URL?
    let subscribed: Bool
    var focus: FocusState<WatchView.Spot?>.Binding
    let action: () -> Void

    var body: some View {
        Button(action: action) { Face(name: name, avatar: avatar, subscribed: subscribed) }
            .buttonStyle(TileStyle())
            .focused(focus, equals: .button("channel"))
    }

    private struct Face: View {
        let name: String
        let avatar: URL?
        let subscribed: Bool
        @Environment(\.lit) private var lit

        var body: some View {
            VStack(spacing: 12) {
                Avatar(url: avatar, name: name, size: 88)
                    .overlay(Circle().strokeBorder(Theme.focus, lineWidth: lit ? 5 : 0))
                    .overlay(alignment: .bottomTrailing) {
                        if subscribed {
                            Image(systemName: "checkmark.circle.fill").font(.system(size: 28)).foregroundStyle(.white, Theme.red)
                        }
                    }
                    .scaleEffect(lit ? 1.12 : 1)
                Text(name).font(.system(size: 22, weight: .semibold)).lineLimit(1).fixedSize()
                    .opacity(lit ? 1 : 0)
            }
            .frame(width: 96)
            .animation(.easeOut(duration: 0.15), value: lit)
        }
    }
}

/// a chapter in the player's shelf: its picture (or the storyboard's), its title and time
struct ChapterTile: View {
    let chapter: Chapter
    let board: Storyboard?
    let length: Double
    let now: Bool
    let action: () -> Void

    var body: some View {
        Button(action: action) { Face(chapter: chapter, board: board, length: length, now: now) }
            .buttonStyle(TileStyle())
    }

    private struct Face: View {
        let chapter: Chapter
        let board: Storyboard?
        let length: Double
        let now: Bool
        @Environment(\.lit) private var lit

        var body: some View {
            VStack(alignment: .leading, spacing: 12) {
                ZStack {
                    if let u = imageURL(chapter.thumb) {
                        Picture([u], maxPixels: 640, minWidth: 1)
                    } else if let board {
                        StoryboardFrame(board: board, time: chapter.start + 1, length: length)
                    } else {
                        Theme.placeholder
                    }
                }
                .frame(width: 340, height: 191)
                .clipShape(RoundedRectangle(cornerRadius: 14))
                .overlay(alignment: .bottomLeading) {
                    Text(duration(Int(chapter.start)).ifEmpty("0:00")).font(.system(size: 22, weight: .semibold).monospacedDigit())
                        .padding(.horizontal, 10).padding(.vertical, 3)
                        .background(now ? Theme.red : Color.black.opacity(0.8), in: RoundedRectangle(cornerRadius: 6))
                        .padding(10)
                }
                .overlay { RoundedRectangle(cornerRadius: 14).strokeBorder(Theme.focus, lineWidth: lit ? 6 : 0) }
                .scaleEffect(lit ? 1.06 : 1, anchor: .bottom)
                Text(chapter.title).font(.system(size: 24, weight: .semibold)).lineLimit(2)
                    .frame(width: 340, alignment: .leading)
                    .offset(y: lit ? 10 : 0)
            }
            .animation(.easeOut(duration: 0.18), value: lit)
        }
    }
}

/// a line of the player's panels: an icon, a name and what it's set to, or a ✓
struct PanelRow: View {
    var icon: String? = nil
    let title: String
    var value: String = ""
    var checked: Bool? = nil
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            HStack(spacing: 24) {
                if let checked {
                    Image(systemName: "checkmark").font(.system(size: 28, weight: .bold)).opacity(checked ? 1 : 0).frame(width: 40)
                } else if let icon {
                    Image(systemName: icon).font(.system(size: 30)).frame(width: 40)
                }
                Text(title).font(Fonts.bodyMedium).lineLimit(1)
                Spacer()
                if !value.isEmpty { Text(value).font(Fonts.bodyMedium).dim().lineLimit(1) }
                if checked == nil && !value.isEmpty { Image(systemName: "chevron.right").font(.system(size: 24)).dim() }
            }
            .padding(.horizontal, 32).frame(height: 88)
        }
        .buttonStyle(.glow(radius: 16))
        .padding(.horizontal, 16)
    }
}

struct PanelToggle: View {
    var icon: String
    let title: String
    let on: Bool
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            HStack(spacing: 24) {
                Image(systemName: icon).font(.system(size: 30)).frame(width: 40)
                Text(title).font(Fonts.bodyMedium).lineLimit(1)
                Spacer()
                Switch(on: on)
            }
            .padding(.horizontal, 32).frame(height: 88)
        }
        .buttonStyle(.glow(radius: 16))
        .padding(.horizontal, 16)
    }
}

struct CommentView: View {
    let comment: Comment
    var creator = false

    var body: some View {
        Button {} label: {
            HStack(alignment: .top, spacing: 24) {
                Avatar(url: comment.avatarURL, name: comment.author, size: 64)
                VStack(alignment: .leading, spacing: 6) {
                    HStack(spacing: 10) {
                        if comment.pinned { Image(systemName: "pin.fill").font(.system(size: 20)) }
                        Text(comment.author).font(.system(size: 24, weight: .semibold))
                            .padding(.horizontal, creator ? 10 : 0).padding(.vertical, creator ? 2 : 0)
                            .background(creator ? AnyShapeStyle(Theme.sub.opacity(0.4)) : AnyShapeStyle(Color.clear), in: Capsule())
                        Text(comment.published).font(.system(size: 22)).dim()
                    }
                    .lineLimit(1)
                    Text(comment.text).font(Fonts.bodySmall).lineLimit(8).multilineTextAlignment(.leading)
                    if !comment.likes.isEmpty || comment.byCreator {
                        HStack(spacing: 16) {
                            if !comment.likes.isEmpty { Label(comment.likes, systemImage: "hand.thumbsup").font(.system(size: 22)).dim() }
                            if comment.byCreator { Label("by the creator", systemImage: "heart.fill").font(.system(size: 22)).dim() }
                        }
                    }
                }
                .frame(maxWidth: .infinity, alignment: .leading)
            }
            .padding(20)
        }
        .buttonStyle(.glow(radius: 16))
    }
}
