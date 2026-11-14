import SwiftUI
import TubeHausKit

// The whole app is Kotlin + Compose (TubeHausKit); this only puts it in a window.
@main
struct TubeHausApp: App {
    init() { setUpCommentTranslation() }

    @StateObject private var bars = Bars()

    var body: some Scene {
        WindowGroup {
            ComposeView(bars: bars).ignoresSafeArea(.all).commentTranslation()
                // in full screen: no clock, date, Wi-Fi and battery over the video, and the home bar fades away
                .statusBarHidden(bars.hidden)
                .persistentSystemOverlays(bars.hidden ? .hidden : .automatic)
                .animation(.easeInOut(duration: 0.2), value: bars.hidden)
        }
    }
}

/** whether the app is in full screen (the Kotlin side says so) */
final class Bars: ObservableObject {
    @Published var hidden = false
}

struct ComposeView: UIViewControllerRepresentable {
    let bars: Bars

    func makeUIViewController(context: Context) -> UIViewController {
        MainKt.MainViewController(hideBars: { [bars] on in
            let hide = on.boolValue
            DispatchQueue.main.async {
                if bars.hidden != hide { bars.hidden = hide; turn(sideways: hide) }
            }
        })
    }

    /**
     * iPhone: full screen turns the screen sideways, as YouTube's button does, also with the
     * orientation lock on; leaving it turns it back upright. Not when the phone is already held
     * that way (turning it is what opened full screen then), and not on the iPad, where the video
     * fills the screen either way.
     */
    private func turn(sideways: Bool) {
        guard UIDevice.current.userInterfaceIdiom == .phone,
              let scene = UIApplication.shared.connectedScenes.first(where: { $0 is UIWindowScene }) as? UIWindowScene
        else { return }
        if scene.interfaceOrientation.isLandscape == sideways { return }
        scene.requestGeometryUpdate(.iOS(interfaceOrientations: sideways ? .landscapeRight : .portrait)) { _ in }
    }
    func updateUIViewController(_ controller: UIViewController, context: Context) {}
}
