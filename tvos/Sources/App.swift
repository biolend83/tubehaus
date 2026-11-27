import SwiftUI

/// TubeHaus for Apple TV: the same YouTube engine as the iPad app, laid out as YouTube's own TV
/// app: the guide down the left (it opens when the remote goes onto it), shelves of videos, and
/// videos full screen with the controls over them. Everything stays on the TV.
@main
struct TubeHausTV: App {
    var body: some Scene {
        WindowGroup {
            RootView()
                .preferredColorScheme(.dark)
                .tint(Theme.red)
                .task { await Engine.shared.update() }
                .task {
                    // `-pick Music,Science` at launch: topics picked as on the first start (the CI check)
                    if let p = UserDefaults.standard.string(forKey: "pick"), Library.shared.needsTopics {
                        Library.shared.pickTopics(p.components(separatedBy: ","))
                    }
                    await ImportServer.fillIn()
                }
                .task {
                    // a connected TubeHaus server: its library now and every five minutes, changes sent as they happen
                    while !Task.isCancelled {
                        await Server.shared.sync()
                        try? await Task.sleep(nanoseconds: 300_000_000_000)
                    }
                }
        }
    }
}

/// where a card or button leads
enum Route: Hashable {
    case video(Video)
    case channel(String)
    case playlist(String)
    case search(String)
    case importSubs
    case topics
    case history
    case later
    case liked
    case learned
    case manageSubs
    case musicAlbum(String)
    case musicArtist(String)
    case nowPlaying

    var isVideo: Bool { if case .video = self { return true } else { return false } }
    var isSearch: Bool { if case .search = self { return true } else { return false } }
}

enum Tab: String, Hashable {
    case search, home, shorts, subscriptions, music, library, settings
}

/// Where the app is: the place picked in the guide and the pages opened on top of it. Opening
/// another place starts it afresh.
@MainActor
@Observable
final class Nav {
    static let shared = Nav()

    var tab: Tab
    var path: [Route] = []
    /// what the Search page starts with (`-tab search -q …`, a search pushed from elsewhere)
    var query = ""

    private init() {
        // `-tab library` at launch opens that tab, `-tab search -q …` searches (the CI check)
        let d = UserDefaults.standard
        let t = d.string(forKey: "tab") ?? ""
        tab = Tab(rawValue: t) ?? .home
        if t == "search" { query = d.string(forKey: "q") ?? "" }
        if let id = d.string(forKey: "open") {
            path = [.video(Video(id: id, title: "", author: "", channelId: "", lengthSec: 0))]
        } else if let id = d.string(forKey: "channel") {
            path = [.channel(id)]
        } else if let id = d.string(forKey: "playlist") {
            path = [.playlist(id)]
        } else if d.bool(forKey: "import") {
            path.append(.importSubs)
        }
    }

    func go(_ t: Tab, start: Video? = nil) {
        if let start { ShortsModel.shared.begin(with: start) }
        tab = t
        path = []
    }

    func push(_ r: Route) {
        if case .search(let q) = r { query = q; go(.search); return }
        path.append(r)
    }

    func open(_ v: Video) { push(.video(v)) }

    func back() { if !path.isEmpty { path.removeLast() } }

    /// another video in place of the one being watched (Up next, the next one when one ends)
    func replace(with v: Video) {
        if let last = path.last, last.isVideo { path[path.count - 1] = .video(v) } else { path.append(.video(v)) }
    }

    var top: Route? { path.last }
    var watching: Bool { top?.isVideo ?? false }
}

/// a place in the guide
enum GuideItem: Hashable {
    case profile
    case tab(Tab)
}

struct RootView: View {
    @State private var nav = Nav.shared
    @State private var player = PlayerModel.shared
    @State private var server = Server.shared
    /// "Who's watching?" is asked once each time the app opens (not when a launch argument opens a page: the CI check)
    @State private var asked = Self.skipWho
    @FocusState private var guide: GuideItem?
    /// the page, not the guide, gets the focus when the app starts and when the remote leaves the guide
    @Namespace private var root
    @Environment(\.resetFocus) private var resetFocus

    private static var skipWho: Bool {
        let d = UserDefaults.standard
        if d.bool(forKey: "who") { return false }
        return ["tab", "open", "channel", "playlist"].contains { d.string(forKey: $0) != nil } || d.bool(forKey: "import")
            || People.everyone.count < 2
    }

