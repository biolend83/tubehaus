import SwiftUI

/// TubeHaus for Apple TV: the same YouTube engine as the iPad app, with the screens of the
/// Android TV app (common/src/app/tubehaus/ui): the TubeHaus bar along the top, the side menu on
/// the left, two big cards across, the video playing on its page with Up next beside it.
/// Everything stays on the TV.
@main
struct TubeHausTV: App {
    var body: some Scene {
        WindowGroup {
            RootView()
                .preferredColorScheme(.dark)
                .tint(Theme.teal)
                .task { await Engine.shared.update() }
                .task {
                    // `-pick Music,Science` at launch: topics picked as on the first start (the CI check)
                    if let p = UserDefaults.standard.string(forKey: "pick"), Library.shared.needsTopics {
                        Library.shared.pickTopics(p.components(separatedBy: ","))
                    }
                    await ImportServer.fillIn()
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

    var isVideo: Bool { if case .video = self { return true } else { return false } }
    var isSearch: Bool { if case .search = self { return true } else { return false } }
}

enum Tab: String, Hashable {
    case home, shorts, subscriptions, library, settings
}

/// Where the app is: the tab picked in the side menu and the pages opened on top of it. Opening
/// another tab starts it afresh, as on Android.
@MainActor
@Observable
final class Nav {
    static let shared = Nav()

    var tab: Tab
    var path: [Route] = []

    private init() {
        // `-tab library` at launch opens that tab, `-tab search -q …` searches (the CI check)
        let d = UserDefaults.standard
        let t = d.string(forKey: "tab") ?? ""
        tab = Tab(rawValue: t) ?? .home
        if t == "search" { path = [.search(d.string(forKey: "q") ?? "")] }
        if let id = d.string(forKey: "open") {
            path = [.video(Video(id: id, title: "", author: "", channelId: "", lengthSec: 0))]
        } else if let id = d.string(forKey: "channel") {
            path = [.channel(id)]
        } else if d.bool(forKey: "import") {
            path.append(.importSubs)
        }
    }

    func go(_ t: Tab) {
        tab = t
        path = []
    }

    func push(_ r: Route) { path.append(r) }

    func open(_ v: Video) { push(.video(v)) }

    func back() { if !path.isEmpty { path.removeLast() } }

    /// another video in place of the one being watched (Up next, the next one when one ends)
    func replace(with v: Video) {
        if let last = path.last, last.isVideo { path[path.count - 1] = .video(v) } else { path.append(.video(v)) }
    }

    var top: Route? { path.last }
    var watching: Bool { top?.isVideo ?? false }

    /// the TubeHaus bar is left out where the page needs the whole height
    var showsTopBar: Bool {
        if let top { return !(top.isVideo || top.isSearch) }
        return tab != .shorts
    }
}

struct RootView: View {
    @State private var nav = Nav.shared
    @State private var player = PlayerModel.shared
    @State private var folded = Settings.railFolded

    var body: some View {
        VStack(spacing: 0) {
            if nav.showsTopBar {
                TopBar(folded: folded) { setFolded(false) }
                    .focusSection()
            }
            HStack(spacing: 0) {
                if !folded {
                    Rail { setFolded(true) }
                        .focusSection()
                }
                NavigationStack(path: $nav.path) {
                    tabRoot
                        .navigationDestination(for: Route.self) { destination($0) }
                }
                .id(nav.tab)
                .frame(maxWidth: .infinity, maxHeight: .infinity)
                // Back on a tab's first page goes Home; on Home it leaves the app
                .onExitCommand(perform: nav.path.isEmpty && nav.tab != .home ? { nav.go(.home) } : nil)
            }
        }
        .background(Theme.bg)
        .foregroundStyle(Theme.text)
        .ignoresSafeArea()
        .fullScreenCover(isPresented: $player.fullscreen, onDismiss: { player.leftFullscreen() }) {
            FullPlayer(model: player).ignoresSafeArea()
        }
        .onChange(of: nav.path) { player.follow(nav.path) }
        .onChange(of: nav.tab) { player.follow(nav.path) }
    }

    @ViewBuilder private var tabRoot: some View {
        switch nav.tab {
        case .home: HomeView()
        case .shorts: ShortsView()
        case .subscriptions: SubscriptionsView()
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
            }
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .topLeading)
        .background(Theme.bg)
        .toolbar(.hidden, for: .navigationBar)
    }

    private func setFolded(_ f: Bool) {
        withAnimation(.easeOut(duration: 0.2)) { folded = f }
        Settings.railFolded = f
    }
}

/// The bar along the top: ← when a page is open, the TubeHaus logo, and search (and settings,
/// when the side menu is folded away).
struct TopBar: View {
    let folded: Bool
    let unfold: () -> Void
    @State private var nav = Nav.shared

    var body: some View {
        VStack(spacing: 0) {
            HStack(spacing: 8) {
                if folded { IconButton(icon: "line.3.horizontal", action: unfold) }
                if !nav.path.isEmpty { IconButton(icon: "arrow.left") { nav.back() } }
                Button { nav.go(.home) } label: { Logo().padding(.horizontal, 12).frame(height: 84) }
                    .buttonStyle(.glow(radius: 16))
                Spacer()
                IconButton(icon: "magnifyingglass") { nav.push(.search("")) }
                if folded { IconButton(icon: "gearshape") { nav.go(.settings) } }
            }
            .padding(.leading, 16).padding(.trailing, 16)
            .frame(height: 104)
            Rectangle().fill(Theme.outline).frame(height: 2)
        }
    }
}

/// The side menu (Android's NavigationRail): fold, the four places, then Resume, Settings and
/// (where the top bar is hidden) Search.
struct Rail: View {
    let fold: () -> Void
    @State private var nav = Nav.shared
    @State private var library = Library.shared

    var body: some View {
        VStack(spacing: 12) {
            IconButton(icon: MenuOpen.name, action: fold)
                .padding(.top, 16).padding(.bottom, 24)
            RailItem(icon: "house.fill", label: "Home", selected: nav.tab == .home && nav.path.isEmpty) { nav.go(.home) }
            if !Settings.hideShorts {
                RailItem(icon: "bolt.fill", label: "Shorts", selected: nav.tab == .shorts && nav.path.isEmpty) { nav.go(.shorts) }
            }
            RailItem(icon: "play.square.stack", label: "Subscriptions", selected: nav.tab == .subscriptions && nav.path.isEmpty) { nav.go(.subscriptions) }
            RailItem(icon: "play.rectangle.on.rectangle", label: "Library", selected: nav.tab == .library && nav.path.isEmpty) { nav.go(.library) }
            Rectangle().fill(Theme.outline).frame(width: 80, height: 2).padding(.vertical, 12)
            if let last = library.continueWatching.first?.video, !nav.watching {
                RailItem(icon: "play.circle", label: "Resume", small: true, selected: false) { nav.open(last) }
            }
            RailItem(icon: "gearshape", label: "Settings", small: true, selected: nav.tab == .settings && nav.path.isEmpty) { nav.go(.settings) }
            if !nav.showsTopBar {
                RailItem(icon: "magnifyingglass", label: "Search", small: true, selected: nav.top?.isSearch ?? false) { nav.push(.search("")) }
            }
            Spacer()
        }
        .frame(width: 160)
    }
}

struct RailItem: View {
    let icon: String
    let label: String
    var small = false
    let selected: Bool
    let action: () -> Void

    var body: some View {
        Button(action: action) { EmptyView() }
            .buttonStyle(Look(icon: icon, label: label, small: small, selected: selected))
    }

    /// the icon in its pill (purple when it's where you are, teal when the remote is on it) and the name under it
    private struct Look: ButtonStyle {
        let icon: String
        let label: String
        let small: Bool
        let selected: Bool

        func makeBody(configuration: Configuration) -> some View {
            Focused(icon: icon, label: label, small: small, selected: selected, pressed: configuration.isPressed)
        }
    }

    private struct Focused: View {
        let icon: String
        let label: String
        let small: Bool
        let selected: Bool
        let pressed: Bool
        @Environment(\.isFocused) private var focused

        var body: some View {
            VStack(spacing: 8) {
                ZStack {
                    Capsule().fill(selected ? Theme.pill : Color.clear)
                    Capsule().fill(focused ? Theme.glow : Color.clear)
                    Image(systemName: icon).font(.system(size: small ? 36 : 44))
                        .foregroundStyle(selected ? Theme.onPill : (focused ? Theme.text : Theme.sub))
                }
                .frame(width: 112, height: 64)
                Text(label).font(.system(size: 22, weight: selected ? .semibold : .medium))
                    .foregroundStyle(selected || focused ? Theme.text : Theme.sub)
                    .lineLimit(1).minimumScaleFactor(0.8)
            }
            .frame(width: 160)
            .contentShape(Rectangle())
            .scaleEffect(pressed ? 0.95 : focused ? 1.06 : 1)
            .animation(.easeOut(duration: 0.15), value: focused)
        }
    }
}
