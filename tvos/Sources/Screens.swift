import SwiftUI

/// Home: what was being watched, then the newest videos from subscribed channels.
/// With no subscriptions yet, a few topic rows from search so the screen isn't empty.
struct HomeView: View {
    @State private var library = Library.shared
    @State private var latest: [Video] = []
    @State private var topics: [Topic] = []
    @State private var error: String?
    @State private var loading = true
    @State private var loadedFor: [String] = []

    struct Topic: Identifiable {
        let title: String
        let items: [Item]
        var id: String { title }
    }

    private static let starterTopics = ["Music", "News", "Gaming", "Science", "Cooking", "Travel", "Documentary"]

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 50) {
                if !library.continueWatching.isEmpty {
                    Row(title: "Continue watching") {
                        ForEach(library.continueWatching.prefix(20)) { VideoCard(video: $0.video) }
                    }
                }
                if library.subscriptions.isEmpty {
                    ForEach(topics) { t in
                        Row(title: t.title) { ForEach(t.items) { ItemCard(item: $0) } }
                    }
                    if topics.isEmpty { Status(error: error) { Task { await load() } } }
                } else {
                    Text("Latest from your subscriptions").font(.title3.weight(.semibold)).padding(.leading, 80)
                    if latest.isEmpty { Status(error: loading ? nil : (error ?? "Nothing new yet")) { Task { await load() } } }
                    CardGrid(items: latest.map(Item.video))
                }
            }
            .padding(.vertical, 40)
        }
        .task(id: library.subscriptions.map(\.id)) {
            if loadedFor != library.subscriptions.map(\.id) || (latest.isEmpty && topics.isEmpty) { await load() }
        }
    }

    private func load() async {
        loading = true
        error = nil
        defer { loading = false }
        let subs = library.subscriptions
        loadedFor = subs.map(\.id)
        if subs.isEmpty {
            await loadTopics()
            return
        }
        // six channels at a time; one that fails is skipped
        var all: [Video] = []
        await withTaskGroup(of: [Video].self) { g in
            var it = subs.makeIterator()
            for _ in 0..<6 { if let c = it.next() { g.addTask { (try? await YT.feed(c.id)) ?? [] } } }
            for await vs in g {
                all += vs
                if let c = it.next() { g.addTask { (try? await YT.feed(c.id)) ?? [] } }
            }
        }
        let cutoff = Int(Date().timeIntervalSince1970) - 30 * 86400
        latest = Array(all.filter { !($0.short && Settings.hideShorts) && ($0.publishedAt == 0 || $0.publishedAt > cutoff) }
            .sorted { $0.publishedAt > $1.publishedAt }.prefix(120))
        if latest.isEmpty && all.isEmpty { error = "Couldn't reach YouTube" }
    }

    private func loadTopics() async {
        var rows: [Topic] = []
        for t in Self.starterTopics {
            do {
                let p = try await YT.search(t)
                let items = p.items.filter { if case .video = $0 { return Feed.keep($0) } else { return false } }
                if !items.isEmpty { rows.append(Topic(title: t, items: Array(items.prefix(16)))) }
                topics = rows
            } catch {
                self.error = error.localizedDescription
                if rows.isEmpty { return }
            }
        }
    }
}

/// Search: type with the TV keyboard (or dictate); results follow as you type, after a pause.
struct SearchView: View {
    @State private var query = ""
    @State private var suggestions: [String] = []
    @State private var feed = Feed()
    @State private var searched = ""

    var body: some View {
        ScrollView {
            if !searched.isEmpty {
                if feed.items.isEmpty { Status(error: feed.loaded ? (feed.error ?? "No results") : nil) }
                CardGrid(items: feed.items) { await feed.more(YT.searchMore) }
                    .padding(.vertical, 40)
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
            try? await Task.sleep(nanoseconds: 350_000_000)
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
        feed = Feed()
        await feed.load { try await YT.search(q) }
    }
}

struct SubscriptionsView: View {
    @State private var library = Library.shared

    var body: some View {
        ScrollView {
            if library.subscriptions.isEmpty {
                Text("Open a channel and choose Subscribe; its new videos then show on Home.")
                    .foregroundStyle(.secondary).frame(maxWidth: .infinity, minHeight: 400)
            } else {
                CardGrid(items: library.subscriptions.map(Item.channel)).padding(.vertical, 40)
            }
        }
    }
}

struct LibraryView: View {
    @State private var library = Library.shared

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 50) {
                if !library.later.isEmpty {
                    Row(title: "Watch later") { ForEach(library.later) { VideoCard(video: $0) } }
                }
                if !library.history.isEmpty {
                    Row(title: "History") { ForEach(library.history.prefix(60)) { VideoCard(video: $0.video) } }
                }
                if library.later.isEmpty && library.history.isEmpty {
                    Text("Videos you watch, and ones you save for later (hold the select button on a video), show here.")
                        .foregroundStyle(.secondary).frame(maxWidth: .infinity, minHeight: 400)
                }
            }
            .padding(.vertical, 40)
        }
    }
}

struct SettingsView: View {
    @State private var library = Library.shared
    @State private var hideShorts = Settings.hideShorts
    @State private var language = UserDefaults.standard.string(forKey: "hl") ?? ""
    @State private var region = UserDefaults.standard.string(forKey: "gl") ?? ""
    @State private var engine = ""
    @State private var confirmClear = false

    private static let languages = ["", "en", "ko", "ja", "zh", "es", "fr", "de", "pt", "ru", "tr", "it", "id", "vi", "ar", "hi"]
    private static let regions = ["", "US", "KR", "JP", "GB", "DE", "FR", "ES", "BR", "IN", "ID", "TR", "RU", "MX", "CA", "AU"]

    var body: some View {
        Form {
            Section {
                Toggle("Hide Shorts", isOn: $hideShorts)
                    .onChange(of: hideShorts) { Settings.hideShorts = hideShorts }
                Picker("YouTube language", selection: $language) {
                    ForEach(Self.languages, id: \.self) { Text($0.isEmpty ? "Same as the TV" : name(language: $0)).tag($0) }
                }
                .onChange(of: language) { save("hl", language) }
                Picker("YouTube region", selection: $region) {
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
                Text("Open source (AGPL-3.0): github.com/biolend83/tubehaus. No account, no ads, nothing leaves this TV but the requests to YouTube. Not affiliated with YouTube or Google.")
                    .font(.caption).foregroundStyle(.secondary)
            }
        }
        .frame(maxWidth: 1100)
        .task {
            _ = try? await Engine.shared.call("suggestions", [""])   // loads the engine, so its version is known
            let v = await Engine.shared.version, s = await Engine.shared.source
            engine = "\(v) (\(s))"
        }
        .confirmationDialog("Clear watch history?", isPresented: $confirmClear) {
            Button("Clear", role: .destructive) { library.clearHistory() }
        }
    }

    private func save(_ key: String, _ value: String) {
        if value.isEmpty { UserDefaults.standard.removeObject(forKey: key) } else { UserDefaults.standard.set(value, forKey: key) }
    }

    private func name(language: String) -> String {
        Locale(identifier: language).localizedString(forLanguageCode: language)?.capitalized(with: Locale(identifier: language)) ?? language
    }

    private func name(region: String) -> String {
        Locale.current.localizedString(forRegionCode: region) ?? region
    }
}
