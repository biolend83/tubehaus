import SwiftUI

/// TubeHaus for Apple TV: the same YouTube engine as the iPad app, with screens made for the
/// Siri Remote (SwiftUI focus, AVPlayerViewController for playback). Everything stays on the TV.
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
    case importSubs
    case topics
}

struct RootView: View {
    enum Tab: String, Hashable { case home, search, subscriptions, library, settings }
    /// `-tab search` at launch opens that tab (used by the CI check)
    @State private var tab = Tab(rawValue: UserDefaults.standard.string(forKey: "tab") ?? "") ?? .home

    var body: some View {
        TabView(selection: $tab) {
            Stack(tab: .home) { HomeView() }
                .tabItem { Label("Home", systemImage: "house") }.tag(Tab.home)
            Stack(tab: .search) { SearchView() }
                .tabItem { Label("Search", systemImage: "magnifyingglass") }.tag(Tab.search)
            Stack(tab: .subscriptions) { SubscriptionsView() }
                .tabItem { Label("Subscriptions", systemImage: "person.2") }.tag(Tab.subscriptions)
            Stack(tab: .library) { LibraryView() }
                .tabItem { Label("Library", systemImage: "clock.arrow.circlepath") }.tag(Tab.library)
            Stack(tab: .settings) { SettingsView() }
                .tabItem { Label("Settings", systemImage: "gearshape") }.tag(Tab.settings)
        }
        .background(Color(white: 0.06).ignoresSafeArea())
    }
}

/// the launch options are acted on once
private var launchHandled: Set<String> = []

/// One tab's navigation. At launch (for the CI check): `-open <video id>` goes straight to that
/// video on Home, `-import YES` to the import screen of the tab opened.
struct Stack<Content: View>: View {
    let tab: RootView.Tab
    @ViewBuilder var content: Content
    @State private var path = NavigationPath()

    var body: some View {
        NavigationStack(path: $path) {
            content
                .navigationDestination(for: Route.self) { r in
                    switch r {
                    case .video(let v): WatchView(video: v)
                    case .channel(let id): ChannelView(id: id)
                    case .playlist(let id): PlaylistView(id: id)
                    case .importSubs: ImportView()
                    case .topics: TopicPicker()
                    }
                }
        }
        .onAppear {
            guard path.isEmpty, launchHandled.insert(tab.rawValue).inserted else { return }
            let d = UserDefaults.standard
            let opened = RootView.Tab(rawValue: d.string(forKey: "tab") ?? "") ?? .home
            guard tab == opened else { return }
            if let id = d.string(forKey: "open") {
                path.append(Route.video(Video(id: id, title: "", author: "", channelId: "", lengthSec: 0)))
            } else if d.bool(forKey: "import") {
                path.append(Route.importSubs)
            }
        }
    }
}
