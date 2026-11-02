import SwiftUI

/// Home (HomeScreen.kt): the region and Discover chips and Refresh, then recommendations worked
/// out on this TV (Recommend.swift) as big cards two across. The first time, with nothing to go
/// on, it asks for a few topics.
struct HomeView: View {
    @State private var library = Library.shared
    @State private var items: [Video] = []
    @State private var loading = false
    @State private var error: String?
    @State private var skipped = false
    @State private var discover = false
    @State private var pickRegion = false
    @State private var region = UserDefaults.standard.string(forKey: "gl") ?? ""

    static let regions = ["US", "GB", "CA", "AU", "IN", "DE", "FR", "ES", "IT", "NL", "TR", "BR", "MX", "JP", "KR", "ID"]

    var body: some View {
        if library.needsTopics && !skipped {
            TopicPicker { skipped = true }
        } else {
            ItemGrid(items: items.map(Item.video), more: { await more() }, focusFirst: true) {
                VStack(alignment: .leading, spacing: 12) {
                    HStack(spacing: 16) {
                        Chip(regionName(Settings.gl), icon: "globe") { pickRegion = true }
                        Chip("Discover", icon: "safari", selected: discover) {
                            discover.toggle()
                            items = []
                            Task { await load(refresh: false) }
                        }
                        Spacer()
                        TextButton("Refresh") { Task { await load(refresh: true) } }
                    }
                    if discover {
                        Text("Videos from topics you don't usually watch. Your likes aren't used here.")
                            .font(Fonts.bodySmall).foregroundStyle(Theme.sub)
                    }
                }
                .padding(.horizontal, 32).padding(.top, 28)
                .focusSection()
                if items.isEmpty {
                    Status(error: loading ? nil : (error ?? "Nothing to show yet. Search for something you like.")) {
                        Task { await load(refresh: true) }
                    }
                    .frame(height: 600)
                }
            }
            .task { if items.isEmpty { await load(refresh: false) } }
            .confirmationDialog("Region", isPresented: $pickRegion) {
                ForEach(Self.regions, id: \.self) { r in
                    Button(regionName(r)) { setRegion(r) }
                }
                Button("This device") { setRegion("") }
            } message: {
                Text("Which country's YouTube Home and search follow")
            }
        }
    }

    private func setRegion(_ r: String) {
        if r.isEmpty { UserDefaults.standard.removeObject(forKey: "gl") } else { UserDefaults.standard.set(r, forKey: "gl") }
        region = r
        items = []
        Task { await load(refresh: true) }
    }

    private func load(refresh: Bool) async {
        guard !loading else { return }
        loading = true
        error = nil
        defer { loading = false }
        if refresh { Recommender.shared.clearCache(); items = [] }
        let got: [Video]
        if discover {
            got = await Recommender.shared.discover(40)
        } else {
            if !library.subscriptions.isEmpty { await SubFeed.shared.load(force: refresh) }
            got = await Recommender.shared.home(40)
        }
        items = got
        if got.isEmpty { error = "Couldn't reach YouTube" }
    }

    private func more() async {
        guard !loading, !items.isEmpty else { return }
        loading = true
        defer { loading = false }
        let skip = Set(items.map(\.id))
        let got: [Video]
        if discover { got = await Recommender.shared.discover(40, skip: skip) } else { got = await Recommender.shared.home(40, skip: skip) }
        guard !Task.isCancelled else { return }
        items += got.filter { !skip.contains($0.id) }
    }
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
                Text("What do you like to watch?").font(Fonts.titleLarge).multilineTextAlignment(.center)
                Text("Pick a few topics to start. Your recommendations are worked out on this device, from what you watch - nothing leaves it.")
                    .font(Fonts.bodyMedium).foregroundStyle(Theme.sub).multilineTextAlignment(.center)
                    .padding(.top, 16).padding(.bottom, 40)
                FlowLayout(spacing: 16) {
                    ForEach(Recommender.topics, id: \.self) { t in
                        Chip(t, selected: picked.contains(t), filter: true) {
                            if let i = picked.firstIndex(of: t) { picked.remove(at: i) } else { picked.append(t) }
                        }
                    }
                }
                .focusSection()
                FilledButton("Continue", enabled: !picked.isEmpty) {
                    Library.shared.pickTopics(picked)
                    finish()
                }
                .padding(.top, 48)
                TextButton("Import subscriptions from another app") { Nav.shared.push(.importSubs) }
                    .padding(.top, 16)
            }
            .frame(maxWidth: 1280)
            .padding(48)
            .frame(maxWidth: .infinity)
        }
    }

    private func finish() {
        if let done { done() } else { Nav.shared.back() }
    }
}

