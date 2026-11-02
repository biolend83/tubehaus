import SwiftUI

/// Home: what was being watched, new uploads from subscriptions, then recommendations worked out
/// on this TV (Recommend.swift). The first time, with nothing to go on, it asks for a few topics.
struct HomeView: View {
    @State private var library = Library.shared
    @State private var subs = SubFeed.shared
    @State private var items: [Video] = []
    @State private var loading = false
    @State private var error: String?
    @State private var skipped = false

    var body: some View {
        if library.needsTopics && !skipped {
            TopicPicker { skipped = true }
        } else {
            Scroller {
                if !library.continueWatching.isEmpty {
                    Row(title: "Continue watching", items: library.continueWatching.prefix(20).map { Item.video($0.video) })
                }
                if !newFromSubs.isEmpty {
                    Row(title: "New from your subscriptions", items: newFromSubs.map(Item.video))
                }
                VStack(alignment: .leading, spacing: 24) {
                    HStack {
                        Heading("Recommended")
                        Spacer()
                        Button { Task { await load(refresh: true) } } label: { Label("Refresh", systemImage: "arrow.clockwise") }
                            .padding(.trailing, Theme.edge)
                            .disabled(loading)
                    }
                    if items.isEmpty {
                        Status(error: loading ? nil : (error ?? "Nothing to show yet. Search for something you like.")) {
                            Task { await load(refresh: true) }
                        }
                    } else {
                        CardGrid(items: items.map(Item.video)) { await more() }
                    }
                }
            }
            .task { if items.isEmpty { await load(refresh: false) } }
        }
    }

    private var newFromSubs: [Video] {
        let since = Int(Date().timeIntervalSince1970) - 7 * 86400
        return Array(subs.videos.filter { $0.publishedAt > since && !library.finished($0.id) }.prefix(20))
    }

    private func load(refresh: Bool) async {
        guard !loading else { return }
        loading = true
        error = nil
        defer { loading = false }
        if refresh { Recommender.shared.clearCache() }
        if !library.subscriptions.isEmpty { await subs.load(force: refresh) }
        let got = await Recommender.shared.home(40)
        items = got
        if got.isEmpty { error = "Couldn't reach YouTube" }
    }

    private func more() async {
        guard !loading else { return }
        loading = true
        defer { loading = false }
        let got = await Recommender.shared.home(40, skip: Set(items.map(\.id)))
        let have = Set(items.map(\.id))
        items += got.filter { !have.contains($0.id) }
    }
}

/// a few topics give the recommendations something to begin with
struct TopicPicker: View {
    var done: (() -> Void)? = nil
    @State private var picked: Set<String> = []
    @Environment(\.dismiss) private var dismiss

    private let columns = Array(repeating: GridItem(.flexible(), spacing: 30), count: 5)

    var body: some View {
        Scroller {
            VStack(spacing: 18) {
                Text("What do you like to watch?").font(.system(size: 56, weight: .bold))
                Text("Pick a few topics to start. Your recommendations are worked out on this Apple TV from what you watch; nothing leaves it.")
                    .font(.system(size: 28)).foregroundStyle(.secondary).multilineTextAlignment(.center).frame(maxWidth: 1200)
            }
            .frame(maxWidth: .infinity)

            LazyVGrid(columns: columns, spacing: 30) {
                ForEach(Recommender.topics, id: \.self) { t in
                    Button {
                        if picked.contains(t) { picked.remove(t) } else { picked.insert(t) }
                    } label: {
                        Label(t, systemImage: picked.contains(t) ? "checkmark.circle.fill" : "circle")
                            .frame(maxWidth: .infinity)
                    }
                }
            }
            .padding(.horizontal, 160)
            .focusSection()

            HStack(spacing: 30) {
                Button {
                    Library.shared.pickTopics(Array(picked))
                    finish()
                } label: { Label("Continue", systemImage: "arrow.right") }
                .disabled(picked.isEmpty)
                NavigationLink(value: Route.importSubs) {
                    Label("Import subscriptions from your phone", systemImage: "qrcode")
                }
                Button("Skip") { finish() }
            }
            .frame(maxWidth: .infinity)
            .focusSection()
        }
    }

    private func finish() {
        if let done { done() } else { dismiss() }
    }
}

/// Search: type with the TV keyboard (or dictate); results follow as you type, after a pause.
struct SearchView: View {
    @State private var query = UserDefaults.standard.string(forKey: "q") ?? ""
    @State private var suggestions: [String] = []
    @State private var feed = Feed()
    @State private var searched = ""
    @State private var recent: [String] = UserDefaults.standard.stringArray(forKey: "searches") ?? []

