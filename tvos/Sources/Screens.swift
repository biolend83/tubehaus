import SwiftUI

/// Home, as on YouTube's TV app: topic chips along the top, a big card for the first suggestion,
/// then shelves: Continue watching, Recommended, New from your subscriptions, Shorts, and more
/// recommendations as the remote goes down. Recommendations are worked out on this TV
/// (Recommend.swift). The first time, with nothing to go on, it asks for a few topics.
struct HomeView: View {
    @State private var library = Library.shared
    @State private var subs = SubFeed.shared
    @State private var items: [Video] = []
    @State private var shorts: [Video] = []
    @State private var loading = false
    @State private var error: String?
    @State private var skipped = false
    @State private var chip = "all"
    @State private var topic = Feed()
    @State private var pickRegion = false

    static let regions = ["US", "GB", "CA", "AU", "IN", "DE", "FR", "ES", "IT", "NL", "TR", "BR", "MX", "JP", "KR", "ID"]
    private static let shelfNames = ["Recommended", "More for you", "Keep watching something new", "You might also like", "From the topics you like", "Something different"]

    var body: some View {
        if library.needsTopics && !skipped {
            TopicPicker { skipped = true }
        } else {
            ScrollView {
                LazyVStack(alignment: .leading, spacing: 8) {
                    chips
                    if chip == "all" { allShelves } else { topicGrid }
                }
                .padding(.bottom, 80)
            }
            .scrollClipDisabled()
            .task { if items.isEmpty { await load(refresh: false) } }
            .confirmationDialog("Region", isPresented: $pickRegion) {
                ForEach(Self.regions, id: \.self) { r in Button(regionName(r)) { setRegion(r) } }
                Button("This device") { setRegion("") }
            } message: {
                Text("Which country's YouTube Home and search follow")
            }
        }
    }

    /// the topics this TV likes most, else a few to start with
    private var topics: [String] {
        let liked = library.taste.kw.filter { $0.value >= 1.5 && $0.key.count > 2 }.sorted { $0.value > $1.value }.map { $0.key.capitalized }
        let list = liked.isEmpty ? Array(Recommender.topics.prefix(8)) : Array(liked.prefix(10))
        return list
    }

    private var chips: some View {
        ScrollView(.horizontal) {
            HStack(spacing: 16) {
                Chip("All", selected: chip == "all") { pick("all") }
                Chip("New to you", icon: "safari", selected: chip == "discover") { pick("discover") }
                if !library.subscriptions.isEmpty {
                    Chip("Subscriptions", selected: chip == "subs") { pick("subs") }
                }
                Chip("Live", icon: "dot.radiowaves.left.and.right", selected: chip == "live") { pick("live") }
                ForEach(topics, id: \.self) { t in Chip(t, selected: chip == t) { pick(t) } }
                Chip(regionName(Settings.gl), icon: "globe") { pickRegion = true }
                Chip("Refresh", icon: "arrow.clockwise") { Task { await load(refresh: true) } }
            }
            .padding(.horizontal, Metrics.side).padding(.vertical, 20)
        }
        .scrollClipDisabled()
        .padding(.top, 36)
        .focusSection()
    }

    @ViewBuilder private var allShelves: some View {
        if let first = items.first {
            Hero(video: first).padding(.bottom, 16)
        } else if loading {
            RoundedRectangle(cornerRadius: 28).fill(Theme.placeholder).frame(height: 520).padding(.horizontal, Metrics.side)
        }
        if !library.continueWatching.isEmpty {
            VideoShelf(title: "Continue watching", videos: library.continueWatching.prefix(20).map(\.video), history: true)
        }
        if items.isEmpty {
            if loading {
                ShelfPlaceholder()
                ShelfPlaceholder()
            } else {
                Status(error: error ?? "Nothing to show yet. Search for something you like.") { Task { await load(refresh: true) } }
                    .frame(height: 500)
            }
        } else {
            VideoShelf(title: Self.shelfNames[0], videos: Array(items.dropFirst().prefix(12)))
            if !subs.videos.isEmpty {
                VideoShelf(title: "New from your subscriptions", icon: "play.square.stack", videos: Array(subs.videos.prefix(20)))
            }
            if !Settings.hideShorts && !shorts.isEmpty {
                Shelf("Shorts", icon: "bolt.fill") {
                    ForEach(shorts) { s in ShortTile(video: s) }
                }
            }
            if !library.later.isEmpty {
                VideoShelf(title: "Watch later", icon: "clock", videos: Array(library.later.prefix(20)))
            }
            let rest = Array(items.dropFirst(13))
            ForEach(0..<(rest.count + 11) / 12, id: \.self) { i in
                VideoShelf(title: Self.shelfNames[min(Self.shelfNames.count - 1, i + 1)], videos: Array(rest[(i * 12)..<min(rest.count, i * 12 + 12)]))
                    .onAppear { if i == (rest.count + 11) / 12 - 1 { Task { await more() } } }
            }
            if rest.isEmpty { Color.clear.frame(height: 1).onAppear { Task { await more() } } }
        }
    }