/// Search (SearchScreen.kt): the box, then recent searches or suggestions, then the results as a
/// list (or cards) with All / Videos / Channels / Playlists. A pasted link or @handle opens it.
struct SearchView: View {
    let initial: String
    @State private var query = ""
    @State private var searched = ""
    @State private var suggestions: [String] = []
    @State private var feed = Feed()
    @State private var filter: YT.Filter = .all
    @State private var grid = false
    @State private var recent: [String] = UserDefaults.standard.stringArray(forKey: "searches") ?? []
    @State private var running: Task<Void, Never>?
    @FocusState private var fieldFocused: Bool

    private static let filters: [(YT.Filter, String)] = [(.all, "All"), (.videos, "Videos"), (.channels, "Channels"), (.playlists, "Playlists")]

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            HStack(spacing: 16) {
                IconButton(icon: "arrow.left") { Nav.shared.back() }
                TextField("Search YouTube", text: $query)
                    .font(Fonts.bodyLarge)
                    .autocorrectionDisabled()
                    .focused($fieldFocused)
                    .onSubmit { search(query) }
                IconButton(icon: "magnifyingglass") { search(query) }
            }
            .padding(.horizontal, 32).padding(.vertical, 16)
            .focusSection()

            if showsResults {
                HStack(spacing: 16) {
                    ForEach(Self.filters.indices, id: \.self) { i in
                        let f = Self.filters[i].0
                        Chip(Self.filters[i].1, selected: filter == f, filter: true) {
                            guard filter != f else { return }
                            filter = f
                            run(searched)
                        }
                    }
                    Spacer()
                    IconButton(icon: grid ? "list.bullet" : "square.grid.2x2") { grid.toggle() }
                }
                .padding(.horizontal, 32).padding(.bottom, 8)
                .focusSection()
                if feed.items.isEmpty {
                    Status(error: feed.loaded ? (feed.error ?? "No results for “\(searched)”") : nil) { run(searched) }
                } else if grid {
                    ItemGrid(items: feed.items, more: { await feed.more(YT.searchMore) })
                } else {
                    ItemList(items: feed.items, more: { await feed.more(YT.searchMore) }) { EmptyView() }
                }
            } else if query.trimmingCharacters(in: .whitespaces).isEmpty {
                recentList
            } else {
                ScrollView {
                    LazyVStack(alignment: .leading, spacing: 4) {
                        ForEach(suggestions, id: \.self) { s in
                            line(icon: "magnifyingglass", s) { query = s; search(s) }
                        }
                    }
                    .padding(.horizontal, 32)
                }
            }
        }
        .onAppear {
            if !initial.isEmpty && searched.isEmpty { query = initial; search(initial) }
            else if initial.isEmpty && searched.isEmpty { fieldFocused = true }
        }
        .task(id: query) {
            let q = query.trimmingCharacters(in: .whitespaces)
            guard !q.isEmpty, q != searched else { suggestions = []; return }
            try? await Task.sleep(nanoseconds: 250_000_000)
            if Task.isCancelled { return }
            suggestions = (try? await YT.suggestions(q)) ?? []
        }
    }

    private var showsResults: Bool { !searched.isEmpty && query.trimmingCharacters(in: .whitespaces) == searched }

    private var recentList: some View {
        ScrollView {
            LazyVStack(alignment: .leading, spacing: 4) {
                if !recent.isEmpty {
                    HStack {
                        Text("Recent searches").font(Fonts.titleSmall)
                        Spacer()
                        TextButton("Clear all") {
                            recent = []
                            UserDefaults.standard.set(recent, forKey: "searches")
                        }
                    }
                    ForEach(recent, id: \.self) { q in
                        HStack(spacing: 8) {
                            line(icon: "clock.arrow.circlepath", q) { query = q; search(q) }
                            IconButton(icon: "xmark", size: 36) {
                                recent.removeAll { $0 == q }
                                UserDefaults.standard.set(recent, forKey: "searches")
                            }
                        }
                    }
                }
            }
            .padding(.horizontal, 32)
        }
    }

    private func line(icon: String, _ text: String, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            HStack(spacing: 28) {
                Image(systemName: icon).font(.system(size: 32)).foregroundStyle(Theme.sub).frame(width: 48)
                Text(text).font(Fonts.bodyLarge).foregroundStyle(Theme.text).lineLimit(1)
                Spacer()
            }
            .padding(.horizontal, 24).frame(height: 88)
        }
        .buttonStyle(.glow(radius: 16))
    }

    private func search(_ raw: String) {
        let q = raw.trimmingCharacters(in: .whitespaces)
        guard !q.isEmpty else { return }
        query = q
        recent.removeAll { $0.caseInsensitiveCompare(q) == .orderedSame }
        recent.insert(q, at: 0)
        recent = Array(recent.prefix(20))
        UserDefaults.standard.set(recent, forKey: "searches")
        // a link to a video, channel or playlist, or a @handle: straight there
        if q.hasPrefix("@") || q.contains("youtube.com/") || q.contains("youtu.be/") {
            Task {
                if let r = try? await YT.resolve(q) {
                    Nav.shared.back()
                    switch r.kind {
                    case "channel": Nav.shared.push(.channel(r.id))
                    case "playlist": Nav.shared.push(.playlist(r.id))
                    default: Nav.shared.open(Video(id: r.id, title: "", author: "", channelId: "", lengthSec: 0))
                    }
                } else {
                    run(q)
                }
            }
            return
        }
        run(q)
    }

    /// the search runs on its own, so the box changing again doesn't cut it off
    private func run(_ q: String) {
        searched = q
        let f = Feed()
        feed = f
        running?.cancel()
        let filter = filter
        running = Task { await f.load { try await YT.search(q, filter: filter) } }
    }
}

