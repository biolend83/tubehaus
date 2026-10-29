import SwiftUI
import TubeHausKit

// The whole app is Kotlin + Compose (TubeHausKit); this only puts it in a window.
@main
struct TubeHausApp: App {
    var body: some Scene {
        WindowGroup {
            ComposeView().ignoresSafeArea(.all)
        }
    }
}

struct ComposeView: UIViewControllerRepresentable {
    func makeUIViewController(context: Context) -> UIViewController { MainKt.MainViewController() }
    func updateUIViewController(_ controller: UIViewController, context: Context) {}
}