    @ViewBuilder private var topicGrid: some View {
        if topic.items.isEmpty {
            Status(error: topic.loaded ? (topic.error ?? "Nothing to show") : nil) { pick(chip) }.frame(height: 600)
        } else {
            LazyVGrid(columns: Array(repeating: GridItem(.flexible(), spacing: 40, alignment: .top), count: 4), alignment: .leading, spacing: 56) {
                ForEach(topic.items) { item in
                    ItemTile(item: item, width: nil)
                        .onAppear {
                            if topic.items.suffix(8).contains(where: { $0.id == item.id }) { Task { await topic.more(YT.searchMore) } }
                        }
                }
            }
            .padding(.horizontal, Metrics.side).padding(.top, 20)
            .focusSection()
        }
    }

    private func pick(_ c: String) {
        chip = c
        guard c != "all" else { return }
        let f = Feed()
        topic = f
        Task {
            switch c {
            case "discover":
                let got = await Recommender.shared.discover(48)
                f.items = got.map(Item.video)
                f.loaded = true
            case "subs":
                await subs.load()
                f.items = subs.videos.map(Item.video)
                f.loaded = true
            case "live":
                await f.load { try await YT.search("live", filter: .live) }
            default:
                await f.load { try await YT.search(c, filter: .videos) }
            }
        }
    }

    private func setRegion(_ r: String) {
        if r.isEmpty { UserDefaults.standard.removeObject(forKey: "gl") } else { UserDefaults.standard.set(r, forKey: "gl") }
        items = []
        Task { await load(refresh: true) }
    }

    private func load(refresh: Bool) async {
        guard !loading else { return }
        loading = true
        error = nil
        defer { loading = false }
        if refresh { Recommender.shared.clearCache(); items = [] }
        if !library.subscriptions.isEmpty { Task { await subs.load(force: refresh) } }
        if !Settings.hideShorts && (shorts.isEmpty || refresh) {
            Task { shorts = Array(await Recommender.shared.shorts().prefix(16)) }
        }
        let got = await Recommender.shared.home(40)
        items = got
        if got.isEmpty { error = "Couldn't reach YouTube" }
    }

    private func more() async {
        guard !loading, !items.isEmpty, items.count < 400 else { return }
        loading = true
        defer { loading = false }
        let skip = Set(items.map(\.id))
        let got = await Recommender.shared.home(36, skip: skip)
        guard !Task.isCancelled else { return }
        items += got.filter { !skip.contains($0.id) }
    }
}

/// The big card at the top of Home: the video's picture across the page, its title, channel and
/// Play / Watch later.
struct Hero: View {
    let video: Video
    @State private var library = Library.shared

    var body: some View {
        ZStack(alignment: .bottomLeading) {
            Picture(video.pictures, maxPixels: 1920)
                .frame(height: 560)
                .overlay(LinearGradient(colors: [.black.opacity(0.85), .black.opacity(0.35), .clear], startPoint: .leading, endPoint: .trailing))
                .overlay(LinearGradient(colors: [.clear, Theme.bg.opacity(0.9)], startPoint: .center, endPoint: .bottom))
                .clipShape(RoundedRectangle(cornerRadius: 28))
            VStack(alignment: .leading, spacing: 18) {
                HStack(spacing: 14) {
                    Avatar(url: video.avatarURL ?? library.avatar(of: video.channelId), name: video.author, size: 56)
                    Text(video.author).font(Fonts.titleSmall).lineLimit(1)
                }
                Text(video.title).font(.system(size: 56, weight: .heavy)).lineLimit(2).frame(maxWidth: 1100, alignment: .leading)
                Text([video.viewsText, video.publishedText.isEmpty ? ago(video.publishedAt) : video.publishedText, duration(video.lengthSec)]
                        .filter { !$0.isEmpty }.joined(separator: " · "))
                    .font(Fonts.bodyMedium).foregroundStyle(Theme.sub)
                HStack(spacing: 20) {
                    FilledButton(library.progress(video.id) != nil ? "Resume" : "Play", icon: "play.fill") { Nav.shared.open(video) }
                        .firstFocus()
                    FilledButton(library.isLater(video.id) ? "Saved" : "Watch later", icon: library.isLater(video.id) ? "checkmark" : "clock") {
                        library.toggleLater(video)
                    }
                    if !video.channelId.isEmpty {
                        FilledButton("Channel", icon: "person.crop.square") { Nav.shared.push(.channel(video.channelId)) }
                    }
                }
                .padding(.top, 8)
                .focusSection()
            }
            .padding(56)
        }
        .padding(.horizontal, Metrics.side)
    }
}

/// "Good morning" until noon, "Good afternoon" until six, then "Good evening"
func greeting(_ now: Date = Date()) -> String {
    let h = Calendar.current.component(.hour, from: now)
    return h >= 5 && h < 12 ? "Good morning" : h >= 12 && h < 18 ? "Good afternoon" : "Good evening"
}

func regionName(_ code: String) -> String {
    Locale.current.localizedString(forRegionCode: code) ?? code
}

/// a few topics give the recommendations something to begin with
struct TopicPicker: View {
    var done: (() -> Void)? = nil
    @State private var picked: [String] = []