/// Subscriptions: the channels along the top, then everything they uploaded lately, newest first.
struct SubscriptionsView: View {
    @State private var library = Library.shared
    @State private var feed = SubFeed.shared
    @State private var shown = 40
    @State private var find = ""

    var body: some View {
        Group {
            if library.subscriptions.isEmpty {
                VStack(spacing: 32) {
                    Text("No subscriptions yet").font(Fonts.titleLarge)
                    Text("Subscribe to channels, or bring your subscriptions over from another app.")
                        .font(Fonts.bodyMedium).foregroundStyle(Theme.sub).multilineTextAlignment(.center)
                    FilledButton("Import subscriptions") { Nav.shared.push(.importSubs) }
                    Text("From YouTube (Google Takeout), NewPipe, FreeTube, Invidious, TubeHaus or OPML")
                        .font(Fonts.bodySmall).foregroundStyle(Theme.sub)
                }
                .frame(maxWidth: 1200)
                .frame(maxWidth: .infinity, maxHeight: .infinity)
            } else {
                ItemGrid(items: shownVideos.map(Item.video), more: { shown += 40 }) {
                    header
                    if shownVideos.isEmpty {
                        Status(error: feed.loading ? nil : (find.isEmpty ? "Nothing new from your channels" : "Nothing matches “\(find)”")) {
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
        let q = find.trimmingCharacters(in: .whitespaces)
        let all = q.isEmpty ? feed.videos : feed.videos.filter {
            $0.title.localizedCaseInsensitiveContains(q) || $0.author.localizedCaseInsensitiveContains(q)
        }
        return Array(all.prefix(shown))
    }

    private var header: some View {
        VStack(alignment: .leading, spacing: 8) {
            HStack(spacing: 16) {
                TextField("Search this feed", text: $find)
                    .font(Fonts.bodyMedium)
                    .frame(maxWidth: 700)
                Spacer()
                TextButton("Manage") { Nav.shared.push(.manageSubs) }
                TextButton("Refresh") { Task { await feed.load(force: true) } }
            }
            .padding(.horizontal, 32)
            .focusSection()
            ScrollView(.horizontal) {
                LazyHStack(alignment: .top, spacing: 16) {
                    ForEach(library.subscriptions) { c in
                        Button { Nav.shared.push(.channel(c.id)) } label: {
                            VStack(spacing: 10) {
                                Avatar(url: c.avatarURL, name: c.name, size: 112)
                                Text(c.name).font(Fonts.bodySmall).foregroundStyle(Theme.text).lineLimit(1)
                            }
                            .frame(width: 128)
                            .padding(12)
                        }
                        .buttonStyle(.glow(radius: 20))
                    }
                }
                .padding(.horizontal, 32).padding(.vertical, 16)
            }
            .scrollClipDisabled()
            .focusSection()
        }
        .padding(.top, 24)
    }
}

/// every subscribed channel, with ✕ to leave it
struct ManageSubsView: View {
    @State private var library = Library.shared
    @State private var confirm = false

    var body: some View {
        ScrollView {
            LazyVStack(alignment: .leading, spacing: 4) {
                HStack {
                    Text("\(library.subscriptions.count) channels").font(Fonts.titleLarge)
                    Spacer()
                    TextButton("Unsubscribe from all", color: Theme.error) { confirm = true }
                        .disabled(library.subscriptions.isEmpty)
                }
                .padding(.bottom, 16)
                ForEach(library.subscriptions) { c in
                    HStack(spacing: 16) {
                        Button { Nav.shared.push(.channel(c.id)) } label: {
                            HStack(spacing: 28) {
                                Avatar(url: c.avatarURL, name: c.name, size: 80)
                                Text(c.name).font(Fonts.bodyLarge).foregroundStyle(Theme.text).lineLimit(1)
                                Spacer()
                            }
                            .padding(.horizontal, 24).padding(.vertical, 12)
                        }
                        .buttonStyle(.glow(radius: 16))
                        IconButton(icon: "xmark", size: 36) { library.toggleSubscription(c) }
                    }
                }
            }
            .padding(32)
        }
        .confirmationDialog("Unsubscribe from all \(library.subscriptions.count) channels?", isPresented: $confirm, titleVisibility: .visible) {
            Button("Unsubscribe from all", role: .destructive) {
                for c in library.subscriptions { library.toggleSubscription(c) }
            }
        }
    }
}

/// Library (LibraryScreens.kt): recent history along the top, then the lists, then your data.
struct LibraryView: View {
    @State private var library = Library.shared

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 0) {
                SectionTitle("History")
                if library.history.isEmpty {
                    Text("Videos you watch show up here").font(Fonts.bodyMedium).foregroundStyle(Theme.sub)
                        .padding(.horizontal, 32).padding(.vertical, 24)
                } else {
                    ScrollView(.horizontal) {
                        LazyHStack(alignment: .top, spacing: 24) {
                            ForEach(library.history.prefix(30)) { w in SmallCard(video: w.video) }
                        }
                        .padding(.horizontal, 32).padding(.vertical, 20)
                    }
                    .scrollClipDisabled()
                    .focusSection()
                }
                VStack(spacing: 4) {
                    LibRow(icon: "clock.arrow.circlepath", label: "History", count: library.history.count) { Nav.shared.push(.history) }
                    LibRow(icon: "clock", label: "Watch later", count: library.later.count) { Nav.shared.push(.later) }
                    LibRow(icon: "hand.thumbsup", label: "Liked videos", count: library.liked.count) { Nav.shared.push(.liked) }
                }
                .padding(.horizontal, 16).padding(.top, 16)
                SectionTitle("Your data")
                VStack(spacing: 4) {
                    LibRow(icon: "sparkles", label: "What TubeHaus learned about you", count: nil) { Nav.shared.push(.learned) }
                    LibRow(icon: "square.and.arrow.down", label: "Import subscriptions", count: nil) { Nav.shared.push(.importSubs) }
                }
                .padding(.horizontal, 16).padding(.bottom, 48)
            }
        }
    }
}

/// a video in Library's history row: 320 wide, the title under it
struct SmallCard: View {
    let video: Video
    @State private var library = Library.shared

    var body: some View {
        Button { Nav.shared.open(video) } label: {
            VStack(alignment: .leading, spacing: 12) {
                Thumb(urls: video.cardPictures, badge: video.badge, progress: library.progress(video.id), radius: 16)
                Text(video.title).font(Fonts.bodySmall).foregroundStyle(Theme.text).lineLimit(2)
                    .frame(maxWidth: .infinity, minHeight: 64, alignment: .topLeading)
                    .padding(.horizontal, 8).padding(.bottom, 8)
            }
            .frame(width: 320)
        }
        .buttonStyle(CardFocus(radius: 16))
        .videoMenu(video, history: true)
    }
}

struct LibRow: View {
    let icon: String
    let label: String
    let count: Int?
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            HStack(spacing: 32) {
                Image(systemName: icon).font(.system(size: 36)).frame(width: 56)
                Text(label).font(Fonts.bodyMedium)
                Spacer()
                if let count { Text("\(count)").font(Fonts.bodyMedium).foregroundStyle(Theme.sub) }
                Image(systemName: "chevron.right").font(.system(size: 24)).foregroundStyle(Theme.sub)
            }
            .foregroundStyle(Theme.text)
            .padding(.horizontal, 24).frame(height: 96)
        }
        .buttonStyle(.glow(radius: 16))
    }
}

