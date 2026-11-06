import UIKit
import Veyra

final class AppDelegate: NSObject, UIApplicationDelegate {
    func application(
        _ application: UIApplication,
        didFinishLaunchingWithOptions launchOptions: [UIApplication.LaunchOptionsKey: Any]? = nil
    ) -> Bool {
        IosAppLifecycle.shared.start()
        return true
    }

    func applicationWillTerminate(_ application: UIApplication) {
        IosAppLifecycle.shared.willTerminate()
    }
}