    var body: some View {
        ScrollView {
            VStack(spacing: 0) {
                HouseMark().frame(width: 120, height: 104).padding(.bottom, 32)
                Text("What do you like to watch?").font(Fonts.hero).multilineTextAlignment(.center)
                Text("Pick a few topics to start. Your recommendations are worked out on this TV, from what you watch - nothing leaves it.")
                    .font(Fonts.bodyMedium).foregroundStyle(Theme.sub).multilineTextAlignment(.center)
                    .padding(.top, 16).padding(.bottom, 48)
                FlowLayout(spacing: 20) {
                    ForEach(Recommender.topics, id: \.self) { t in
                        Chip(t, icon: picked.contains(t) ? "checkmark" : nil, selected: picked.contains(t)) {
                            if let i = picked.firstIndex(of: t) { picked.remove(at: i) } else { picked.append(t) }
                        }
                        .firstFocus(t == Recommender.topics.first)
                    }
                }
                .focusSection()
                HStack(spacing: 24) {
                    FilledButton(picked.isEmpty ? "Pick at least one" : "Continue (\(picked.count))", icon: "arrow.right", enabled: !picked.isEmpty) {
                        Library.shared.pickTopics(picked)
                        finish()
                    }
                    TextButton("Import subscriptions", icon: "qrcode") { Nav.shared.push(.importSubs) }
                    if done != nil { TextButton("Skip") { finish() } }
                }
                .padding(.top, 56)
                .focusSection()
            }
            .frame(maxWidth: 1400)
            .padding(64)
            .frame(maxWidth: .infinity)
        }
    }

    private func finish() {
        if let done { done() } else { Nav.shared.back() }
    }
}

/// Subscriptions, as on YouTube's TV app: the channels along the top (All first; one picked shows
/// only its videos), then everything they uploaded lately, newest first, four across.
struct SubscriptionsView: View {
    @State private var library = Library.shared
    @State private var feed = SubFeed.shared
    @State private var shown = 40
    @State private var only: String?

    var body: some View {
        Group {
            if library.subscriptions.isEmpty {
                VStack(spacing: 32) {
                    Image(systemName: "play.square.stack").font(.system(size: 96)).foregroundStyle(Theme.sub)
                    Text("No subscriptions yet").font(Fonts.titleLarge)
                    Text("Subscribe to channels, or bring your subscriptions over from another app with your phone.")
                        .font(Fonts.bodyMedium).foregroundStyle(Theme.sub).multilineTextAlignment(.center)
                    FilledButton("Import subscriptions", icon: "qrcode") { Nav.shared.push(.importSubs) }
                        .firstFocus()
                    Text("From YouTube (Google Takeout), NewPipe, FreeTube, Invidious, TubeHaus or OPML")
                        .font(Fonts.bodySmall).foregroundStyle(Theme.sub)
                }
                .frame(maxWidth: 1200)
                .frame(maxWidth: .infinity, maxHeight: .infinity)
            } else {
                ItemGrid(items: shownVideos.map(Item.video), more: { shown += 40 }) {
                    header
                    if shownVideos.isEmpty {
                        Status(error: feed.loading ? nil : "Nothing new from your channels") {
                            Task { await feed.load(force: true) }
                        }
                        .frame(height: 500)
                    }
                }
            }
        }
        .task(id: library.subscriptions.count) { await feed.load() }
    }

    private var shownVideos: [Video] {
        let all = only.map { id in feed.videos.filter { $0.channelId == id } } ?? feed.videos
        return Array(all.prefix(shown))
    }

    private var header: some View {
        VStack(alignment: .leading, spacing: 0) {
            ScrollView(.horizontal) {
                LazyHStack(alignment: .top, spacing: 24) {
                    Button { only = nil } label: { AvatarFace(letter: "All", url: nil, name: "All", on: only == nil) }
                        .buttonStyle(TileStyle())
                        .firstFocus()
                    ForEach(library.subscriptions) { c in
                        Button { only = only == c.id ? nil : c.id } label: { AvatarFace(letter: nil, url: c.avatarURL, name: c.name, on: only == c.id) }
                            .buttonStyle(TileStyle())
                            .contextMenu {
                                Button("Go to channel", systemImage: "person.crop.square") { Nav.shared.push(.channel(c.id)) }
                                Button("Unsubscribe", systemImage: "xmark", role: .destructive) { library.toggleSubscription(c) }
                            }
                    }
                    Button { Nav.shared.push(.manageSubs) } label: { AvatarFace(letter: "⋯", url: nil, name: "Manage", on: false) }
                        .buttonStyle(TileStyle())
                }
                .padding(.horizontal, Metrics.side).padding(.vertical, 24)
            }
            .scrollClipDisabled()
            .padding(.top, 36)
            .focusSection()
            if let id = only, let c = library.subscriptions.first(where: { $0.id == id }) {
                HStack(spacing: 24) {
                    Text(c.name).font(Fonts.titleLarge)
                    Spacer()
                    TextButton("Go to channel", icon: "chevron.right") { Nav.shared.push(.channel(c.id)) }
                }
                .padding(.horizontal, Metrics.side)
                .focusSection()
            }
        }
    }

    private struct AvatarFace: View {
        let letter: String?
        let url: URL?
        let name: String
        let on: Bool
        @Environment(\.lit) private var lit

