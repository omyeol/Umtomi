import BackgroundTasks
import Foundation
import GoogleSignIn
import SwiftUI
import UIKit

@MainActor
final class DriveSyncCoordinator: ObservableObject {
    static let shared = DriveSyncCoordinator()
    nonisolated static let taskID = "xyz.sabok.umtomi.bookshelf-sync"
    nonisolated static let backgroundKey = "umtomi.drive.background"
    nonisolated static let onDemandKey = "umtomi.drive.onDemand"
    nonisolated static let enabledKey = "umtomi.drive.enabled"
    nonisolated static let accountKey = "umtomi.drive.account"
    nonisolated static let accountEmailKey = "umtomi.drive.accountEmail"

    var connectedAccountEmail: String {
        defaults.string(forKey: Self.accountEmailKey) ?? defaults.string(forKey: Self.accountKey) ?? ""
    }

    @Published private(set) var status = ""
    @Published private(set) var busy = false
    @Published private(set) var progress: Double?
    @Published private(set) var lastSuccess: Date?

    private let defaults = UserDefaults.standard
    private var running: Task<Bool, Never>?
    private var runningInBackground = false
    private var keyCache: (account: String, keys: DriveKeyRing)?
    private var userRestoration: Task<GIDGoogleUser, Error>?
    private var lastForegroundAttempt = Date.distantPast
    private var accountGeneration = UUID()

    var configured: Bool {
        guard let id = Bundle.main.object(forInfoDictionaryKey: "GIDClientID") as? String,
              id.range(of: "^[0-9]+-[A-Za-z0-9_-]+[.]apps[.]googleusercontent[.]com$", options: .regularExpression) != nil,
              let types = Bundle.main.object(forInfoDictionaryKey: "CFBundleURLTypes") as? [SyncObject] else { return false }
        let scheme = id.split(separator: ".").reversed().joined(separator: ".")
        return types.contains { ($0["CFBundleURLSchemes"] as? [String])?.contains(scheme) == true }
    }

    func connect() async {
        guard !busy else { return }
        guard configured else { status = DriveSyncFailure.configuration.localizedDescription; return }
        guard let window = UIApplication.shared.connectedScenes.compactMap({ $0 as? UIWindowScene })
                    .flatMap(\.windows).first(where: \.isKeyWindow),
              var presenter = window.rootViewController else { return }
        while let presented = presenter.presentedViewController { presenter = presented }
        busy = true
        status = "Google 계정 선택 대기 중"
        defer { busy = false; progress = nil }
        do {
            let account = try await GIDSignIn.sharedInstance.signIn(withPresenting: presenter,
                hint: nil, additionalScopes: [DriveFiles.scope]).user
            let identity = try checkedIdentity(account)
            status = "Google 로그인 갱신 중"
            let drive = try await makeDrive(account)
            let keys = try await loadKeys(from: drive, namespace: .shelfKey, label: "공통 책장")
            defaults.set(identity, forKey: Self.accountKey)
            if let email = account.profile?.email, !email.isEmpty {
                defaults.set(email, forKey: Self.accountEmailKey)
            }
            defaults.set(true, forKey: Self.enabledKey)
            keyCache = (identity, keys)
            status = "Google Drive 연결됨"
            schedule()
        } catch {
            // Prevent a failed account change from leaving a different Google account active.
            GIDSignIn.sharedInstance.signOut()
            defaults.set(false, forKey: Self.enabledKey)
            keyCache = nil
            schedule()
            showFailure(error)
        }
    }

    func disconnect() {
        guard !busy else { return }
        accountGeneration = UUID()
        userRestoration?.cancel()
        userRestoration = nil
        GIDSignIn.sharedInstance.signOut()
        defaults.set(false, forKey: Self.enabledKey)
        defaults.set(false, forKey: Self.backgroundKey)
        keyCache = nil
        status = ""
        schedule()
    }

    private func checkedIdentity(_ account: GIDGoogleUser) throws -> String {
        guard let id = account.userID, !id.isEmpty, account.grantedScopes?.contains(DriveFiles.scope) == true,
              defaults.string(forKey: Self.accountKey).map({ $0 == id }) ?? true else {
            throw DriveSyncFailure.account
        }
        return id
    }

    private func makeDrive(_ account: GIDGoogleUser) async throws -> DriveFiles {
        _ = try checkedIdentity(account)
        let refreshed = try await DriveAccountDeadline.wait(stage: "Google 로그인 갱신") {
            try await account.refreshTokensIfNeeded()
        }
        try Task.checkCancellation()
        return DriveFiles(accessToken: refreshed.accessToken.tokenString)
    }

