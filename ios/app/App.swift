import SwiftUI
import TubeHausKit

// The whole app is Kotlin + Compose (TubeHausKit); this only puts it in a window.
@main
struct TubeHausApp: App {
    @StateObject private var bars = Bars()

    var body: some Scene {
        WindowGroup {
            ComposeView(bars: bars).ignoresSafeArea(.all)
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
            DispatchQueue.main.async { if bars.hidden != hide { bars.hidden = hide } }
        })
    }
    func updateUIViewController(_ controller: UIViewController, context: Context) {}
}