        var body: some View {
            VStack(spacing: 12) {
                ZStack {
                    if let letter {
                        Circle().fill(on ? Theme.text : Theme.chip)
                        Text(letter).font(.system(size: 30, weight: .bold)).foregroundStyle(on ? Theme.onFocus : Theme.text)
                    } else {
                        Avatar(url: url, name: name, size: 120)
                    }
                }
                .frame(width: 120, height: 120)
                .overlay(Circle().strokeBorder(lit ? Theme.focus : on ? Theme.red : .clear, lineWidth: 6))
                .scaleEffect(lit ? 1.1 : 1)
                Text(name).font(.system(size: 22, weight: on ? .bold : .medium)).lineLimit(1)
                    .foregroundStyle(lit || on ? Theme.text : Theme.sub)
                    .frame(width: 150)
            }
            .animation(.easeOut(duration: 0.15), value: lit)
        }
    }
}

/// every subscribed channel, with ✕ to leave it
struct ManageSubsView: View {
    @State private var library = Library.shared
    @State private var confirm = false

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 0) {
                HStack {
                    PageTitle(title: "Subscriptions", sub: "\(library.subscriptions.count) channels")
                    Spacer()
                    TextButton("Unsubscribe from all", color: Theme.error) { confirm = true }
                        .disabled(library.subscriptions.isEmpty)
                        .padding(.trailing, Metrics.side)
                }
                LazyVGrid(columns: Array(repeating: GridItem(.flexible(), spacing: 32, alignment: .top), count: 6), spacing: 48) {
                    ForEach(library.subscriptions) { c in
                        VStack(spacing: 16) {
                            ChannelTile(channel: c, size: 180)
                            TextButton("Unsubscribe", color: Theme.sub) { library.toggleSubscription(c) }
                        }
                    }
                }
                .padding(.horizontal, Metrics.side).padding(.vertical, 32)
            }
        }
        .confirmationDialog("Unsubscribe from all \(library.subscriptions.count) channels?", isPresented: $confirm, titleVisibility: .visible) {
            Button("Unsubscribe from all", role: .destructive) {
                for c in library.subscriptions { library.toggleSubscription(c) }
            }
        }
    }
}

/// You: who's using the TV, then History, Watch later and Liked videos as shelves, then your data.
struct LibraryView: View {
    @State private var library = Library.shared

    var body: some View {
        ScrollView {
            LazyVStack(alignment: .leading, spacing: 8) {
                HStack(spacing: 32) {
                    let me = People.name(People.everyone.first { People.id($0) == People.current } ?? "")
                    Text(String(me.prefix(1)).uppercased().ifEmpty("?")).font(.system(size: 64, weight: .bold))
                        .foregroundStyle(Theme.onFocus)
                        .frame(width: 140, height: 140).background(Circle().fill(Theme.teal))
                    VStack(alignment: .leading, spacing: 8) {
                        Text(me.isEmpty ? "You" : me).font(Fonts.hero)
                        Text("\(library.subscriptions.count) subscriptions · \(library.history.count) watched · \(library.liked.count) liked")
                            .font(Fonts.bodyMedium).foregroundStyle(Theme.sub)
                    }
                }
                .padding(.horizontal, Metrics.side).padding(.top, 56).padding(.bottom, 24)

                shelfHeader("History", count: library.history.count) { Nav.shared.push(.history) }
                if library.history.isEmpty {
                    empty("Videos you watch show up here")
                } else {
                    shelf(library.history.prefix(24).map(\.video), history: true)
                }
                shelfHeader("Watch later", count: library.later.count, play: library.later) { Nav.shared.push(.later) }
                if library.later.isEmpty {
                    empty("Hold OK on a video and choose Save to Watch later")
                } else {
                    shelf(Array(library.later.prefix(24)))
                }
                shelfHeader("Liked videos", count: library.liked.count, play: library.liked) { Nav.shared.push(.liked) }
                if library.liked.isEmpty {
                    empty("Videos you like show up here")
                } else {
                    shelf(Array(library.liked.prefix(24)))
                }
                SectionTitle("Your data")
                HStack(spacing: 32) {
                    DataTile(icon: "person.2", title: "Subscriptions", sub: "\(library.subscriptions.count) channels") { Nav.shared.push(.manageSubs) }
                    DataTile(icon: "sparkles", title: "What TubeHaus learned", sub: "What Home is based on") { Nav.shared.push(.learned) }
                    DataTile(icon: "square.and.arrow.down", title: "Import", sub: "From your phone") { Nav.shared.push(.importSubs) }
                    DataTile(icon: "tag", title: "Pick topics", sub: "Tell Home what you like") { Nav.shared.push(.topics) }
                }
                .padding(.horizontal, Metrics.side).padding(.vertical, 24)
                .focusSection()
            }
            .padding(.bottom, 80)
        }
        .scrollClipDisabled()
    }

    private func shelfHeader(_ title: String, count: Int, play: [Video] = [], all: @escaping () -> Void) -> some View {
        HStack(spacing: 20) {
            Text(title).font(Fonts.shelf)
            Text("\(count)").font(Fonts.bodyMedium).foregroundStyle(Theme.sub)
            Spacer()
            if !play.isEmpty {
                TextButton("Play all", icon: "play.fill") { PlayerModel.shared.playAll(play) }
            }
            if count > 0 { TextButton("See all", icon: "chevron.right", action: all) }
        }
        .padding(.horizontal, Metrics.side).padding(.top, 24)
        .focusSection()
    }

    private func shelf(_ vids: [Video], history: Bool = false) -> some View {
        Shelf("") {
            ForEach(vids) { v in VideoTile(video: v, history: history) }
        }
    }

