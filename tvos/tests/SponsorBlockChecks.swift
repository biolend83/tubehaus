// From tvos/: swiftc Sources/SponsorBlock.swift tests/SponsorBlockChecks.swift -o /tmp/tubehaus-sponsorblock-checks
// Then run /tmp/tubehaus-sponsorblock-checks.
import Foundation

@main
struct SponsorBlockChecks {
    static func main() async {
        let videoID = "gZd0IBxfheg"
        // printf %s gZd0IBxfheg | shasum -a 256
        precondition(SponsorBlock.prefix(of: videoID) == "a1b2")
        let url = SponsorBlock.lookupURL(for: videoID, includeFiller: false)
        let query = URLComponents(url: url, resolvingAgainstBaseURL: false)?.queryItems
        precondition(url.host == "sponsor.ajay.app" && url.path == "/api/skipSegments/a1b2")
        precondition(!url.absoluteString.contains(videoID))
        precondition(query?.first(where: { $0.name == "actionTypes" })?.value == "[\"skip\",\"poi\"]")
        precondition(query?.first(where: { $0.name == "categories" })?.value?.contains("poi_highlight") == true)
        precondition(!url.absoluteString.contains("filler"))
        precondition(SponsorBlock.lookupURL(for: videoID, includeFiller: true).absoluteString.contains("%22filler%22"))

        let fixture = """
        [
          {"videoID":"another-video","segments":[{"UUID":"other","category":"sponsor","actionType":"skip","segment":[0,30]}]},
          {"videoID":"gZd0IBxfheg","segments":[
            {"UUID":"highlight","category":"poi_highlight","actionType":"poi","segment":[90]},
            {"UUID":"sponsor","category":"sponsor","actionType":"skip","segment":[10.657,64.795]},
            {"UUID":"intro","category":"intro","actionType":"skip","segment":[6,14]},
            {"UUID":"invalid","category":"sponsor","actionType":"mute","segment":[1,10]},
            {"UUID":"invalid","category":"intro","actionType":"skip","segment":[14,6]},
            {"UUID":"invalid","category":"unknown","actionType":"skip","segment":[0,30]},
            {"UUID":"invalid","category":"poi_highlight","actionType":"skip","segment":[0,30]}
          ]}
        ]
        """
        let data = Data(fixture.utf8)
        let segments = SponsorBlock.parse(data, videoID: videoID)!
        precondition(segments.map(\.id) == ["intro", "sponsor", "highlight"])
        precondition(SponsorBlock.parse(data, videoID: "missing") == [])
        precondition(SponsorBlock.parse(Data("invalid".utf8), videoID: videoID) == nil)
        let intro = segments[0], sponsor = segments[1], highlight = segments[2]
        precondition(highlight.start == 90 && highlight.end == 90)
        precondition(sponsor.start == 10.657 && sponsor.end == 64.795)

        let defaults = SponsorBlock.Category.defaultAutoSkip
        precondition(defaults == [.sponsor, .selfpromo, .interaction])
        func decide(_ position: Double, skipped: Set<String> = [], playing: Bool = true,
                    autoSkip: Set<SponsorBlock.Category> = defaults) -> SponsorBlock.Decision? {
            SponsorBlock.decision(at: position, segments: segments, autoSkip: autoSkip,
                                  skipped: skipped, playing: playing)
        }
        precondition(decide(0) == .offerHighlight(highlight))
        precondition(decide(6) == .offerSkip(intro))
        precondition(decide(10.657) == .autoSkip(sponsor), "auto-skip wins overlaps")
        precondition(decide(11, playing: false) == .offerSkip(intro), "never auto-skip while paused")
        precondition(decide(11, skipped: [sponsor.id]) == .offerSkip(intro))
        precondition(decide(15, skipped: [sponsor.id, highlight.id]) == nil, "allow replay after Undo")
        precondition(decide(25, autoSkip: []) == .offerSkip(sponsor))
        precondition(decide(64.494) == .autoSkip(sponsor))
        precondition(decide(64.495) == nil, "do not skip within 0.3 s of the end")
        precondition(decide(20, skipped: [sponsor.id]) == nil, "highlight expires at 20 s")
        precondition(decide(.nan) == nil)
        await checkCache(videoID, data, segments)
        print("SponsorBlock prefix, URL, parsing, defaults, segment selection and cache checks passed.")
    }

    private static func checkCache(_ videoID: String, _ data: Data, _ segments: [SponsorBlock.Segment]) async {
        let stub = Stub(data: data)
        let client = SponsorBlockClient { try await stub.load($0) }
        for _ in 0..<3 {
            let result = await client.segments(for: videoID, includeFiller: false)
            precondition(result.isEmpty)
        }
        async let first = client.segments(for: videoID, includeFiller: false)
        async let second = client.segments(for: videoID, includeFiller: false)
        let answers = await [first, second]
        precondition(answers == [segments, segments])
        for _ in 0..<2 {
            let missing = await client.segments(for: "missing", includeFiller: false)
            precondition(missing.isEmpty)
        }
        let requests = await stub.requests
        precondition(requests == 5, "retry failures, share a request, and cache both 200 and 404")
    }

    private actor Stub {
        let data: Data
        var requests = 0

        init(data: Data) { self.data = data }

        func load(_ url: URL) async throws -> (Data, URLResponse) {
            requests += 1
            let request = requests
            if request == 1 { throw URLError(.timedOut) }
            if request == 4 { try await Task.sleep(for: .milliseconds(50)) }
            let status = request == 2 ? 503 : request > 4 ? 404 : 200
            let body = request == 3 ? Data("invalid".utf8) : data
            return (body, HTTPURLResponse(url: url, statusCode: status, httpVersion: nil, headerFields: nil)!)
        }
    }
}
