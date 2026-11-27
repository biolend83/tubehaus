import XCTest

/// The CI check of the Apple TV app: the Siri Remote pressed the way a person would, with a
/// picture of the screen after each step (kept as attachments, and written to $SHOTS when set).
/// Nothing is asserted beyond the app staying up: YouTube may answer a datacenter's IP with its
/// bot check, so the pictures are what's looked at.
final class WalkThrough: XCTestCase {
    private let remote = XCUIRemote.shared
    private var app: XCUIApplication!
    private var n = 0

    override func setUp() {
        continueAfterFailure = true
    }

    private func launch(_ args: [String]) {
        app?.terminate()
        app = XCUIApplication()
        app.launchArguments = args
        app.launch()
    }

    private func shot(_ name: String) {
        n += 1
        let s = XCUIScreen.main.screenshot()
        let a = XCTAttachment(screenshot: s)
        a.name = String(format: "%02d-%@", n, name)
        a.lifetime = .keepAlways
        add(a)
        if let dir = ProcessInfo.processInfo.environment["SHOTS"], !dir.isEmpty {
            try? s.pngRepresentation.write(to: URL(fileURLWithPath: dir).appendingPathComponent(String(format: "%02d-%@.png", n, name)))
        }
    }

    private func press(_ b: XCUIRemote.Button, _ times: Int = 1, wait: Double = 0.6) {
        for _ in 0..<times {
            remote.press(b)
            Thread.sleep(forTimeInterval: wait)
        }
    }

    private func pause(_ s: Double) { Thread.sleep(forTimeInterval: s) }

    func testWalkThrough() {
        // first start: topics to pick
        launch([])
        pause(20); shot("topics")

        // Home, from picked topics
        launch(["-pick", "Music,Science"])
        pause(40); shot("home")
        press(.down, 2); pause(2); shot("home-shelf")
        press(.left, 3); pause(1.5); shot("guide-open")
        press(.right); press(.up, 3); pause(1)

        // the big card's Play: the player, its controls, then the video alone
        press(.select); pause(30); shot("player-controls")
        pause(9); shot("player-clean")
        press(.right, wait: 0.3); press(.right, wait: 0.3); press(.right, wait: 0.2); shot("player-scrub")
        pause(3)
        press(.down); press(.down, wait: 1); press(.down, wait: 1.5); shot("player-shelves")
        press(.up, 3)
        press(.down); press(.right, 16, wait: 0.25); press(.select, wait: 1.5); shot("player-settings")
        press(.select, wait: 1.5); shot("player-quality")
        press(.menu, wait: 1); press(.menu, wait: 1); press(.menu, wait: 1)
        press(.menu, wait: 2); shot("back-from-player")

        // search: the keyboard and suggestions, results as you type
        launch(["-tab", "search"])
        pause(14); shot("search")
        press(.down, 2); press(.select, wait: 0.4); press(.right, wait: 0.3); press(.select, wait: 0.4); press(.down, wait: 0.3); press(.select)
        pause(5); shot("search-typed")
        launch(["-tab", "search", "-q", "lofi hip hop"])
        pause(25); shot("search-results")

        launch(["-tab", "subscriptions", "-import", "YES"])
        pause(15); shot("import")
        launch(["-tab", "subscriptions"])
        pause(30); shot("subscriptions")
        launch(["-tab", "library"])
        pause(8); shot("you")
        launch(["-tab", "settings"])
        pause(6); shot("settings")
        press(.down, 1); pause(1); shot("settings-playback")
        launch(["-channel", "UCX6OQ3DkcsbYNE6H8uQQuVA"])
        pause(30); shot("channel")
        launch(["-playlist", "PLFgquLnL59alCl_2TQvOiD5Vgm1hCaGSI"])
        pause(25); shot("playlist")
        launch(["-tab", "shorts"])
        pause(40); shot("shorts")
        launch(["-open", "aqz-KE-bpKQ"])
        pause(40); shot("video-by-id")
        press(.select, wait: 1); shot("paused")
        launch(["-tab", "library"])
        pause(8); shot("you-after")
        XCTAssertTrue(app.state == .runningForeground, "the app is still running")
    }
}