    var body: some View {
        Scroller {
            if searched.isEmpty {
                if !recent.isEmpty {
                    VStack(alignment: .leading, spacing: 24) {
                        Heading("Recent searches")
                        ScrollView(.horizontal) {
                            LazyHStack(spacing: 24) {
                                ForEach(recent, id: \.self) { q in
                                    Button { query = q; Task { await search(q) } } label: { Label(q, systemImage: "clock.arrow.circlepath") }
                                }
                            }
                            .padding(.horizontal, Theme.edge).padding(.vertical, 20)
                        }
                        .scrollClipDisabled()
                    }
                    .focusSection()
                }
            } else if feed.items.isEmpty {
                Status(error: feed.loaded ? (feed.error ?? "No results for “\(searched)”") : nil, icon: "magnifyingglass")
            } else {
                CardGrid(items: feed.items) { await feed.more(YT.searchMore) }
            }
        }
        .searchable(text: $query, prompt: "Search YouTube")
        .searchSuggestions {
            ForEach(suggestions, id: \.self) { Text($0).searchCompletion($0) }
        }
        .onSubmit(of: .search) { Task { await search(query) } }
        .task(id: query) {
            let q = query.trimmingCharacters(in: .whitespaces)
            guard !q.isEmpty else { suggestions = []; return }
            try? await Task.sleep(nanoseconds: 300_000_000)
            if Task.isCancelled { return }
            suggestions = (try? await YT.suggestions(q)) ?? []
            try? await Task.sleep(nanoseconds: 900_000_000)
            if Task.isCancelled { return }
            await search(q)
        }
    }

    private func search(_ q: String) async {
        let q = q.trimmingCharacters(in: .whitespaces)
        guard !q.isEmpty, q != searched else { return }
        searched = q
        recent.removeAll { $0.caseInsensitiveCompare(q) == .orderedSame }
        recent.insert(q, at: 0)
        recent = Array(recent.prefix(12))
        UserDefaults.standard.set(recent, forKey: "searches")
        feed = Feed()
        await feed.load { try await YT.search(q) }
    }
}

/// Subscriptions: the channels, then everything they uploaded lately, newest first.
struct SubscriptionsView: View {
    @State private var library = Library.shared
    @State private var feed = SubFeed.shared
    @State private var shown = 40

    var body: some View {
        Scroller {
            if library.subscriptions.isEmpty {
                VStack(spacing: 30) {
                    Image(systemName: "person.2").font(.system(size: 80)).foregroundStyle(.secondary)
                    Text("No subscriptions yet").font(.system(size: 44, weight: .bold))
                    Text("Bring them over from YouTube, NewPipe, FreeTube, Invidious or the TubeHaus app with your phone, or open a channel and choose Subscribe.")
                        .font(.system(size: 28)).foregroundStyle(.secondary).multilineTextAlignment(.center).frame(maxWidth: 1100)
                    NavigationLink(value: Route.importSubs) { Label("Import from your phone", systemImage: "qrcode") }
                }
                .frame(maxWidth: .infinity, minHeight: 700)
            } else {
                VStack(alignment: .leading, spacing: 8) {
                    HStack {
                        Heading("Channels", detail: "\(library.subscriptions.count)")
                        Spacer()
                        NavigationLink(value: Route.importSubs) { Label("Import", systemImage: "square.and.arrow.down") }
                            .padding(.trailing, Theme.edge)
                    }
                    ScrollView(.horizontal) {
                        LazyHStack(alignment: .top, spacing: 30) {
                            ForEach(library.subscriptions) { ChannelCard(channel: $0, size: 130) }
                        }
                        .padding(.horizontal, Theme.edge).padding(.vertical, 30)
                    }
                    .scrollClipDisabled()
                }
                .focusSection()

                VStack(alignment: .leading, spacing: 24) {
                    Heading("Latest")
                    if feed.videos.isEmpty {
                        Status(error: feed.loading ? nil : "Nothing new from your channels", icon: "film") { Task { await feed.load(force: true) } }
                    } else {
                        CardGrid(items: feed.videos.prefix(shown).map(Item.video)) { shown += 40 }
                    }
                }
            }
        }
        .task(id: library.subscriptions.count) { await feed.load() }
    }
}

struct LibraryView: View {
    @State private var library = Library.shared

    var body: some View {
        Scroller {
            if !library.continueWatching.isEmpty {
                Row(title: "Continue watching", items: library.continueWatching.prefix(30).map { Item.video($0.video) })
            }
            if !library.later.isEmpty {
                Row(title: "Watch later", items: library.later.map(Item.video))
            }
            if !library.liked.isEmpty {
                Row(title: "Liked", items: library.liked.map(Item.video))
            }
            if !library.history.isEmpty {
                Row(title: "History", items: library.history.prefix(80).map { Item.video($0.video) })
            }
            if library.later.isEmpty && library.history.isEmpty && library.liked.isEmpty {
                VStack(spacing: 30) {
                    Image(systemName: "clock.arrow.circlepath").font(.system(size: 80)).foregroundStyle(.secondary)
                    Text("Nothing here yet").font(.system(size: 44, weight: .bold))
                    Text("Videos you watch, like, or save for later (hold the select button on a video) show here.")
                        .font(.system(size: 28)).foregroundStyle(.secondary).multilineTextAlignment(.center).frame(maxWidth: 1100)
                }
                .frame(maxWidth: .infinity, minHeight: 700)
            }
        }
    }
}

