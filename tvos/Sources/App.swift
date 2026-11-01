import SwiftUI

/// TubeHaus for Apple TV: the same YouTube engine as the iPad app, with screens made for the
/// Siri Remote (SwiftUI focus, AVPlayerViewController for playback). Everything stays on the TV.
@main
struct TubeHausTV: App {
    var body: some Scene {
        WindowGroup {
            RootView()
                .task { await Engine.shared.update() }
        }
    }
}

/// where a card leads
enum Route: Hashable {
    case video(Video)
    case channel(String)
    case playlist(String)
}

extension Item {
    var route: Route {
        switch self {
        case .video(let v): return .video(v)
        case .channel(let c): return .channel(c.id)
        case .playlist(let p): return .playlist(p.id)
        }
    }
}

struct RootView: View {
    enum Tab: Hashable { case home, search, subscriptions, library, settings }
    @State private var tab = Tab.home

    var body: some View {
        TabView(selection: $tab) {
            Stack { HomeView() }
                .tabItem { Label("Home", systemImage: "house") }.tag(Tab.home)
            Stack { SearchView() }
                .tabItem { Label("Search", systemImage: "magnifyingglass") }.tag(Tab.search)
            Stack { SubscriptionsView() }
                .tabItem { Label("Subscriptions", systemImage: "person.2") }.tag(Tab.subscriptions)
            Stack { LibraryView() }
                .tabItem { Label("Library", systemImage: "clock.arrow.circlepath") }.tag(Tab.library)
            Stack { SettingsView() }
                .tabItem { Label("Settings", systemImage: "gearshape") }.tag(Tab.settings)
        }
    }
}

/// one tab's navigation; `-open <video id>` at launch goes straight to that video (used by the CI check)
struct Stack<Content: View>: View {
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
                    }
                }
        }
        .onAppear {
            if let id = UserDefaults.standard.string(forKey: "open"), path.isEmpty, Content.self == HomeView.self {
                path.append(Route.video(Video(id: id, title: "", author: "", channelId: "", lengthSec: 0)))
            }
        }
    }
}