/// everything watched, newest first, with a search box
struct HistoryView: View {
    @State private var library = Library.shared
    @State private var find = ""
    @State private var confirm = false

    var body: some View {
        let q = find.trimmingCharacters(in: .whitespaces)
        let list = library.history.map(\.video).filter {
            q.isEmpty || $0.title.localizedCaseInsensitiveContains(q) || $0.author.localizedCaseInsensitiveContains(q)
        }
        ScrollView {
            LazyVStack(alignment: .leading, spacing: 4) {
                Text("History").font(Fonts.titleLarge).padding(.horizontal, 24)
                HStack(spacing: 16) {
                    TextField("Search watch history", text: $find).font(Fonts.bodyMedium).frame(maxWidth: 800)
                    Spacer()
                    TextButton("Clear all", color: Theme.error) { confirm = true }.disabled(library.history.isEmpty)
                }
                .padding(.horizontal, 24).padding(.vertical, 12)
                .focusSection()
                if list.isEmpty {
                    Text(library.history.isEmpty ? "Videos you watch show up here" : "Nothing matches “\(q)”")
                        .font(Fonts.bodyMedium).foregroundStyle(Theme.sub).padding(24)
                }
                ForEach(list) { v in VideoRow(video: v, history: true) }
            }
            .padding(32)
        }
        .confirmationDialog("Clear watch history?", isPresented: $confirm, titleVisibility: .visible) {
            Button("Clear", role: .destructive) { library.clearHistory() }
        }
    }
}

