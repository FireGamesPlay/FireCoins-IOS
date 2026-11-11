import SwiftUI

@main
struct FireCoinsApp: App {
    @UIApplicationDelegateAdaptor(FireCoinsPushDelegate.self) private var pushDelegate
    @StateObject private var store = AppStore(repository: RemoteFireCoinsRepository())

    var body: some Scene {
        WindowGroup {
            RootView()
                .environmentObject(store)
                .preferredColorScheme(.dark)
        }
    }
}