    private func empty(_ t: String) -> some View {
        Text(t).font(Fonts.bodyMedium).foregroundStyle(Theme.sub)
            .padding(.horizontal, Metrics.side).padding(.vertical, 28)
    }
}

/// a big square button of You's "Your data"
struct DataTile: View {
    let icon: String
    let title: String
    let sub: String
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            VStack(alignment: .leading, spacing: 12) {
                Image(systemName: icon).font(.system(size: 44))
                Spacer()
                Text(title).font(Fonts.titleSmall).lineLimit(2)
                Text(sub).font(Fonts.bodySmall).dim().lineLimit(1)
            }
            .padding(32)
            .frame(width: 360, height: 240, alignment: .leading)
        }
        .buttonStyle(Glow(shape: RoundedRectangle(cornerRadius: 24), fill: Theme.chip))
    }
}

/// everything watched, newest first, four across
struct HistoryView: View {
    @State private var library = Library.shared
    @State private var confirm = false

    var body: some View {
        ItemGrid(items: library.history.map { Item.video($0.video) }) {
            HStack {
                PageTitle(title: "History", sub: "\(library.history.count) videos")
                Spacer()
                TextButton("Clear all", icon: "trash", color: Theme.error) { confirm = true }
                    .disabled(library.history.isEmpty)
                    .padding(.trailing, Metrics.side)
                    .firstFocus()
            }
            .focusSection()
            if library.history.isEmpty {
                Text("Videos you watch show up here").font(Fonts.bodyMedium).foregroundStyle(Theme.sub).padding(Metrics.side)
            }
        }
        .confirmationDialog("Clear watch history?", isPresented: $confirm, titleVisibility: .visible) {
            Button("Clear", role: .destructive) { library.clearHistory() }
        }
    }
}

/// Watch later or Liked videos, laid out as a playlist
struct LocalList: View {
    enum Kind { case later, liked }
    let kind: Kind
    @State private var library = Library.shared

    var body: some View {
        let list = kind == .later ? library.later : library.liked
        HStack(alignment: .top, spacing: 0) {
            VStack(alignment: .leading, spacing: 24) {
                if let first = list.first {
                    Thumb(urls: first.pictures, radius: 24)
                } else {
                    RoundedRectangle(cornerRadius: 24).fill(Theme.chip).aspectRatio(16 / 9, contentMode: .fit)
                        .overlay(Image(systemName: kind == .later ? "clock" : "hand.thumbsup").font(.system(size: 80)).foregroundStyle(Theme.sub))
                }
                Text(kind == .later ? "Watch later" : "Liked videos").font(Fonts.titleLarge)
                Text("\(list.count) videos").font(Fonts.bodyMedium).foregroundStyle(Theme.sub)
                if !list.isEmpty {
                    VStack(alignment: .leading, spacing: 16) {
                        FilledButton("Play all", icon: "play.fill") { PlayerModel.shared.playAll(list) }.firstFocus()
                        FilledButton("Shuffle", icon: "shuffle") { PlayerModel.shared.playAll(list, shuffled: true) }
                    }
                    .focusSection()
                }
                Spacer()
            }
            .frame(width: 620)
            .padding(.leading, Metrics.side).padding(.top, 64)
            if list.isEmpty {
                Text(kind == .later ? "Hold OK on a video and choose Save to Watch later" : "Videos you like show up here")
                    .font(Fonts.bodyMedium).foregroundStyle(Theme.sub).padding(64)
            } else {
                ItemList(items: list.map(Item.video), numbered: true) { EmptyView() }
                    .focusSection()
            }
        }
    }
}

/// What Home's recommendations are based on: the channels and words scored from what you
/// watched and liked, each one removable, and Start over.
struct LearnedView: View {
    @State private var library = Library.shared
    @State private var confirm = false

    var body: some View {
        let t = library.taste
        let channels = t.ch.sorted { $0.value > $1.value }
        let words = t.kw.sorted { $0.value > $1.value }
        ScrollView {
            LazyVStack(alignment: .leading, spacing: 4) {
                Text("What TubeHaus learned about you").font(Fonts.titleLarge)
                Text("Home is worked out on this TV from these scores: what you watch, how much of it, what you like and what you hide. Remove anything that's wrong.")
                    .font(Fonts.bodyMedium).foregroundStyle(Theme.sub).padding(.bottom, 24)
                if channels.isEmpty && words.isEmpty {
                    Text("Nothing yet. Watch a few videos.").font(Fonts.bodyMedium).foregroundStyle(Theme.sub)
                }
                if !channels.isEmpty {
                    Text("Channels").font(Fonts.titleSmall).foregroundStyle(Theme.blue).padding(.top, 16)
                    ForEach(channels, id: \.key) { e in
                        scoreRow(name(e.key), e.value) { library.forgetChannel(e.key) }
                    }
                }
                if !words.isEmpty {
                    Text("Topics").font(Fonts.titleSmall).foregroundStyle(Theme.blue).padding(.top, 24)
                    ForEach(words.prefix(80), id: \.key) { e in
                        scoreRow(e.key, e.value) { library.forgetWord(e.key) }
                    }
                }
                TextButton("Start over", color: Theme.error) { confirm = true }
                    .padding(.top, 32)
            }
            .padding(Metrics.side)
        }
        .confirmationDialog("Forget everything TubeHaus learned about what you like?", isPresented: $confirm, titleVisibility: .visible) {
            Button("Start over", role: .destructive) {
                library.resetTaste()
                Recommender.shared.clearCache()
            }
        }
    }