    private func auth(reportStatus: Bool = false) async throws -> (String, DriveFiles) {
        guard configured, defaults.bool(forKey: Self.enabledKey) else { throw DriveSyncFailure.configuration }
        let generation = accountGeneration
        let account: GIDGoogleUser
        if let current = GIDSignIn.sharedInstance.currentUser {
            account = current
        } else {
            if reportStatus { status = "저장된 Google 로그인 복원 중" }
            if userRestoration == nil {
                userRestoration = Task {
                    defer { if self.accountGeneration == generation { self.userRestoration = nil } }
                    return try await DriveAccountDeadline.wait(stage: "Google 로그인 복원") {
                        try await GIDSignIn.sharedInstance.restorePreviousSignIn()
                    }
                }
            }
            guard let task = userRestoration else { throw DriveSyncFailure.account }
            account = try await DriveAccountDeadline.wait(seconds: 27, stage: "Google 로그인 복원") {
                try await task.value
            }
        }
        let identity = try checkedIdentity(account)
        if let email = account.profile?.email, !email.isEmpty {
            defaults.set(email, forKey: Self.accountEmailKey)
        }
        if reportStatus { status = "Google 로그인 갱신 중" }
        let drive = try await makeDrive(account)
        guard accountGeneration == generation, defaults.bool(forKey: Self.enabledKey) else {
            throw DriveSyncFailure.account
        }
        return (identity, drive)
    }

    func foregroundTick(store: AppStore) async {
        guard defaults.bool(forKey: Self.enabledKey), running == nil,
              Date().timeIntervalSince(lastForegroundAttempt) >= 60 else { return }
        lastForegroundAttempt = Date()
        _ = await sync(store: store, automaticForeground: true)
    }

    func sync(store: AppStore, background: Bool = false, automaticForeground: Bool = false) async -> Bool {
        guard !busy, running == nil, defaults.bool(forKey: Self.enabledKey) else { return false }
        busy = true
        status = "Google 계정 확인 중"
        progress = nil
        lastForegroundAttempt = Date()
        let job = Task { await run(store: store) }
        running = job
        runningInBackground = background
        let notices = DriveSyncNotifications.shared
        notices.begin()
        let updates = Task {
            var displayed = false
            if automaticForeground {
                do { try await Task.sleep(for: .seconds(12)) }
                catch { return false }
            }
            guard !Task.isCancelled, await notices.canPost() else { return false }
            while !Task.isCancelled {
                if await notices.post(status: status, progress: progress, background: background) {
                    displayed = true
                }
                do { try await Task.sleep(for: .seconds(4)) }
                catch { break }
            }
            return displayed
        }
        let result = await withTaskCancellationHandler { await job.value } onCancel: { job.cancel() }
        updates.cancel()
        let progressDisplayed = await updates.value
        if (progressDisplayed || !automaticForeground), await notices.canPost() {
            _ = await notices.post(status: status, progress: nil, background: background, finished: true)
        }
        lastForegroundAttempt = Date()
        running = nil
        runningInBackground = false
        busy = false
        progress = nil
        return result
    }

    func cancel() { running?.cancel() }

    func schedule() {
        let scheduler = BGTaskScheduler.shared
        guard defaults.bool(forKey: Self.backgroundKey), defaults.bool(forKey: Self.enabledKey), configured else {
            scheduler.cancel(taskRequestWithIdentifier: Self.taskID)
            if runningInBackground { running?.cancel() }
            return
        }
        let request = BGProcessingTaskRequest(identifier: Self.taskID)
        request.requiresNetworkConnectivity = true
        request.earliestBeginDate = Date(timeIntervalSinceNow: 15 * 60)
        do { try scheduler.submit(request) }
        catch {
            if !busy { status = "백그라운드 예약 불가 · iOS 설정에서 백그라운드 앱 새로 고침을 확인해 주세요." }
        }
    }

    private func run(store: AppStore) async -> Bool {
        do {
            status = "Google 계정 확인 중"
            progress = nil
            let (identity, drive) = try await auth(reportStatus: true)
            let directory = ShelfFiles.root.appendingPathComponent(".drive", isDirectory: true)
                .appendingPathComponent(SyncJSON.digest(Data(identity.utf8)), isDirectory: true)
            let keys = try await loadKeys(from: drive, namespace: .shelfKey, label: "공통 책장")
            keyCache = (identity, keys)
            try await syncShelf(store.bookshelf, drive: drive, keys: keys, directory: directory)
            do {
                try await syncUser(store, drive: drive, directory: directory)
            } catch is CancellationError {
                throw CancellationError()
            } catch {
                if Task.isCancelled { throw CancellationError() }
                showFailure(error, prefix: "책장 완료 · ")
                return false
            }
            try Task.checkCancellation()
            lastSuccess = Date()
            status = "책장·즐겨찾기·기록 동기화 완료"
            return true
        } catch {
            showFailure(error)
            return false
        }
    }

