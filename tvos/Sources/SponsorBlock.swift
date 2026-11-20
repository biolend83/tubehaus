import CryptoKit
import Foundation

enum SponsorBlock {
    enum Category: String, CaseIterable {
        case sponsor, selfpromo, interaction, intro, outro, preview
        case musicOfftopic = "music_offtopic"
        case filler
        case highlight = "poi_highlight"

        static let skippable = allCases.filter { $0 != .highlight }
        static let defaultAutoSkip: Set<Self> = [.sponsor, .selfpromo, .interaction]

        var label: String {
            switch self {
            case .sponsor: return "Sponsor"
            case .selfpromo: return "Self-promotion"
            case .interaction: return "Subscribe and like reminders"
            case .intro: return "Intro"
            case .outro: return "Endcards and credits"
            case .preview: return "Preview and recap"
            case .musicOfftopic: return "Non-music part"
            case .filler: return "Filler"
            case .highlight: return "Highlight"
            }
        }
    }

    struct Segment: Equatable {
        let id: String
        let category: Category
        let start: Double
        let end: Double
    }

    enum Decision: Equatable {
        case autoSkip(Segment)
        case offerSkip(Segment)
        case offerHighlight(Segment)
    }

    static func prefix(of videoID: String) -> String {
        SHA256.hash(data: Data(videoID.utf8)).prefix(2).map { String(format: "%02x", $0) }.joined()
    }

    static func lookupURL(for videoID: String, includeFiller: Bool) -> URL {
        var url = URLComponents()
        url.scheme = "https"
        url.host = "sponsor.ajay.app"
        url.path = "/api/skipSegments/\(prefix(of: videoID))"
        let categories = Category.allCases.filter { includeFiller || $0 != .filler }
        let values = categories.map { "%22\($0.rawValue)%22" }.joined(separator: "%2C")
        url.percentEncodedQuery = "categories=%5B\(values)%5D&actionTypes=%5B%22skip%22%2C%22poi%22%5D"
        return url.url!
    }

    static func parse(_ data: Data, videoID: String) -> [Segment]? {
        guard let matches = (try? JSONSerialization.jsonObject(with: data)) as? [[String: Any]] else { return nil }
        guard let match = matches.first(where: { $0["videoID"] as? String == videoID }) else { return [] }
        guard let segments = match["segments"] as? [Any] else { return nil }

        return segments.compactMap { raw -> Segment? in
            guard let raw = raw as? [String: Any],
                  let key = raw["category"] as? String, let category = Category(rawValue: key),
                  let times = raw["segment"] as? [Double],
                  let start = times.first, start.isFinite, start >= 0 else { return nil }
            let action = raw["actionType"] as? String ?? "skip"
            let end = times.dropFirst().first ?? start
            if category == .highlight {
                guard action == "poi" else { return nil }
            } else {
                guard action == "skip", end.isFinite, end > start else { return nil }
            }
            return Segment(id: raw["UUID"] as? String ?? "\(key)\(start)", category: category,
                           start: start, end: category == .highlight ? start : end)
        }.sorted { $0.start < $1.start }
    }

    static func decision(at position: Double, segments: [Segment], autoSkip: Set<Category>,
                         skipped: Set<String>, playing: Bool) -> Decision? {
        guard position.isFinite, position >= 0 else { return nil }
        let hits = segments.filter {
            $0.category != .highlight && !skipped.contains($0.id) &&
                position >= $0.start && position < $0.end - 0.3
        }
        if playing, let segment = hits.first(where: { autoSkip.contains($0.category) }) {
            return .autoSkip(segment)
        }
        if let segment = hits.first(where: { !autoSkip.contains($0.category) }) {
            return .offerSkip(segment)
        }
        if position < 20, let highlight = segments.first(where: {
            $0.category == .highlight && !skipped.contains($0.id) && position < $0.start - 2
        }) {
            return .offerHighlight(highlight)
        }
        return nil
    }
}

/// Each video is asked for once, unless the request failed.
actor SponsorBlockClient {
    static let shared = SponsorBlockClient()
    typealias Loader = @Sendable (URL) async throws -> (Data, URLResponse)

    private struct Key: Hashable {
        let videoID: String
        let includeFiller: Bool
    }

    private let load: Loader
    private var cached: [Key: [SponsorBlock.Segment]] = [:]
    private var pending: [Key: Task<[SponsorBlock.Segment], Never>] = [:]

    init() {
        let config = URLSessionConfiguration.ephemeral
        config.timeoutIntervalForRequest = 4
        config.timeoutIntervalForResource = 4
        let session = URLSession(configuration: config)
        load = { try await session.data(from: $0) }
    }

    init(load: @escaping Loader) { self.load = load }

    func segments(for videoID: String, includeFiller: Bool) async -> [SponsorBlock.Segment] {
        let key = Key(videoID: videoID, includeFiller: includeFiller)
        if let result = cached[key] { return result }
        if let request = pending[key] { return await request.value }
        let request = Task {
            let answer = await fetch(key)
            pending[key] = nil
            if let answer { cached[key] = answer }
            return answer ?? []
        }
        pending[key] = request
        return await request.value
    }

    private func fetch(_ key: Key) async -> [SponsorBlock.Segment]? {
        let url = SponsorBlock.lookupURL(for: key.videoID, includeFiller: key.includeFiller)
        guard let (data, response) = try? await load(url), let http = response as? HTTPURLResponse else { return nil }
        switch http.statusCode {
        case 200: return SponsorBlock.parse(data, videoID: key.videoID)
        case 404: return []
        default: return nil
        }
    }
}
