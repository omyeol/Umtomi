import Foundation
import UserNotifications

@MainActor
final class DriveSyncNotifications: NSObject, UNUserNotificationCenterDelegate {
    static let shared = DriveSyncNotifications()
    nonisolated static let preferenceKey = "umtomi.drive.progressNotifications"
    nonisolated private static let identifier = "xyz.sabok.umtomi.drive-progress"
    nonisolated private static let finishedKey = "driveSyncFinished"

    private let center = UNUserNotificationCenter.current()
    private var lastBody: String?

    func register() {
        center.delegate = self
    }

    func requestPermission() async -> Bool {
        (try? await center.requestAuthorization(options: [.alert])) ?? false
    }

    func canPost() async -> Bool {
        guard UserDefaults.standard.bool(forKey: Self.preferenceKey) else { return false }
        let settings = await center.notificationSettings()
        return settings.authorizationStatus == .authorized || settings.authorizationStatus == .provisional
    }

    func begin() {
        lastBody = nil
    }

    func clear() {
        center.removePendingNotificationRequests(withIdentifiers: [Self.identifier])
        center.removeDeliveredNotifications(withIdentifiers: [Self.identifier])
        lastBody = nil
    }

    func post(status: String, progress: Double?, background: Bool, finished: Bool = false) async -> Bool {
        guard UserDefaults.standard.bool(forKey: Self.preferenceKey), !status.isEmpty else { return false }
        let body: String
        if let progress, progress.isFinite, !finished {
            let percent = Int((max(0, min(1, progress)) * 100).rounded(.down))
            body = "\(status) · 현재 단계 \(percent)%"
        } else {
            body = status
        }
        if !finished && lastBody == body { return false }

        let content = UNMutableNotificationContent()
        content.title = background ? "Umtomi 백그라운드 동기화" : "Umtomi 동기화"
        content.body = body
        content.threadIdentifier = Self.identifier
        content.interruptionLevel = finished ? .active : .passive
        content.userInfo = [Self.finishedKey: finished]
        let request = UNNotificationRequest(identifier: Self.identifier, content: content, trigger: nil)
        do {
            try await center.add(request)
            guard UserDefaults.standard.bool(forKey: Self.preferenceKey) else {
                clear()
                return false
            }
            lastBody = body
            return true
        } catch {
            return false
        }
    }

    nonisolated func userNotificationCenter(
        _ center: UNUserNotificationCenter,
        willPresent notification: UNNotification,
        withCompletionHandler completionHandler: @escaping (UNNotificationPresentationOptions) -> Void
    ) {
        let request = notification.request
        guard request.identifier == Self.identifier else {
            completionHandler([.banner, .list])
            return
        }
        let finished = request.content.userInfo[Self.finishedKey] as? Bool == true
        completionHandler(finished ? [.banner, .list] : [.list])
    }
}
