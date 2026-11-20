import Foundation
import GoogleSignIn
import SwiftUI
import UIKit

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
                .background(store.settings.darkModeEnabled ? Color.black : Color.clear)
                .onChange(of: store.settings.darkModeEnabled, initial: true) { _, darkMode in
                    Self.applyOLEDAppearance(darkMode: darkMode)
                }
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

    private static func applyOLEDAppearance(darkMode: Bool) {
        let navAppearance = UINavigationBarAppearance()
        let tabAppearance = UITabBarAppearance()
        if darkMode {
            navAppearance.configureWithOpaqueBackground()
            navAppearance.backgroundColor = .black
            navAppearance.shadowColor = UIColor(white: 0.14, alpha: 1)
            navAppearance.titleTextAttributes = [.foregroundColor: UIColor.white]
            navAppearance.largeTitleTextAttributes = [.foregroundColor: UIColor.white]

            tabAppearance.configureWithOpaqueBackground()
            tabAppearance.backgroundColor = .black
            tabAppearance.shadowColor = UIColor(white: 0.14, alpha: 1)

            UITableView.appearance().backgroundColor = .black
            UICollectionView.appearance().backgroundColor = .black
        } else {
            navAppearance.configureWithDefaultBackground()
            tabAppearance.configureWithDefaultBackground()
            UITableView.appearance().backgroundColor = nil
            UICollectionView.appearance().backgroundColor = nil
        }
        UINavigationBar.appearance().standardAppearance = navAppearance
        UINavigationBar.appearance().compactAppearance = navAppearance
        UINavigationBar.appearance().scrollEdgeAppearance = navAppearance
        UITabBar.appearance().standardAppearance = tabAppearance
        UITabBar.appearance().scrollEdgeAppearance = tabAppearance
    }
}
