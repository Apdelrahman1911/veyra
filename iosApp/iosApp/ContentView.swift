import SwiftUI
import Veyra

struct ContentView: View {
    @Environment(\.scenePhase) private var scenePhase

    var body: some View {
        VeyraComposeView()
            .ignoresSafeArea()
            .onChange(of: scenePhase) { phase in
                switch phase {
                case .active: IosAppLifecycle.shared.didBecomeActive()
                case .inactive: IosAppLifecycle.shared.willResignActive()
                case .background: IosAppLifecycle.shared.didEnterBackground()
                @unknown default: break
                }
            }
    }
}

private struct VeyraComposeView: UIViewControllerRepresentable {
    func makeUIViewController(context: Context) -> UIViewController {
        MainViewControllerKt.MainViewController()
    }

    func updateUIViewController(_ uiViewController: UIViewController, context: Context) {}
}
