import AVKit

/// The full screen player's skip buttons and the notice after a skip.
@MainActor
final class SponsorBlockPlayer {
    private enum Action: Equatable {
        case skip(SponsorBlock.Segment)
        case highlight(SponsorBlock.Segment)
        case undo(SponsorBlock.Segment)

        var title: String {
            switch self {
            case .skip(let segment):
                return "Skip \(segment.category.label)"
            case .highlight:
                return "Jump to highlight"
            case .undo:
                return "Undo"
            }
        }
    }

    private weak var controller: AVPlayerViewController?
    private var observedPlayer: AVPlayer?
    private var observer: Any?
    private weak var banner: UIView?
    private weak var bannerLabel: UILabel?
    private var action: Action?
    private var notice: SponsorBlock.Segment?
    private var hideNotice: Task<Void, Never>?

    private var lookup: Task<Void, Never>?
    private var segments: [SponsorBlock.Segment] = []
    private var autoSkip: Set<SponsorBlock.Category> = []
    private var skipped: Set<String> = []
    private var pendingSeek: UUID?

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
        showAction(nil)
    }

    func attach(to controller: AVPlayerViewController) {
        if let previous = self.controller { detach(from: previous) }
        guard let player = controller.player else { return }
        self.controller = controller
        observedPlayer = player
        addBanner(to: controller)
        observer = player.addPeriodicTimeObserver(forInterval: CMTime(value: 1, timescale: 4), queue: .main) { [weak self] time in
            MainActor.assumeIsolated { self?.tick(at: time.seconds) }
        }
    }

    func detach(from controller: AVPlayerViewController) {
        guard self.controller === controller else { return }
        if let observer { observedPlayer?.removeTimeObserver(observer) }
        observer = nil
        observedPlayer = nil
        clearNotice()
        showAction(nil)
        banner?.removeFromSuperview()
        self.controller = nil
    }

    private func tick(at position: Double) {
        guard let player = observedPlayer, player.currentItem?.status == .readyToPlay, pendingSeek == nil else { return }
        switch SponsorBlock.decision(at: position, segments: segments, autoSkip: autoSkip,
                                     skipped: skipped, playing: player.timeControlStatus == .playing) {
        case .autoSkip(let segment):
            skipped.insert(segment.id)
            showAction(nil)
            seek(to: segment.end, automatic: segment)
        case .offerSkip(let segment):
            showAction(notice.map(Action.undo) ?? .skip(segment))
        case .offerHighlight(let segment):
            showAction(notice.map(Action.undo) ?? .highlight(segment))
        case nil:
            showAction(notice.map(Action.undo))
        }
    }

    private func showAction(_ next: Action?) {
        guard action != next else { return }
        action = next
        guard let next else {
            controller?.contextualActions = []
            return
        }
        controller?.contextualActions = [UIAction(title: next.title) { [weak self] _ in
            MainActor.assumeIsolated { self?.perform(next) }
        }]
    }

    private func perform(_ selected: Action) {
        guard action == selected, pendingSeek == nil else { return }
        showAction(nil)
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

    private func seek(to target: Double, automatic segment: SponsorBlock.Segment? = nil) {
        guard let player = observedPlayer, let item = player.currentItem,
              let time = validTarget(target, in: item) else { return }
        let token = UUID()
        pendingSeek = token
        player.seek(to: time, toleranceBefore: .zero, toleranceAfter: .zero) { [weak self, weak player, weak item] finished in
            Task { @MainActor in
                guard let self, self.pendingSeek == token else { return }
                self.pendingSeek = nil
                guard finished, let player, let item, player.currentItem === item, let segment else { return }
                self.showNotice(for: segment)
            }
        }
    }

    private func validTarget(_ seconds: Double, in item: AVPlayerItem) -> CMTime? {
        let duration = item.duration.seconds
        let target = CMTime(seconds: seconds, preferredTimescale: 600)
        guard target.isNumeric, !duration.isFinite || seconds <= duration else { return nil }
        return target
    }

    private func showNotice(for segment: SponsorBlock.Segment) {
        guard controller != nil else { return }
        hideNotice?.cancel()
        notice = segment
        bannerLabel?.text = "Skipped: \(segment.category.label)"
        banner?.isHidden = false
        showAction(.undo(segment))
        hideNotice = Task { [weak self] in
            try? await Task.sleep(for: .seconds(3))
            guard !Task.isCancelled else { return }
            self?.clearNotice()
            self?.showAction(nil)
        }
    }

    private func clearNotice() {
        hideNotice?.cancel()
        hideNotice = nil
        notice = nil
        banner?.isHidden = true
    }

    private func addBanner(to controller: AVPlayerViewController) {
        guard let overlay = controller.contentOverlayView else { return }
        let banner = UIView()
        banner.backgroundColor = UIColor.black.withAlphaComponent(0.82)
        banner.layer.cornerRadius = 16
        banner.isUserInteractionEnabled = false
        banner.isHidden = true
        banner.translatesAutoresizingMaskIntoConstraints = false

        let label = UILabel()
        label.font = .systemFont(ofSize: 32, weight: .semibold)
        label.textColor = .white
        label.translatesAutoresizingMaskIntoConstraints = false
        banner.addSubview(label)
        overlay.addSubview(banner)
        NSLayoutConstraint.activate([
            banner.topAnchor.constraint(equalTo: overlay.safeAreaLayoutGuide.topAnchor, constant: 24),
            banner.trailingAnchor.constraint(equalTo: overlay.safeAreaLayoutGuide.trailingAnchor, constant: -24),
            label.leadingAnchor.constraint(equalTo: banner.leadingAnchor, constant: 28),
            label.trailingAnchor.constraint(equalTo: banner.trailingAnchor, constant: -28),
            label.topAnchor.constraint(equalTo: banner.topAnchor, constant: 16),
            label.bottomAnchor.constraint(equalTo: banner.bottomAnchor, constant: -16),
        ])
        self.banner = banner
        bannerLabel = label
    }
}