    private func name(_ id: String) -> String {
        library.names[id] ?? library.subscriptions.first { $0.id == id }?.name ?? id
    }

    private func scoreRow(_ label: String, _ score: Double, remove: @escaping () -> Void) -> some View {
        HStack(spacing: 24) {
            Text(label).font(Fonts.bodyMedium).lineLimit(1)
            Spacer()
            Text(score > 0 ? String(format: "+%.1f", score) : String(format: "%.1f", score))
                .font(Fonts.bodyMedium.monospacedDigit()).foregroundStyle(score > 0 ? Theme.teal : Theme.error)
            IconButton(icon: "xmark", size: 32, action: remove)
        }
        .padding(.horizontal, 16)
    }
}

/// Settings, as on YouTube's TV app: the groups down the left (the remote on one shows it), the
/// settings of that group on the right.
struct SettingsView: View {
    @State private var library = Library.shared
    @State private var group: Pane = .general
    @State private var hideShorts = Settings.hideShorts
    @State private var hideComments = Settings.hideComments
    @State private var hideRelated = Settings.hideRelated
    @State private var autoplay = Settings.autoplayNext
    @State private var originalAudio = Settings.originalAudio
    @State private var soundNorm = Settings.soundNorm
    @State private var sponsorBlock = Settings.sponsorBlock
    @State private var autoSkip = Settings.sponsorBlockCategories
    @State private var glance = Settings.glance
    @State private var captions = Settings.captions
    @State private var captionSize = Settings.captionSize
    @State private var quality = Settings.quality
    @State private var speed = Settings.speed
    @State private var countdown = Settings.countdown
    @State private var seekStep = Settings.seekStep
    @State private var language = UserDefaults.standard.string(forKey: "hl") ?? ""
    @State private var region = UserDefaults.standard.string(forKey: "gl") ?? ""
    @State private var engine = ""
    @State private var dialog: Dialog?
    @State private var server = Server.shared
    @State private var sheet: Sheet?
    @State private var pinFor: String?
    @FocusState private var focusedGroup: Pane?

    enum Pane: String, CaseIterable, Identifiable {
        case general, playback, subtitles, sponsorBlock, focus, people, server, data, about
        var id: String { rawValue }

        var title: String {
            switch self {
            case .general: return "General"
            case .playback: return "Playback"
            case .subtitles: return "Subtitles"
            case .sponsorBlock: return "SponsorBlock"
            case .focus: return "Focus"
            case .people: return "People on this TV"
            case .server: return "TubeHaus server"
            case .data: return "Your data"
            case .about: return "About"
            }
        }

        var icon: String {
            switch self {
            case .general: return "globe"
            case .playback: return "play.rectangle"
            case .subtitles: return "captions.bubble"
            case .sponsorBlock: return "forward.end"
            case .focus: return "eye.slash"
            case .people: return "person.2"
            case .server: return "server.rack"
            case .data: return "tray.full"
            case .about: return "info.circle"
            }
        }
    }

    enum Sheet: String, Identifiable {
        case server, addPerson, pin
        var id: String { rawValue }
    }

    enum Dialog: String, Identifiable {
        case language, region, captions, captionSize, clear, people, disconnect, quality, speed, countdown, seekStep
        var id: String { rawValue }
    }

    private static let languages = ["en", "ko", "ja", "zh", "es", "fr", "de", "pt", "ru", "tr", "it", "id", "vi", "ar", "hi"]

    var body: some View {
        HStack(alignment: .top, spacing: 0) {
            VStack(alignment: .leading, spacing: 6) {
                Text("Settings").font(Fonts.titleLarge).padding(.leading, 24).padding(.top, 56).padding(.bottom, 24)
                ForEach(Pane.allCases) { g in
                    Button { group = g } label: {
                        HStack(spacing: 24) {
                            Image(systemName: g.icon).font(.system(size: 30)).frame(width: 44)
                            Text(g.title).font(Fonts.bodyMedium).lineLimit(1)
                            Spacer()
                        }
                        .padding(.horizontal, 24).frame(height: 80)
                        .background(RoundedRectangle(cornerRadius: 16).fill(group == g && focusedGroup == nil ? Theme.chip : Color.clear))
                    }
                    .buttonStyle(.glow(radius: 16))
                    .focused($focusedGroup, equals: g)
                }
                Spacer()
            }
            .frame(width: 560)
            .padding(.leading, Metrics.side - 24)
            .focusSection()
            .onChange(of: focusedGroup) { _, g in if let g { group = g } }
            .task {
                try? await Task.sleep(nanoseconds: 150_000_000)
                if focusedGroup == nil { focusedGroup = .general }
            }

            ScrollView {
                LazyVStack(alignment: .leading, spacing: 0) {
                    Text(group.title).font(Fonts.titleMedium).foregroundStyle(Theme.sub)
                        .padding(.horizontal, 32).padding(.top, 72).padding(.bottom, 16)
                    rows
                }
                .frame(maxWidth: 1100, alignment: .leading)
                .padding(.horizontal, 32).padding(.bottom, 64)
            }
            .focusSection()
        }
        .task {
            _ = try? await Engine.shared.call("suggestions", [""])   // loads the engine, so its version is known
            let v = await Engine.shared.version, s = await Engine.shared.source
            engine = "\(v) (\(s))"
        }
        .confirmationDialog(title(dialog), isPresented: Binding(get: { dialog != nil }, set: { if !$0 { dialog = nil } }),
                            titleVisibility: .visible, presenting: dialog) { d in dialogButtons(d) }
        .sheet(item: $sheet) { s in
            switch s {
            case .server: ConnectServer()
            case .addPerson: AddPerson {}
            case .pin: SetPin()
            }
        }
        .sheet(item: Binding(get: { pinFor.map(PinFor.init) }, set: { pinFor = $0?.id })) { p in
            PinEntry(title: "PIN for \(People.name(People.everyone.first { People.id($0) == p.id } ?? ""))") { pin in
                guard People.pinOK(p.id, pin) else { return false }
                pinFor = nil
                People.switchTo(p.id)
                return true
            }
        }
    }