    var body: some View {
        if !asked {
            WhoIsWatching { asked = true }
        } else {
            main
        }
    }

    private var showsGuide: Bool { !nav.watching && nav.top != .nowPlaying }
    private var guideOpen: Bool { guide != nil }

    private var main: some View {
        ZStack(alignment: .leading) {
            NavigationStack(path: $nav.path) {
                tabRoot
                    .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .topLeading)
                    .background(Theme.bg)
                    .navigationDestination(for: Route.self) { destination($0) }
            }
            .id(nav.tab)
            .focusSection()
            .prefersDefaultFocus(in: root)
            .padding(.leading, showsGuide ? Metrics.guide : 0)
            .frame(maxWidth: .infinity, maxHeight: .infinity)

            if showsGuide {
                LinearGradient(stops: [.init(color: .black.opacity(0.95), location: 0), .init(color: .black.opacity(0.75), location: 0.45),
                                       .init(color: .clear, location: 1)],
                               startPoint: .leading, endPoint: .trailing)
                    .frame(width: 1200)
                    .opacity(guideOpen ? 1 : 0)
                    .allowsHitTesting(false)
                Guide(focus: $guide, open: guideOpen, music: server.connected) { asked = false }
                    .focusSection()
                    // the open guide covers the page's left edge, so right goes back to the page itself
                    .onMoveCommand { if $0 == .right { resetFocus(in: root) } }
            }
        }
        .background(Theme.bg)
        .foregroundStyle(Theme.text)
        .ignoresSafeArea()
        .focusScope(root)
        .animation(.easeOut(duration: 0.2), value: guideOpen)
        // Back on a place's first page goes to the guide, then Home; on Home's guide it leaves the app
        .onExitCommand(perform: exitAction)
        .onChange(of: nav.path) { player.follow(nav.path) }
        .onChange(of: nav.tab) { player.follow(nav.path) }
    }

    private var exitAction: (() -> Void)? {
        guard nav.path.isEmpty else { return nil }
        if guide == nil { return { guide = .tab(nav.tab) } }
        if nav.tab != .home { return { nav.go(.home); guide = .tab(.home) } }
        return nil
    }

    @ViewBuilder private var tabRoot: some View {
        switch nav.tab {
        case .search: SearchView(initial: nav.query)
        case .home: HomeView()
        case .shorts: ShortsView()
        case .subscriptions: SubscriptionsView()
        case .music: MusicView()
        case .library: LibraryView()
        case .settings: SettingsView()
        }
    }

    @ViewBuilder private func destination(_ r: Route) -> some View {
        Group {
            switch r {
            case .video(let v): WatchView(video: v)
            case .channel(let id): ChannelView(id: id)
            case .playlist(let id): PlaylistView(id: id)
            case .search(let q): SearchView(initial: q)
            case .importSubs: ImportView()
            case .topics: TopicPicker()
            case .history: HistoryView()
            case .later: LocalList(kind: .later)
            case .liked: LocalList(kind: .liked)
            case .learned: LearnedView()
            case .manageSubs: ManageSubsView()
            case .musicAlbum(let id): MusicPageView(id: id, artist: false)
            case .musicArtist(let id): MusicPageView(id: id, artist: true)
            case .nowPlaying: NowPlaying()
            }
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .topLeading)
        .background(Theme.bg)
        .toolbar(.hidden, for: .navigationBar)
    }
}

/// The guide down the left: icons only, and their names too while the remote is on it.
struct Guide: View {
    var focus: FocusState<GuideItem?>.Binding
    let open: Bool
    let music: Bool
    let switchPerson: () -> Void
    @State private var nav = Nav.shared