    private func showFailure(_ error: Error, prefix: String = "") {
        if Task.isCancelled || error is CancellationError {
            status = "동기화 중단됨"
        } else if (error as? URLError)?.code == .timedOut {
            status = prefix + "\(status) · Drive 응답 시간이 초과되었습니다. 다시 시도해 주세요."
        } else {
            status = prefix + error.localizedDescription
        }
    }

    private func loadKeys(from drive: DriveFiles, namespace: DriveNamespace, label: String) async throws -> DriveKeyRing {
        progress = nil
        status = "\(label) 암호화 키 목록 확인 중"
        return try await drive.keys(for: namespace) { done, total in
            self.status = "\(label) 암호화 키 읽는 중 \(done)/\(total)"
            self.progress = Double(done) / Double(total)
        }
    }

    private func syncShelf(_ library: BookshelfLibrary, drive: DriveFiles, keys: DriveKeyRing, directory: URL) async throws {
        let accountRoot = SharedSyncFolderManager.shared.activeAccountRoot(for: connectedAccountEmail)
        var lockHandle: SharedSyncFolderManager.CrossAppLockHandle? = nil
        let lockStartedAt = Int64(Date().timeIntervalSince1970 * 1000)
        if let accountRoot {
            lockHandle = await SharedSyncFolderManager.acquireSyncLock(
                in: accountRoot,
                appName: SharedSyncFolderManager.appIdentifier,
                maxWaitSeconds: runningInBackground ? 2.0 : 30.0
            ) { [weak self] waitingText in
                Task { @MainActor in self?.status = waitingText }
            }
            guard lockHandle != nil else {
                status = "다른 앱(Omyaru Browser)이 동기화 중이어서 책장 동기화를 안전하게 보류했습니다."
                return
            }
        }
        defer { lockHandle?.release() }

        let stateDir = accountRoot.map { SharedSyncFolderManager.syncStateDirectory(in: $0) } ?? directory
        var replica = try DriveReplica(directory: stateDir, kind: .shelf)
        let original = try library.syncCatalog()
        let onDemand = defaults.bool(forKey: Self.onDemandKey)
        try await pull(&replica, from: drive, keys: keys, namespace: .shelf, label: "책장")
        lockHandle?.refreshHeartbeat(appName: SharedSyncFolderManager.appIdentifier, startedAt: lockStartedAt)

        let remote = replica.project()
        let photos = original.shelves.flatMap(\.photos)
        var manifests: [UUID: ShelfCloudImage] = [:]
        let cached = stateDir.appendingPathComponent("manifests.json")
        var cache = FileManager.default.fileExists(atPath: cached.path)
            ? try JSONDecoder().decode([String: ShelfCloudImage].self, from: Data(contentsOf: cached)) : [:]
        for (index, photo) in photos.enumerated() {
            try Task.checkCancellation()
            progress = Double(index) / Double(max(1, photos.count))
            status = "책장 업로드 확인 \(index)/\(photos.count)"
            if let manifest = photo.cloud {
                try manifest.validate()
                manifests[photo.id] = manifest
                continue
            }
            guard Self.safeFilename(photo.filename) else { throw DriveSyncFailure.invalid }
            let url = ShelfFiles.photoURL(for: photo)
            let checksum = try await Task.detached { try ShelfFiles.digest(url).map { String(format: "%02x", $0) }.joined() }.value
            let id = SyncJSON.uuid(photo.id)
            let key = id + "-" + checksum
            let candidate = remote["images/\(id)/manifest"] as? SyncObject
            if let candidate, let cloud = try? JSONDecoder().decode(ShelfCloudImage.self, from: SyncJSON.data(candidate)),
               cloud.sha256 == checksum {
                try cloud.validate()
                manifests[photo.id] = cloud
            } else if let cached = cache[key] {
                try cached.validate()
                manifests[photo.id] = cached
            } else {
                status = "책장 사진 보내는 중 \(index + 1)/\(photos.count)"
                let manifest = try await ShelfCloudImage.upload(url, via: drive, keys: keys) { chunks in
                    self.status = "책장 사진 보내는 중 \(index + 1)/\(photos.count) · \(chunks)개 조각 확인"
                }
                guard manifest.sha256 == checksum else { throw DriveSyncFailure.changed }
                cache[key] = manifest
                try JSONEncoder().encode(cache).write(to: cached, options: .atomic)
                manifests[photo.id] = manifest
            }
        }

        progress = nil
        status = "책장 변경 기록 준비 중"
        try library.validateSyncCatalog(matching: original)
        let local = try DriveShelfBridge.flatten(original, manifests: manifests)
        try replica.capture(local)
        try replica.save()
        try await push(&replica, to: drive, keys: keys, namespace: .shelf, label: "책장")
        status = "받은 책장 목록 반영 중"
        progress = nil
        let merged = try DriveShelfBridge.restore(replica.project(), previous: original)
        try library.applyCloudCatalog(merged, matching: original)
        let applied = try library.syncCatalog()
        replica.acknowledge(try DriveShelfBridge.flatten(applied, manifests: [:]))
        try replica.save()

        if !onDemand {
            let images = applied.shelves.flatMap(\.photos)
            var pendingDownloads: [(ShelfPhoto, ShelfCloudImage, URL)] = []
            for photo in images {
                guard let cloud = photo.cloud else { continue }
                if let accountRoot, SharedSyncFolderManager.existingOriginalURL(sha256: cloud.sha256, in: accountRoot) != nil {
                    continue // Already exists in shared folder (e.g., downloaded by Omyaru Browser!) -> 0 bytes transferred!
                }
                let file = ShelfFiles.photoURL(for: photo)
                if !FileManager.default.fileExists(atPath: file.path) {
                    pendingDownloads.append((photo, cloud, file))
                }
            }
            if !pendingDownloads.isEmpty {
                let totalDown = pendingDownloads.count
                var doneDown = 0
                try await withThrowingTaskGroup(of: Void.self) { group in
                    var nextIdx = 0
                    let limit = max(1, min(4, totalDown))
                    func enqueue(_ i: Int) {
                        let (_, cloud, target) = pendingDownloads[i]
                        group.addTask {
                            try Task.checkCancellation()
                            if let accountRoot {
                                let temp = FileManager.default.temporaryDirectory.appendingPathComponent("umtomi-dl-\(UUID().uuidString).part")
                                defer { try? FileManager.default.removeItem(at: temp) }
                                try await cloud.download(to: temp, via: drive, keys: keys)
                                let bytes = try Data(contentsOf: temp)
                                _ = try SharedSyncFolderManager.storeOriginal(bytes, expectedSHA256: cloud.sha256, in: accountRoot)
                            } else {
                                try await cloud.download(to: target, via: drive, keys: keys)
                            }
                        }
                    }
                    for _ in 0..<limit { enqueue(nextIdx); nextIdx += 1 }
                    while try await group.next() != nil {
                        doneDown += 1
                        status = "책장 사진 병렬 받는 중 \(doneDown)/\(totalDown)"
                        progress = Double(doneDown) / Double(totalDown)
                        if doneDown.isMultiple(of: 8) {
                            lockHandle?.refreshHeartbeat(appName: SharedSyncFolderManager.appIdentifier, startedAt: lockStartedAt)
                        }
                        if nextIdx < totalDown { enqueue(nextIdx); nextIdx += 1 }
                    }
                }
            }
        }
    }