    @ViewBuilder private var rows: some View {
        switch group {
        case .general:
            item("Language", language.isEmpty ? "\(name(language: Settings.hl)) (from this device)" : name(language: language)) { dialog = .language }
            item("Region", region.isEmpty ? "\(regionName(Settings.gl)) (from this device)" : regionName(region)) { dialog = .region }
        case .playback:
            toggle("Autoplay", "Up next counts down and plays the next video when one ends", $autoplay) { Settings.autoplayNext = $0 }
            item("Up next countdown", "\(countdown) seconds") { dialog = .countdown }
            item("Quality", PlayerModel.qualityName(quality) + (quality == 0 ? " (the best the connection allows)" : " at most")) { dialog = .quality }
            item("Speed", PlayerModel.speedName(speed)) { dialog = .speed }
            item("Left and right skip", "\(seekStep) seconds") { dialog = .seekStep }
            toggle("Loudness matching", "Loud videos are turned down to the level YouTube aims for", $soundNorm) { Settings.soundNorm = $0 }
            toggle("Original audio", "Start videos in their original language when available", $originalAudio) { Settings.originalAudio = $0 }
            toggle("Video at a glance", "Key words and moments in a video's description, made on this TV from its captions", $glance) { Settings.glance = $0 }
        case .subtitles:
            item("Subtitles", captions.isEmpty ? "Off" : name(language: captions)) { dialog = .captions }
            item("Subtitle size", ["Small", "Medium", "Large"][max(0, min(2, captionSize))]) { dialog = .captionSize }
        case .sponsorBlock:
            toggle("SponsorBlock", "Only the first 4 characters of a video ID hash are sent to SponsorBlock.", $sponsorBlock) { Settings.sponsorBlock = $0 }
            Text("Auto-skip is on for the categories turned on here. Other parts offer a Skip button.")
                .font(Fonts.bodySmall).foregroundStyle(Theme.sub)
                .padding(.horizontal, 32).padding(.vertical, 12)
            ForEach(SponsorBlock.Category.skippable, id: \.self) { category in
                let enabled = Binding(get: { autoSkip.contains(category) }, set: { on in
                    if on { autoSkip.insert(category) } else { autoSkip.remove(category) }
                })
                toggle(category.label, "", enabled) { _ in Settings.sponsorBlockCategories = autoSkip }
                    .disabled(!sponsorBlock)
                    .opacity(sponsorBlock ? 1 : 0.4)
            }
        case .focus:
            toggle("Hide comments", "No comments in the player", $hideComments) { Settings.hideComments = $0 }
            toggle("Hide Shorts", "No Shorts in the guide, no Shorts in lists", $hideShorts) {
                Settings.hideShorts = $0
                Recommender.shared.clearCache()
            }
            toggle("Hide related videos", "Nothing suggested in the player or when a video ends", $hideRelated) { Settings.hideRelated = $0 }
        case .people:
            item("Using TubeHaus now: \(People.name(People.everyone.first { People.id($0) == People.current } ?? ""))",
                 People.everyone.count > 1 ? "Switch to someone else, or remove someone" : "Everyone can have their own subscriptions, history and recommendations") { dialog = .people }
            item("Add a person", "They start with an empty library and their own TubeHaus server login") { sheet = .addPerson }
            item(People.hasPin(People.current) ? "PIN: on" : "PIN: off", "Asked before switching to you, so others can't open your library") { sheet = .pin }
        case .server:
            if server.connected {
                item("Connected to \(server.base.replacingOccurrences(of: "https://", with: ""))",
                     server.problem.map(Server.say) ?? (server.syncedAt.map { "Synced at \($0.formatted(date: .omitted, time: .shortened))" } ?? "Syncing…")) {
                    Task { await server.sync() }
                }
                item("Disconnect", "This TV keeps its copy of the library") { dialog = .disconnect }
            } else {
                item("Connect to a TubeHaus server", "Your library shared with your server and your other devices, videos played through the server, and Music") { sheet = .server }
            }
        case .data:
            item("What TubeHaus learned about you", "See and change what your recommendations are based on") { Nav.shared.push(.learned) }
            item("Pick topics", "Tell Home what you like to watch") { Nav.shared.push(.topics) }
            item("Import", "Subscriptions from YouTube (Google Takeout), NewPipe, FreeTube, Invidious, TubeHaus or OPML, sent from your phone") { Nav.shared.push(.importSubs) }
            item("Manage subscriptions", "\(library.subscriptions.count) channels. Remove channels, or all of them") { Nav.shared.push(.manageSubs) }
            item("Clear watch history", "\(library.history.count) videos") { dialog = .clear }
        case .about:
            item("TubeHaus \(Bundle.main.object(forInfoDictionaryKey: "CFBundleShortVersionString") as? String ?? "")",
                 "Everything happens on this device: it talks to YouTube directly, with no TubeHaus server, no account and no tracking. Your library and recommendations never leave it.") {}
            item("YouTube engine", engine) {}
            item("Source code and updates", "github.com/biolend83/tubehaus") {}
            item("Disclaimer", "TubeHaus is independent, free, open-source software, not affiliated with YouTube or Google. It doesn't host any videos and comes with no warranty.") {}
        }
    }