struct SettingsView: View {
    @State private var library = Library.shared
    @State private var hideShorts = Settings.hideShorts
    @State private var autoplay = Settings.autoplayNext
    @State private var captions = Settings.captions
    @State private var captionSize = Settings.captionSize
    @State private var language = UserDefaults.standard.string(forKey: "hl") ?? ""
    @State private var region = UserDefaults.standard.string(forKey: "gl") ?? ""
    @State private var engine = ""
    @State private var confirmClear = false
    @State private var confirmReset = false

    private static let languages = ["", "en", "ko", "ja", "zh", "es", "fr", "de", "pt", "ru", "tr", "it", "id", "vi", "ar", "hi"]
    private static let regions = ["", "US", "KR", "JP", "GB", "DE", "FR", "ES", "BR", "IN", "ID", "TR", "RU", "MX", "CA", "AU"]

    var body: some View {
        Form {
            Section("Subscriptions") {
                NavigationLink(value: Route.importSubs) {
                    LabeledContent("Import from your phone", value: "\(library.subscriptions.count) channels")
                }
            }
            Section("Playback") {
                Toggle("Play the next video automatically", isOn: $autoplay)
                    .onChange(of: autoplay) { Settings.autoplayNext = autoplay }
                Picker("Subtitles", selection: $captions) {
                    Text("Off").tag("")
                    ForEach(Self.languages.dropFirst(), id: \.self) { Text(name(language: $0)).tag($0) }
                }
                .onChange(of: captions) { Settings.captions = captions }
                Picker("Subtitle size", selection: $captionSize) {
                    Text("Small").tag(0)
                    Text("Medium").tag(1)
                    Text("Large").tag(2)
                }
                .onChange(of: captionSize) { Settings.captionSize = captionSize }
            }
            Section("Home") {
                Toggle("Hide Shorts", isOn: $hideShorts)
                    .onChange(of: hideShorts) { Settings.hideShorts = hideShorts }
                NavigationLink(value: Route.topics) { Text("Pick topics") }
                Button("Reset recommendations", role: .destructive) { confirmReset = true }
            }
            Section("YouTube") {
                Picker("Language", selection: $language) {
                    ForEach(Self.languages, id: \.self) { Text($0.isEmpty ? "Same as the TV" : name(language: $0)).tag($0) }
                }
                .onChange(of: language) { save("hl", language) }
                Picker("Region", selection: $region) {
                    ForEach(Self.regions, id: \.self) { Text($0.isEmpty ? "Same as the TV" : name(region: $0)).tag($0) }
                }
                .onChange(of: region) { save("gl", region) }
            }
            Section {
                Button("Clear watch history", role: .destructive) { confirmClear = true }
                    .disabled(library.history.isEmpty)
            }
            Section("About") {
                LabeledContent("TubeHaus for Apple TV", value: Bundle.main.object(forInfoDictionaryKey: "CFBundleShortVersionString") as? String ?? "")
                LabeledContent("YouTube engine", value: engine)
                Text("Open source (AGPL-3.0): github.com/biolend83/tubehaus. No account, no ads; nothing leaves this TV but the requests to YouTube. Not affiliated with YouTube or Google.")
                    .font(.caption).foregroundStyle(.secondary)
            }
        }
        .frame(maxWidth: 1200)
        .task {
            _ = try? await Engine.shared.call("suggestions", [""])   // loads the engine, so its version is known
            let v = await Engine.shared.version, s = await Engine.shared.source
            engine = "\(v) (\(s))"
        }
        .confirmationDialog("Clear watch history?", isPresented: $confirmClear) {
            Button("Clear", role: .destructive) { library.clearHistory() }
        }
        .confirmationDialog("Forget everything Home learned about what you like?", isPresented: $confirmReset) {
            Button("Reset", role: .destructive) {
                library.resetTaste()
                Recommender.shared.clearCache()
            }
        }
    }

    private func save(_ key: String, _ value: String) {
        if value.isEmpty { UserDefaults.standard.removeObject(forKey: key) } else { UserDefaults.standard.set(value, forKey: key) }
        Recommender.shared.clearCache()
    }

    private func name(language: String) -> String {
        Locale(identifier: language).localizedString(forLanguageCode: language)?.capitalized(with: Locale(identifier: language)) ?? language
    }

    private func name(region: String) -> String {
        Locale.current.localizedString(forRegionCode: region) ?? region
    }
}