    private func syncUser(_ store: AppStore, drive: DriveFiles, directory: URL) async throws {
        let keys = try await loadKeys(from: drive, namespace: .userKey, label: "즐겨찾기·열람 기록")
        let local = try store.driveUserSnapshot()
        var replica = try DriveReplica(directory: directory, kind: .user)
        try await pull(&replica, from: drive, keys: keys, namespace: .user, label: "즐겨찾기·열람 기록")
        guard try SyncJSON.same(store.driveUserSnapshot(), local) else { throw DriveSyncFailure.changed }
        try replica.capture(local)
        try replica.save()
        try await push(&replica, to: drive, keys: keys, namespace: .user, label: "즐겨찾기·열람 기록")
        try Task.checkCancellation()
        try store.applyDriveUserSnapshot(replica.project(), matching: local)
        replica.acknowledge(try store.driveUserSnapshot())
        try replica.save()
    }

    private func pull(_ replica: inout DriveReplica, from drive: DriveFiles, keys: DriveKeyRing,
                      namespace: DriveNamespace, label: String) async throws {
        status = "\(label) 변경 목록 확인 중"
        progress = nil
        let rows = try await drive.list(namespace)
        let read = replica.seen
        let unread = rows.filter { !read.contains($0.id) }.sorted { $0.name < $1.name }
        guard !unread.isEmpty else { return }

