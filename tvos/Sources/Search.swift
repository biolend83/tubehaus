import SwiftUI

/// Search, as on YouTube's TV app: a keyboard on the left (Hangul, ABC, 123; the Apple keyboard
/// and dictation one press away), suggestions under it, and the results on the right as you type.
/// A pasted link or @handle opens what it points to.
struct SearchView: View {
    let initial: String
    @State private var keys: [Character] = []
    @State private var mode: Keyboard.Mode = Keyboard.Mode.start
    @State private var searched = ""
    @State private var suggestions: [String] = []
    @State private var feed = Feed()
    @State private var filter: YT.Filter = .all
    @State private var recent: [String] = UserDefaults.standard.stringArray(forKey: "searches") ?? []
    @State private var running: Task<Void, Never>?
    @State private var system = ""
    @FocusState private var focus: String?

    private var query: String { Hangul.compose(keys) }

    private static let filters: [(YT.Filter, String)] = [
        (.all, "All"), (.videos, "Videos"), (.channels, "Channels"), (.playlists, "Playlists"),
        (.live, "Live"), (.today, "Today"), (.week, "This week"), (.long, "Over 20 minutes"),
    ]

    var body: some View {
        HStack(alignment: .top, spacing: 0) {
            VStack(alignment: .leading, spacing: 20) {
                queryBox
                Keyboard(mode: $mode, focus: $focus) { k in typeKey(k) }
                    .focusSection()
                suggestionList
                Spacer(minLength: 0)
            }
            .frame(width: 660)
            .padding(.leading, Metrics.side).padding(.top, 48)

            VStack(alignment: .leading, spacing: 0) {
                if !searched.isEmpty {
                    ScrollView(.horizontal) {
                        HStack(spacing: 14) {
                            ForEach(Self.filters.indices, id: \.self) { i in
                                let f = Self.filters[i].0
                                Chip(Self.filters[i].1, selected: filter == f) {
                                    guard filter != f else { return }
                                    filter = f
                                    run(searched)
                                }
                            }
                        }
                        .padding(.horizontal, Metrics.side).padding(.vertical, 16)
                    }
                    .scrollClipDisabled()
                    .padding(.top, 36)
                    .focusSection()
                    if feed.items.isEmpty {
                        Status(error: feed.loaded ? (feed.error ?? "No results for “\(searched)”") : nil) { run(searched) }
                    } else {
                        ItemGrid(items: feed.items, more: { await feed.more(YT.searchMore) }, columns: 3)
                    }
                } else {
                    recentPane
                }
            }
            .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .topLeading)
        }
        .onAppear {
            if keys.isEmpty && !initial.isEmpty {
                keys = Array(initial)
                search(initial)
            }
        }
        .task(id: query) {
            let q = query.trimmingCharacters(in: .whitespaces)
            guard !q.isEmpty else { suggestions = []; return }
            try? await Task.sleep(nanoseconds: 250_000_000)
            if Task.isCancelled { return }
            suggestions = Array(((try? await YT.suggestions(q)) ?? []).prefix(7))
            // results follow the typing, once it pauses
            try? await Task.sleep(nanoseconds: 900_000_000)
            if Task.isCancelled || q == searched || q.count < 2 { return }
            run(q)
        }
    }

    // MARK: the box and the keyboard

    private var queryBox: some View {
        HStack(spacing: 16) {
            Image(systemName: "magnifyingglass").font(.system(size: 30, weight: .semibold)).foregroundStyle(Theme.sub)
            Group {
                if query.isEmpty {
                    Text("Search YouTube").foregroundStyle(Theme.sub)
                } else {
                    (Text(query) + Text("|").foregroundColor(Theme.red)).foregroundStyle(Theme.text)
                }
            }
            .font(.system(size: 34, weight: .medium)).lineLimit(1).truncationMode(.head)
            Spacer()
            // the Apple keyboard: every language, dictation, the Remote app on a phone
            TextField("", text: $system, prompt: Text(Image(systemName: "keyboard")))
                .frame(width: 96)
                .onSubmit {
                    let s = system.trimmingCharacters(in: .whitespaces)
                    system = ""
                    guard !s.isEmpty else { return }
                    keys = Array(s)
                    search(s)
                }
        }
        .padding(.horizontal, 28).frame(height: 96)
        .background(Theme.chip, in: RoundedRectangle(cornerRadius: 20))
        .focusSection()
    }

    private func typeKey(_ k: Keyboard.Key) {
        switch k {
        case .char(let c): keys.append(c)
        case .space: if keys.last != " " && !keys.isEmpty { keys.append(" ") }
        case .delete: if !keys.isEmpty { keys.removeLast() }
        case .clear: keys = []; searched = ""; feed = Feed()
        case .search: search(query)
        }
    }

    // MARK: suggestions and recent searches

    @ViewBuilder private var suggestionList: some View {
        if !suggestions.isEmpty {
            VStack(alignment: .leading, spacing: 4) {
                ForEach(suggestions, id: \.self) { s in
                    line(icon: "magnifyingglass", s) { keys = Array(s); search(s) }
                }
            }
            .focusSection()
        }
    }

    private var recentPane: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 4) {
                if recent.isEmpty {
                    VStack(alignment: .leading, spacing: 20) {
                        Image(systemName: "magnifyingglass").font(.system(size: 80)).foregroundStyle(Theme.sub)
                        Text("Search YouTube").font(Fonts.titleLarge)
                        Text("Type with the keyboard, press the keyboard button for dictation and every language, or paste a link or @handle.")
                            .font(Fonts.bodyMedium).foregroundStyle(Theme.sub).frame(maxWidth: 900, alignment: .leading)
                    }
                    .padding(.top, 140)
                } else {
                    HStack {
                        Text("Recent searches").font(Fonts.shelf)
                        Spacer()
                        TextButton("Clear all", icon: "trash") {
                            recent = []
                            UserDefaults.standard.set(recent, forKey: "searches")
                        }
                    }
                    .padding(.top, 52).padding(.bottom, 12)
                    ForEach(recent, id: \.self) { q in
                        HStack(spacing: 8) {
                            line(icon: "clock.arrow.circlepath", q) { keys = Array(q); search(q) }
                            IconButton(icon: "xmark", size: 36) {
                                recent.removeAll { $0 == q }
                                UserDefaults.standard.set(recent, forKey: "searches")
                            }
                        }
                    }
                }
            }
            .padding(.horizontal, Metrics.side)
            .focusSection()
        }
    }

    private func line(icon: String, _ text: String, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            HStack(spacing: 24) {
                Image(systemName: icon).font(.system(size: 26)).dim().frame(width: 40)
                Text(text).font(Fonts.bodyMedium).lineLimit(1)
                Spacer()
            }
            .padding(.horizontal, 20).frame(height: 72)
        }
        .buttonStyle(.glow(radius: 14))
    }

    // MARK: searching

    private func search(_ raw: String) {
        let q = raw.trimmingCharacters(in: .whitespaces)
        guard !q.isEmpty else { return }
        recent.removeAll { $0.caseInsensitiveCompare(q) == .orderedSame }
        recent.insert(q, at: 0)
        recent = Array(recent.prefix(20))
        UserDefaults.standard.set(recent, forKey: "searches")
        // a link to a video, channel or playlist, or a @handle: straight there
        if q.hasPrefix("@") || q.contains("youtube.com/") || q.contains("youtu.be/") {
            Task {
                if let r = try? await YT.resolve(q) {
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

/// The on-screen keyboard: Hangul (두벌식 jamo, joined into syllables as they're typed), ABC and 123.
struct Keyboard: View {
    enum Mode: String, CaseIterable {
        case hangul, abc, num

        var title: String {
            switch self {
            case .hangul: return "한글"
            case .abc: return "ABC"
            case .num: return "123"
            }
        }

        /// Hangul first on a Korean TV
        static var start: Mode { (Locale.preferredLanguages.first ?? "").hasPrefix("ko") ? .hangul : .abc }

        var keys: [Character] {
            switch self {
            case .hangul: return Array("ㅂㅈㄷㄱㅅㅛㅕㅑㅁㄴㅇㄹㅎㅗㅓㅏㅋㅌㅊㅍㅠㅜㅡㅣㅃㅉㄸㄲㅆㅐㅔㅒㅖ")
            case .abc: return Array("abcdefghijklmnopqrstuvwxyz'-")
            case .num: return Array("1234567890.,!?&@#:/_+()\"%")
            }
        }
    }

    enum Key: Hashable {
        case char(Character), space, delete, clear, search
    }

    @Binding var mode: Mode
    var focus: FocusState<String?>.Binding
    let press: (Key) -> Void

    private let columns = Array(repeating: GridItem(.fixed(84), spacing: 8), count: 7)

    var body: some View {
        VStack(alignment: .leading, spacing: 14) {
            HStack(spacing: 12) {
                ForEach(Mode.allCases, id: \.self) { m in
                    Chip(m.title, selected: mode == m) { mode = m }
                }
            }
            LazyVGrid(columns: columns, alignment: .leading, spacing: 8) {
                ForEach(Array(mode.keys.enumerated()), id: \.offset) { i, c in
                    key(String(c), id: "k\(mode.rawValue)\(i)") { press(.char(c)) }
                }
            }
            HStack(spacing: 8) {
                wide("Space", icon: "space", width: 268) { press(.space) }
                wide("", icon: "delete.left", width: 176) { press(.delete) }
                wide("Clear", icon: nil, width: 176) { press(.clear) }
            }
            wide("Search", icon: "magnifyingglass", width: 636, fill: Theme.pill) { press(.search) }
        }
    }

    private func key(_ label: String, id: String, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            Text(label).font(.system(size: 34, weight: .medium)).frame(width: 84, height: 72)
        }
        .buttonStyle(Glow(shape: RoundedRectangle(cornerRadius: 12), fill: Theme.chip.opacity(0.6), scale: 1.12))
        .focused(focus, equals: id)
    }

    private func wide(_ label: String, icon: String?, width: CGFloat, fill: Color = Theme.chip.opacity(0.6), action: @escaping () -> Void) -> some View {
        Button(action: action) {
            HStack(spacing: 12) {
                if let icon { Image(systemName: icon).font(.system(size: 28, weight: .semibold)) }
                if !label.isEmpty { Text(label).font(.system(size: 26, weight: .semibold)) }
            }
            .frame(width: width, height: 72)
        }
        .buttonStyle(Glow(shape: RoundedRectangle(cornerRadius: 12), fill: fill, scale: 1.06))
    }
}

/// Jamo typed one at a time, joined into syllables the way a Korean keyboard does: 가 + ㄱ = 각,
/// 각 + ㅏ = 가가, ㅗ + ㅏ = ㅘ, ㄹ + ㄱ = ㄺ. Anything that isn't jamo passes through.
enum Hangul {
    static let cho: [Character] = Array("ㄱㄲㄴㄷㄸㄹㅁㅂㅃㅅㅆㅇㅈㅉㅊㅋㅌㅍㅎ")
    static let jung: [Character] = Array("ㅏㅐㅑㅒㅓㅔㅕㅖㅗㅘㅙㅚㅛㅜㅝㅞㅟㅠㅡㅢㅣ")
    /// index 0 is "no final"
    static let jong: [Character] = ["\u{0}"] + Array("ㄱㄲㄳㄴㄵㄶㄷㄹㄺㄻㄼㄽㄾㄿㅀㅁㅂㅄㅅㅆㅇㅈㅊㅋㅌㅍㅎ")
    static let vowelPairs: [String: Character] = ["ㅗㅏ": "ㅘ", "ㅗㅐ": "ㅙ", "ㅗㅣ": "ㅚ", "ㅜㅓ": "ㅝ", "ㅜㅔ": "ㅞ", "ㅜㅣ": "ㅟ", "ㅡㅣ": "ㅢ"]
    static let finalPairs: [String: Character] = [
        "ㄱㅅ": "ㄳ", "ㄴㅈ": "ㄵ", "ㄴㅎ": "ㄶ", "ㄹㄱ": "ㄺ", "ㄹㅁ": "ㄻ", "ㄹㅂ": "ㄼ",
        "ㄹㅅ": "ㄽ", "ㄹㅌ": "ㄾ", "ㄹㅍ": "ㄿ", "ㄹㅎ": "ㅀ", "ㅂㅅ": "ㅄ",
    ]

    static func compose(_ keys: [Character]) -> String {
        var out = ""
        var l: Character?
        var v: Character?
        var t: [Character] = []

        func flush() {
            if let lc = l, let vc = v, let li = cho.firstIndex(of: lc), let vi = jung.firstIndex(of: vc) {
                var ti = 0
                if let tc = t.count == 2 ? finalPairs[String(t)] : t.first { ti = jong.firstIndex(of: tc) ?? 0 }
                if let u = UnicodeScalar(0xAC00 + (li * 21 + vi) * 28 + ti) { out.append(Character(u)) }
            } else {
                if let lc = l { out.append(lc) }
                if let vc = v { out.append(vc) }
                out += String(t)
            }
            l = nil; v = nil; t = []
        }

        for k in keys {
            if cho.contains(k) {
                if l == nil && v == nil {
                    l = k
                } else if v == nil || l == nil {
                    flush(); l = k
                } else if t.isEmpty {
                    if jong.dropFirst().contains(k) { t = [k] } else { flush(); l = k }
                } else if t.count == 1, finalPairs[String(t) + String(k)] != nil {
                    t.append(k)
                } else {
                    flush(); l = k
                }
            } else if jung.contains(k) {
                if v == nil {
                    v = k
                } else if t.isEmpty {
                    if let c = vowelPairs[String(v!) + String(k)] { v = c } else { flush(); v = k }
                } else {
                    // the last final starts the next syllable: 각 + ㅏ = 가가
                    let moved = t.removeLast()
                    flush()
                    l = moved; v = k
                }
            } else {
                flush()
                out.append(k)
            }
        }
        flush()
        return out
    }
}
