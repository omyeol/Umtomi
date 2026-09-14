import Foundation
import SwiftUI

@main
struct UmtomiApp: App {
    @StateObject private var store = AppStore()

    var body: some Scene {
        WindowGroup {
            RootView(store: store)
                .environment(\.locale, Locale(identifier: store.settings.appLanguage.localeIdentifier))
                .preferredColorScheme(store.settings.darkModeEnabled ? .dark : .light)
        }
    }
}
