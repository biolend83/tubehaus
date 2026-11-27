import SwiftUI

/// A channel, as on YouTube's TV app: its banner across the top fading into the page, the picture,
/// name, handle and subscribers with Subscribe, then Videos / Shorts / Live / Playlists, four across.
struct ChannelView: View {
    let id: String
    @State private var library = Library.shared
    @State private var page: ChannelPage?
    @State private var videos = Feed()
    @State private var tab = YT.videosTab
    @State private var error: String?

    var body: some View {
        if let p = page {
            ItemGrid(items: videos.items, more: { await videos.more(YT.browseMore) }, columns: isShorts ? 6 : 4) {
                header(p)
                if videos.items.isEmpty {
                    Status(error: videos.loaded ? (videos.error ?? "Nothing here") : nil).frame(height: 400)
                }
            }
            .background(alignment: .top) {
                if !p.banner.isEmpty {
                    Picture(imageURL(p.banner), maxPixels: 2000)
                        .frame(height: 520)
                        .overlay(LinearGradient(colors: [Theme.bg.opacity(0.2), Theme.bg], startPoint: .top, endPoint: .bottom))
                        .clipped()
                        .ignoresSafeArea()
                }
            }
        } else {
            Status(error: error) { Task { await load() } }
                .task(id: id) { await load() }
        }
    }

    private var isShorts: Bool { tab == YT.channelTabs[1].params }

    private func header(_ p: ChannelPage) -> some View {
        VStack(alignment: .leading, spacing: 0) {
            HStack(alignment: .center, spacing: 40) {
                Avatar(url: imageURL(p.avatar), name: p.name, size: 180)
                VStack(alignment: .leading, spacing: 10) {
                    Text(p.name).font(Fonts.hero).lineLimit(1)
                    Text([p.handle, p.subsText].filter { !$0.isEmpty }.joined(separator: " · "))
                        .font(Fonts.bodyMedium).foregroundStyle(Theme.sub)
                    HStack(spacing: 20) {
                        SubscribeButton(channel: Channel(id: id, name: p.name, avatar: p.avatar))
                            .firstFocus()
                        if !videos.items.isEmpty {
                            FilledButton("Play all", icon: "play.fill") {
                                PlayerModel.shared.playAll(videos.items.compactMap(\.video))
                            }
                            FilledButton("Shuffle", icon: "shuffle") {
                                PlayerModel.shared.playAll(videos.items.compactMap(\.video), shuffled: true)
                            }
                        }
                    }
                    .padding(.top, 12)
                }
                Spacer()
            }
            .padding(.horizontal, Metrics.side).padding(.top, p.banner.isEmpty ? 64 : 220)
            .focusSection()
            HStack(spacing: 16) {
                ForEach(YT.channelTabs.filter { !(Settings.hideShorts && $0.name == "Shorts") }) { t in
                    Chip(t.name, selected: tab == t.params) {
                        guard tab != t.params else { return }
                        tab = t.params
                        Task { await loadTab() }
                    }
                }
            }
            .padding(.horizontal, Metrics.side).padding(.top, 40)
            .focusSection()
        }
    }

    private func load() async {
        error = nil
        do {
            let p = try await YT.channel(id)
            page = p
            videos.items = p.videos.items.filter(Feed.keep)
            videos.next = p.videos.next
            videos.loaded = true
            library.refresh(Channel(id: id, name: p.name, avatar: p.avatar))   // keeps the saved name and picture up to date
        } catch {
            self.error = error.localizedDescription
        }
    }

    private func loadTab() async {
        let f = Feed()
        videos = f
        let t = tab
        let channel = id
        await f.load { try await YT.channel(channel, tab: t).videos }
    }
}

/// A playlist, as on YouTube's TV app: its picture, title and Play all / Shuffle on the left, the
/// videos numbered on the right.
struct PlaylistView: View {
    let id: String
    @State private var page: PlaylistPage?
    @State private var videos = Feed()
    @State private var error: String?

    var body: some View {
        if let p = page {
            let list = videos.items.compactMap(\.video)
            HStack(alignment: .top, spacing: 0) {
                VStack(alignment: .leading, spacing: 24) {
                    if let first = list.first {
                        Thumb(urls: first.pictures, radius: 24)
                    }
                    Text(p.title).font(Fonts.titleLarge).lineLimit(3)
                    Text([p.author, p.countText].filter { !$0.isEmpty }.joined(separator: " · "))
                        .font(Fonts.bodyMedium).foregroundStyle(Theme.sub)
                    if !list.isEmpty {
                        VStack(alignment: .leading, spacing: 16) {
                            FilledButton("Play all", icon: "play.fill") { PlayerModel.shared.playAll(list) }
                                .firstFocus()
                            FilledButton("Shuffle", icon: "shuffle") { PlayerModel.shared.playAll(list, shuffled: true) }
                        }
                        .focusSection()
                    }
                    Spacer()
                }
                .frame(width: 620)
                .padding(.leading, Metrics.side).padding(.top, 64)
                ItemList(items: videos.items, more: { await videos.more(YT.browseMore) }, numbered: true) { EmptyView() }
                    .focusSection()
            }
            .background {
                if let first = list.first {
                    Picture(first.pictures).blur(radius: 60).opacity(0.25).ignoresSafeArea()
                }
            }
        } else {
            Status(error: error) { Task { await load() } }
                .task(id: id) { await load() }
        }
    }

    private func load() async {
        error = nil
        do {
            let p = try await YT.playlist(id)
            page = p
            videos.items = p.videos.items
            videos.next = p.videos.next
        } catch {
            self.error = error.localizedDescription
        }
    }
}
