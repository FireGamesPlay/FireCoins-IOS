import SwiftUI

@main
struct FireCoinsApp: App {
    @StateObject private var store = AppStore(repository: RemoteFireCoinsRepository())

    var body: some Scene {
        WindowGroup {
            RootView()
                .environmentObject(store)
                .preferredColorScheme(.dark)
        }
    }
}