        let total = unread.count
        var completed = 0
        let decoded: [(Int, DriveRecord, Data)] = try await withThrowingTaskGroup(of: (Int, DriveRecord, Data).self) { group in
            var nextIdx = 0
            let limit = max(1, min(6, total))
            func enqueue(_ idx: Int) {
                let row = unread[idx]
                group.addTask {
                    try Task.checkCancellation()
                    let plain = try await keys.decrypt(try await drive.read(row, in: namespace))
                    return (idx, row, plain)
                }
            }
            for _ in 0..<limit { enqueue(nextIdx); nextIdx += 1 }
            var out: [(Int, DriveRecord, Data)] = []
            out.reserveCapacity(total)
            while let item = try await group.next() {
                out.append(item)
                completed += 1
                status = "\(label) 변경 기록 병렬 받는 중 \(completed)/\(total)"
                progress = Double(completed) / Double(total)
                if nextIdx < total { enqueue(nextIdx); nextIdx += 1 }
            }
            return out.sorted { $0.0 < $1.0 }
        }
        for (_, row, plain) in decoded {
            let batch = try SyncJSON.read(plain)
            guard let id = batch["id"] as? String,
                  namespace.identity(row.name) == id.replacingOccurrences(of: "-", with: "").lowercased() else {
                throw DriveSyncFailure.invalid
            }
            try replica.apply(batch)
            replica.markSeen(row.id)
        }
        try replica.save()
    }

    private func push(_ replica: inout DriveReplica, to drive: DriveFiles, keys: DriveKeyRing,
                      namespace: DriveNamespace, label: String) async throws {
        let total = replica.pending.count
        var sent = 0
        while let batch = replica.pending.first {
            status = "\(label) 변경 기록 보내는 중 \(sent)/\(total)"
            progress = Double(sent) / Double(total)
            guard let id = batch["id"] as? String else { throw DriveSyncFailure.invalid }
            let compact = id.replacingOccurrences(of: "-", with: "").lowercased()
            let file = try await drive.append(keys.encrypt(SyncJSON.data(batch)), id: compact, in: namespace)
            replica.pop(file.id)
            try replica.save()
            sent += 1
        }
    }

    func openPhoto(_ photo: ShelfPhoto, forExport: Bool = false) async throws -> URL {
        guard Self.safeFilename(photo.filename) else { throw DriveSyncFailure.invalid }
        let local = ShelfFiles.photoURL(for: photo)
        if FileManager.default.fileExists(atPath: local.path) { return local }
        guard let cloud = photo.cloud else { throw DriveSyncFailure.invalid }
        let (identity, drive) = try await auth()
        let keys: DriveKeyRing
        if let cached = keyCache, cached.account == identity { keys = cached.keys }
        else {
            keys = try await drive.keys(for: .shelfKey)
            keyCache = (identity, keys)
        }
        try cloud.validate()
        let target: URL
        if forExport || defaults.bool(forKey: Self.onDemandKey) {
            target = FileManager.default.temporaryDirectory.appendingPathComponent("umtomi-view-\(UUID().uuidString).image")
        } else {
            target = local
        }
        try await cloud.download(to: target, via: drive, keys: keys)
        return target
    }

    nonisolated static func discardTemporaryPhoto(_ url: URL) {
        if url.deletingLastPathComponent().standardizedFileURL == FileManager.default.temporaryDirectory.standardizedFileURL,
           url.lastPathComponent.hasPrefix("umtomi-view-") {
            try? FileManager.default.removeItem(at: url)
        }
    }

    nonisolated static func safeFilename(_ name: String) -> Bool {
        !name.isEmpty && name != "." && name != ".." && !name.contains("/") && !name.contains("\\")
    }
}

@MainActor
final class UmtomiSyncDelegate: NSObject, UIApplicationDelegate {
    static let store = AppStore()

    func application(_ application: UIApplication, didFinishLaunchingWithOptions options: [UIApplication.LaunchOptionsKey: Any]? = nil) -> Bool {
        DriveSyncNotifications.shared.register()
        BGTaskScheduler.shared.register(forTaskWithIdentifier: DriveSyncCoordinator.taskID, using: .main) { task in
            Task { @MainActor in
                let coordinator = DriveSyncCoordinator.shared
                guard UserDefaults.standard.bool(forKey: DriveSyncCoordinator.backgroundKey) else {
                    task.setTaskCompleted(success: true)
                    return
                }
                let work = Task { @MainActor in await coordinator.sync(store: Self.store, background: true) }
                task.expirationHandler = { work.cancel() }
                coordinator.schedule()
                task.setTaskCompleted(success: await work.value)
            }
        }
        return true
    }
}