    @ViewBuilder private func dialogButtons(_ d: Dialog) -> some View {
        switch d {
        case .language:
            Button("From this device") { save("hl", ""); language = "" }
            ForEach(Self.languages, id: \.self) { l in Button(name(language: l)) { save("hl", l); language = l } }
        case .region:
            Button("From this device") { save("gl", ""); region = "" }
            ForEach(HomeView.regions, id: \.self) { r in Button(regionName(r)) { save("gl", r); region = r } }
        case .captions:
            Button("Off") { captions = ""; Settings.captions = "" }
            ForEach(Self.languages, id: \.self) { l in Button(name(language: l)) { captions = l; Settings.captions = l } }
        case .captionSize:
            ForEach(0..<3, id: \.self) { i in
                Button(["Small", "Medium", "Large"][i]) { captionSize = i; PlayerModel.shared.setCaptionSize(i) }
            }
        case .quality:
            ForEach(PlayerModel.qualities, id: \.self) { q in Button(PlayerModel.qualityName(q)) { quality = q; PlayerModel.shared.setQuality(q) } }
        case .speed:
            ForEach(PlayerModel.speeds, id: \.self) { r in Button(PlayerModel.speedName(r)) { speed = r; PlayerModel.shared.setRate(r) } }
        case .countdown:
            ForEach([3, 5, 8, 10, 15, 20], id: \.self) { s in Button("\(s) seconds") { countdown = s; Settings.countdown = s } }
        case .seekStep:
            ForEach([5, 10, 15], id: \.self) { s in Button("\(s) seconds") { seekStep = s; Settings.seekStep = s } }
        case .clear:
            Button("Clear", role: .destructive) { library.clearHistory() }
        case .people:
            ForEach(People.everyone, id: \.self) { e in
                let id = People.id(e)
                if id != People.current {
                    Button("Switch to \(People.name(e))") { switchPerson(id) }
                }
            }
            ForEach(People.everyone, id: \.self) { e in
                let id = People.id(e)
                if id != People.current && !People.hasPin(id) {
                    Button("Remove \(People.name(e))", role: .destructive) { People.remove(id) }
                }
            }
        case .disconnect:
            Button("Disconnect", role: .destructive) { server.disconnect() }
        }
    }

    private struct PinFor: Identifiable { let id: String }

    private func switchPerson(_ id: String) {
        if People.hasPin(id) { pinFor = id } else { People.switchTo(id) }
    }

    private func title(_ d: Dialog?) -> String {
        switch d {
        case .people: return "People on this TV"
        case .disconnect: return "Disconnect from the server?"
        case .language: return "Language"
        case .region: return "Region"
        case .captions: return "Subtitles"
        case .captionSize: return "Subtitle size"
        case .clear: return "Clear watch history?"
        case .quality: return "Quality"
        case .speed: return "Speed"
        case .countdown: return "Up next countdown"
        case .seekStep: return "Left and right skip"
        case nil: return ""
        }
    }

    private func item(_ title: String, _ sub: String, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            VStack(alignment: .leading, spacing: 6) {
                Text(title).font(Fonts.bodyMedium)
                if !sub.isEmpty { Text(sub).font(Fonts.bodySmall).dim().multilineTextAlignment(.leading) }
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            .padding(.horizontal, 32).padding(.vertical, 24)
        }
        .buttonStyle(.glow(radius: 16))
    }

    private func toggle(_ title: String, _ sub: String, _ on: Binding<Bool>, set: @escaping (Bool) -> Void) -> some View {
        Button {
            on.wrappedValue.toggle()
            set(on.wrappedValue)
        } label: {
            HStack(spacing: 32) {
                VStack(alignment: .leading, spacing: 6) {
                    Text(title).font(Fonts.bodyMedium)
                    if !sub.isEmpty { Text(sub).font(Fonts.bodySmall).dim() }
                }
                .frame(maxWidth: .infinity, alignment: .leading)
                Switch(on: on.wrappedValue)
            }
            .padding(.horizontal, 32).padding(.vertical, 20)
        }
        .buttonStyle(.glow(radius: 16))
    }

    private func save(_ key: String, _ value: String) {
        if value.isEmpty { UserDefaults.standard.removeObject(forKey: key) } else { UserDefaults.standard.set(value, forKey: key) }
        Recommender.shared.clearCache()
    }

    private func name(language: String) -> String {
        Locale(identifier: language).localizedString(forLanguageCode: language)?.capitalized(with: Locale(identifier: language)) ?? language
    }
}
