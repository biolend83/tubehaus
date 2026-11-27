import AVFoundation
import Observation

/// SponsorBlock in the player: skips the categories set to skip on its own, offers "Skip …" for
/// the others (the player draws it as YouTube draws "Skip ad"), and says what was skipped with an
/// Undo for a few seconds.
@MainActor
@Observable
final class SponsorBlockPlayer {
    enum Action: Equatable {
        case skip(SponsorBlock.Segment)
        case highlight(SponsorBlock.Segment)
        case undo(SponsorBlock.Segment)

        var title: String {
            switch self {
            case .skip(let segment): return "Skip \(segment.category.label.lowercased())"
            case .highlight: return "Jump to highlight"
            case .undo: return "Undo skip"
            }
        }
    }

    /// what the player offers now (nil: nothing)
    private(set) var action: Action?
    /// "Skipped: Sponsor", for a few seconds after a skip
    private(set) var notice: SponsorBlock.Segment?
    /// the parts of the video, for marks on the seek bar
    private(set) var segments: [SponsorBlock.Segment] = []

    @ObservationIgnored private weak var player: AVPlayer?
    @ObservationIgnored private var observer: Any?
    @ObservationIgnored private var hideNotice: Task<Void, Never>?
    @ObservationIgnored private var lookup: Task<Void, Never>?
    @ObservationIgnored private var autoSkip: Set<SponsorBlock.Category> = []
    @ObservationIgnored private var skipped: Set<String> = []
    @ObservationIgnored private var pendingSeek: UUID?

    func attach(_ p: AVPlayer) {
        guard player !== p else { return }
        if let observer, let player { player.removeTimeObserver(observer) }
        player = p
        observer = p.addPeriodicTimeObserver(forInterval: CMTime(value: 1, timescale: 4), queue: .main) { [weak self] time in
            MainActor.assumeIsolated { self?.tick(at: time.seconds) }
        }
    }

    func open(_ id: String) {
        close()
        guard Settings.sponsorBlock else { return }
        autoSkip = Settings.sponsorBlockCategories
        let includeFiller = autoSkip.contains(.filler)
        lookup = Task { [weak self] in
            let segments = await SponsorBlockClient.shared.segments(for: id, includeFiller: includeFiller)
            guard !Task.isCancelled, let self else { return }
            self.segments = segments
        }
    }

    func close() {
        lookup?.cancel()
        lookup = nil
        segments = []
        replay()
    }

    func replay() {
        skipped.removeAll()
        pendingSeek = nil
        clearNotice()
        action = nil
    }

    /// OK on the offered button
    func perform() {
        guard let selected = action, pendingSeek == nil else { return }
        action = nil
        switch selected {
        case .skip(let segment):
            skipped.insert(segment.id)
            seek(to: segment.end)
        case .highlight(let segment):
            skipped.insert(segment.id)
            seek(to: segment.start)
        case .undo(let segment):
            clearNotice()
            seek(to: segment.start)
        }
    }

    private func tick(at position: Double) {
        guard let player, player.currentItem?.status == .readyToPlay, pendingSeek == nil else { return }
        let next: Action?
        switch SponsorBlock.decision(at: position, segments: segments, autoSkip: autoSkip,
                                     skipped: skipped, playing: player.timeControlStatus == .playing) {
        case .autoSkip(let segment):
            skipped.insert(segment.id)
            action = nil
            seek(to: segment.end, automatic: segment)
            return
        case .offerSkip(let segment):
            next = notice.map(Action.undo) ?? .skip(segment)
        case .offerHighlight(let segment):
            next = notice.map(Action.undo) ?? .highlight(segment)
        case nil:
            next = notice.map(Action.undo)
        }
        if action != next { action = next }
    }

    private func seek(to target: Double, automatic segment: SponsorBlock.Segment? = nil) {
        guard let player, let item = player.currentItem else { return }
        let duration = item.duration.seconds
        guard !duration.isFinite || target <= duration else { return }
        let token = UUID()
        pendingSeek = token
        player.seek(to: CMTime(seconds: target, preferredTimescale: 600), toleranceBefore: .zero, toleranceAfter: .zero) { [weak self] finished in
            Task { @MainActor in
                guard let self, self.pendingSeek == token else { return }
                self.pendingSeek = nil
                guard finished, let segment else { return }
                self.showNotice(for: segment)
            }
        }
    }

    private func showNotice(for segment: SponsorBlock.Segment) {
        hideNotice?.cancel()
        notice = segment
        action = .undo(segment)
        hideNotice = Task { [weak self] in
            try? await Task.sleep(for: .seconds(4))
            guard !Task.isCancelled else { return }
            self?.clearNotice()
            self?.action = nil
        }
    }

    private func clearNotice() {
        hideNotice?.cancel()
        hideNotice = nil
        notice = nil
    }
}