    var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            HStack(spacing: 18) {
                HouseMark().frame(width: 56, height: 48)
                if open { Text("TubeHaus").font(.system(size: 34, weight: .bold)).tracking(-1).transition(.opacity) }
            }
            .padding(.leading, 38).padding(.top, 52).padding(.bottom, 30)
            if People.everyone.count > 1 || People.list.names.count > 0 {
                item(.profile, icon: "person.crop.circle", label: People.name(People.everyone.first { People.id($0) == People.current } ?? ""), letter: true)
            }
            item(.tab(.search), icon: "magnifyingglass", label: "Search")
            item(.tab(.home), icon: "house", selectedIcon: "house.fill", label: "Home")
            if !Settings.hideShorts { item(.tab(.shorts), icon: "bolt", selectedIcon: "bolt.fill", label: "Shorts") }
            item(.tab(.subscriptions), icon: "play.square.stack", selectedIcon: "play.square.stack.fill", label: "Subscriptions")
            if music { item(.tab(.music), icon: "music.note", label: "Music") }
            item(.tab(.library), icon: "play.rectangle.on.rectangle", selectedIcon: "play.rectangle.on.rectangle.fill", label: "You")
            Spacer()
            if let last = Library.shared.continueWatching.first?.video, open {
                Button { nav.open(last) } label: {
                    HStack(spacing: 18) {
                        Thumb(urls: last.cardPictures, progress: Library.shared.progress(last.id), radius: 10).frame(width: 150)
                        VStack(alignment: .leading, spacing: 4) {
                            Text("Resume").font(.system(size: 22, weight: .semibold)).dim()
                            Text(last.title).font(.system(size: 24, weight: .semibold)).lineLimit(2)
                        }
                    }
                    .padding(14)
                    .frame(width: 360, alignment: .leading)
                }
                .buttonStyle(.glow(radius: 18))
                .padding(.leading, 20)
                .transition(.opacity)
            }
            item(.tab(.settings), icon: "gearshape", selectedIcon: "gearshape.fill", label: "Settings")
                .padding(.bottom, 48)
        }
        .frame(width: open ? 420 : Metrics.guide, alignment: .leading)
        .frame(maxHeight: .infinity)
    }

    private func selected(_ i: GuideItem) -> Bool {
        if case .tab(let t) = i { return nav.tab == t }
        return false
    }

    private func item(_ i: GuideItem, icon: String, selectedIcon: String? = nil, label: String, letter: Bool = false) -> some View {
        let sel = selected(i)
        return Button {
            switch i {
            case .profile: switchPerson()
            case .tab(let t): nav.go(t)
            }
        } label: {
            Face(icon: sel ? (selectedIcon ?? icon) : icon, label: label, letter: letter ? String(label.prefix(1)).uppercased() : nil,
                 selected: sel, open: open)
        }
        .buttonStyle(GuideStyle(open: open))
        .focused(focus, equals: i)
        .padding(.leading, 20)
    }

    private struct GuideStyle: ButtonStyle {
        let open: Bool
        func makeBody(configuration: Configuration) -> some View { Lit(configuration: configuration, open: open) }

        private struct Lit: View {
            let configuration: ButtonStyleConfiguration
            let open: Bool
            @Environment(\.isFocused) private var focused

            var body: some View {
                configuration.label
                    .environment(\.lit, focused)
                    .background { Capsule().fill(focused ? Theme.focus : .clear) }
                    .scaleEffect(configuration.isPressed ? 0.96 : 1)
                    .animation(.easeOut(duration: 0.15), value: focused)
            }
        }
    }

    private struct Face: View {
        let icon: String
        let label: String
        let letter: String?
        let selected: Bool
        let open: Bool
        @Environment(\.lit) private var lit

        var body: some View {
            HStack(spacing: 26) {
                Group {
                    if let letter {
                        Text(letter.isEmpty ? "?" : letter).font(.system(size: 28, weight: .bold))
                            .foregroundStyle(lit ? Theme.focus : Theme.onFocus)
                            .frame(width: 48, height: 48)
                            .background(Circle().fill(lit ? Theme.onFocus : Theme.teal))
                    } else {
                        Image(systemName: icon).font(.system(size: 36, weight: selected ? .bold : .regular))
                    }
                }
                .frame(width: 52)
                if open {
                    Text(label).font(.system(size: 30, weight: selected ? .bold : .medium)).lineLimit(1)
                        .transition(.opacity)
                }
            }
            .foregroundStyle(lit ? Theme.onFocus : selected ? Theme.text : Theme.sub)
            .padding(.horizontal, 18)
            .frame(width: open ? 360 : 92, height: 84, alignment: .leading)
            .overlay(alignment: .leading) {
                if selected && !open && !lit {
                    Capsule().fill(Theme.red).frame(width: 6, height: 36).offset(x: -10)
                }
            }
        }
    }
}