/// Watch later or Liked videos
struct LocalList: View {
    enum Kind { case later, liked }
    let kind: Kind
    @State private var library = Library.shared

    var body: some View {
        let list = kind == .later ? library.later : library.liked
        ScrollView {
            LazyVStack(alignment: .leading, spacing: 4) {
                HStack(alignment: .center, spacing: 32) {
                    VStack(alignment: .leading, spacing: 6) {
                        Text(kind == .later ? "Watch later" : "Liked videos").font(Fonts.titleLarge)
                        Text("\(list.count) videos").font(Fonts.bodySmall).foregroundStyle(Theme.sub)
                    }
                    Spacer()
                    if let first = list.first {
                        FilledButton("Play all", icon: "play.fill") {
                            PlayerModel.shared.queue = Array(list.dropFirst())
                            Nav.shared.open(first)
                        }
                    }
                }
                .padding(.horizontal, 24).padding(.bottom, 16)
                if list.isEmpty {
                    Text(kind == .later ? "Hold OK on a video and choose Save to Watch later" : "Videos you like show up here")
                        .font(Fonts.bodyMedium).foregroundStyle(Theme.sub).padding(24)
                }
                ForEach(list) { v in VideoRow(video: v) }
            }
            .padding(32)
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
            .padding(48)
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

/// Settings (SettingsScreen.kt): groups in blue, a line per setting.
struct SettingsView: View {
    @State private var library = Library.shared
    @State private var hideShorts = Settings.hideShorts
    @State private var hideComments = Settings.hideComments
    @State private var hideRelated = Settings.hideRelated
    @State private var autoplay = Settings.autoplayNext
    @State private var captions = Settings.captions
    @State private var captionSize = Settings.captionSize
    @State private var language = UserDefaults.standard.string(forKey: "hl") ?? ""
    @State private var region = UserDefaults.standard.string(forKey: "gl") ?? ""
    @State private var engine = ""
    @State private var dialog: Dialog?

    enum Dialog: String, Identifiable {
        case language, region, captions, captionSize, clear
        var id: String { rawValue }
    }

    private static let languages = ["en", "ko", "ja", "zh", "es", "fr", "de", "pt", "ru", "tr", "it", "id", "vi", "ar", "hi"]

    var body: some View {
        ScrollView {
            LazyVStack(alignment: .leading, spacing: 0) {
                group("General")
                item("Language", language.isEmpty ? "\(name(language: Settings.hl)) (from this device)" : name(language: language)) { dialog = .language }
                item("Region", region.isEmpty ? "\(regionName(Settings.gl)) (from this device)" : regionName(region)) { dialog = .region }

                group("Focus")
                toggle("Hide comments", "No comments under videos", $hideComments) { Settings.hideComments = $0 }
                toggle("Hide Shorts", "No Shorts tab, no Shorts in lists", $hideShorts) {
                    Settings.hideShorts = $0
                    Recommender.shared.clearCache()
                }
                toggle("Hide related videos", "Nothing suggested next to a video", $hideRelated) { Settings.hideRelated = $0 }

                group("Playback")
                toggle("Autoplay", "Play the next video when one ends", $autoplay) { Settings.autoplayNext = $0 }
                item("Captions", captions.isEmpty ? "Off" : name(language: captions)) { dialog = .captions }
                item("Caption size", ["Small", "Medium", "Large"][max(0, min(2, captionSize))]) { dialog = .captionSize }

                group("Your data")
                item("What TubeHaus learned about you", "See and change what your recommendations are based on") { Nav.shared.push(.learned) }
                item("Pick topics", "Tell Home what you like to watch") { Nav.shared.push(.topics) }
                item("Import", "Subscriptions from YouTube (Google Takeout), NewPipe, FreeTube, Invidious, TubeHaus or OPML, sent from your phone") { Nav.shared.push(.importSubs) }
                item("Manage subscriptions", "\(library.subscriptions.count) channels. Remove channels, or all of them") { Nav.shared.push(.manageSubs) }
                item("Clear watch history", "") { dialog = .clear }

                group("About")
                item("TubeHaus \(Bundle.main.object(forInfoDictionaryKey: "CFBundleShortVersionString") as? String ?? "")",
                     "Everything happens on this device: it talks to YouTube directly, with no TubeHaus server, no account and no tracking. Your library and recommendations never leave it.") {}
                item("YouTube engine", engine) {}
                item("Source code and updates", "github.com/biolend83/tubehaus") {}
                item("Disclaimer", "TubeHaus is independent, free, open-source software, not affiliated with YouTube or Google. It doesn't host any videos and comes with no warranty.") {}
            }
            .frame(maxWidth: 1600, alignment: .leading)
            .padding(.horizontal, 32).padding(.bottom, 64)
        }
        .task {
            _ = try? await Engine.shared.call("suggestions", [""])   // loads the engine, so its version is known
            let v = await Engine.shared.version, s = await Engine.shared.source
            engine = "\(v) (\(s))"
        }
        .confirmationDialog(title(dialog), isPresented: Binding(get: { dialog != nil }, set: { if !$0 { dialog = nil } }),
                            titleVisibility: .visible, presenting: dialog) { d in
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
                    Button(["Small", "Medium", "Large"][i]) { captionSize = i; Settings.captionSize = i }
                }
            case .clear:
                Button("Clear", role: .destructive) { library.clearHistory() }
            }
        }
    }

    private func title(_ d: Dialog?) -> String {
        switch d {
        case .language: return "Language"
        case .region: return "Region"
        case .captions: return "Captions"
        case .captionSize: return "Caption size"
        case .clear: return "Clear watch history?"
        case nil: return ""
        }
    }

    private func group(_ t: String) -> some View {
        Text(t).font(Fonts.titleSmall).foregroundStyle(Theme.blue)
            .padding(.leading, 32).padding(.top, 40).padding(.bottom, 8)
    }

    private func item(_ title: String, _ sub: String, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            VStack(alignment: .leading, spacing: 4) {
                Text(title).font(Fonts.bodyMedium).foregroundStyle(Theme.text)
                if !sub.isEmpty { Text(sub).font(Fonts.bodySmall).foregroundStyle(Theme.sub).multilineTextAlignment(.leading) }
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
                VStack(alignment: .leading, spacing: 4) {
                    Text(title).font(Fonts.bodyMedium).foregroundStyle(Theme.text)
                    if !sub.isEmpty { Text(sub).font(Fonts.bodySmall).foregroundStyle(Theme.sub) }
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

/// Material's switch, dark theme: white track when on, grey outline when off
struct Switch: View {
    let on: Bool

    var body: some View {
        ZStack(alignment: on ? .trailing : .leading) {
            Capsule().fill(on ? Theme.text : Theme.chip)
            Capsule().strokeBorder(on ? Color.clear : Theme.sub, lineWidth: 4)
            Circle().fill(on ? Theme.bg : Theme.sub)
                .frame(width: on ? 48 : 32, height: on ? 48 : 32)
                .padding(.horizontal, on ? 8 : 16)
        }
        .frame(width: 104, height: 64)
        .animation(.easeOut(duration: 0.15), value: on)
    }
}
