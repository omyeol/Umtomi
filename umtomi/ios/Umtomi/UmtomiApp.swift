import Foundation
import GoogleSignIn
import SwiftUI

@main
@MainActor
struct UmtomiApp: App {
    @UIApplicationDelegateAdaptor(UmtomiSyncDelegate.self) private var syncDelegate
    @StateObject private var store = UmtomiSyncDelegate.store
    @StateObject private var lock = AppLock()
    @Environment(\.scenePhase) private var phase

    var body: some Scene {
        WindowGroup {
            UmtomiLockGate(store: store)
                .environmentObject(lock)
                .environment(\.locale, Locale(identifier: store.settings.appLanguage.localeIdentifier))
                .preferredColorScheme(store.settings.darkModeEnabled ? .dark : .light)
                .onOpenURL { url in _ = GIDSignIn.sharedInstance.handle(url) }
                .task(id: phase) {
                    if phase == .active {
                        while !Task.isCancelled {
                            await DriveSyncCoordinator.shared.foregroundTick(store: store)
                            do { try await Task.sleep(for: .seconds(30)) } catch { break }
                        }
                    } else if phase == .background {
                        DriveSyncCoordinator.shared.schedule()
                    }
                }
        }
    }
}
