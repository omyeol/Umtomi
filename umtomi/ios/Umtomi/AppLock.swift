import CommonCrypto
import Foundation
import LocalAuthentication
import Security
import SwiftUI

private enum AppLockPassword {
    static let query: [String: Any] = [
        kSecClass as String: kSecClassGenericPassword,
        kSecAttrService as String: "xyz.sabok.umtomi.app-lock",
        kSecAttrAccount as String: "password-v1"
    ]

    static func save(_ password: String) throws {
        var salt = [UInt8](repeating: 0, count: 16)
        guard SecRandomCopyBytes(kSecRandomDefault, salt.count, &salt) == errSecSuccess else {
            throw Failure.storage
        }
        let data = Data(salt) + (try key(password, salt: Data(salt)))
        let changes = [kSecValueData as String: data]
        let update = SecItemUpdate(query as CFDictionary, changes as CFDictionary)
        if update == errSecItemNotFound {
            var item = query
            item[kSecValueData as String] = data
            item[kSecAttrAccessible as String] = kSecAttrAccessibleWhenUnlockedThisDeviceOnly
            guard SecItemAdd(item as CFDictionary, nil) == errSecSuccess else { throw Failure.storage }
        } else if update != errSecSuccess {
            throw Failure.storage
        }
    }

    static func verify(_ password: String) throws -> Bool {
        var request = query
        request[kSecReturnData as String] = true
        request[kSecMatchLimit as String] = kSecMatchLimitOne
        var value: CFTypeRef?
        guard SecItemCopyMatching(request as CFDictionary, &value) == errSecSuccess,
              let record = value as? Data, record.count == 48 else { throw Failure.storage }
        let actual = try key(password, salt: Data(record.prefix(16)))
        return zip(actual, record.suffix(32)).reduce(UInt8(0)) { $0 | ($1.0 ^ $1.1) } == 0
    }

    private static func key(_ password: String, salt: Data) throws -> Data {
        var bytes = [UInt8](repeating: 0, count: 32)
        let status = salt.withUnsafeBytes { buffer in
            password.withCString { value in
                CCKeyDerivationPBKDF(CCPBKDFAlgorithm(kCCPBKDF2), value, password.utf8.count,
                    buffer.bindMemory(to: UInt8.self).baseAddress, salt.count,
                    CCPseudoRandomAlgorithm(kCCPRFHmacAlgSHA256), 210_000, &bytes, 32)
            }
        }
        guard status == kCCSuccess else { throw Failure.storage }
        return Data(bytes)
    }

    enum Failure: LocalizedError {
        case storage
        var errorDescription: String? { "잠금 비밀번호를 안전하게 처리하지 못했습니다." }
    }
}

private final class BiometricSession: @unchecked Sendable {
    private static let queue = DispatchQueue(label: "xyz.sabok.umtomi.biometric", qos: .userInteractive)
    private let context = LAContext()
    private let policy: LAPolicy
    private var prepared = false
    private var available = false
    private var availabilityMessage: String?

    init(policy: LAPolicy, fallbackTitle: String?) {
        self.policy = policy
        context.localizedFallbackTitle = fallbackTitle
    }

    func prepare() {
        Self.queue.async { [self] in
            guard !prepared else { return }
            var error: NSError?
            available = context.canEvaluatePolicy(policy, error: &error)
            availabilityMessage = error?.localizedDescription
            prepared = true
        }
    }

    func evaluate(reason: String, completion: @escaping @Sendable (Bool, Error?) -> Void) {
        Self.queue.async { [self] in
            if !prepared {
                var error: NSError?
                available = context.canEvaluatePolicy(policy, error: &error)
                availabilityMessage = error?.localizedDescription
                prepared = true
            }
            guard available else {
                let error = NSError(
                    domain: LAError.errorDomain,
                    code: LAError.biometryNotAvailable.rawValue,
                    userInfo: [NSLocalizedDescriptionKey: availabilityMessage ?? "기기 인증을 사용할 수 없습니다."]
                )
                DispatchQueue.main.async { completion(false, error) }
                return
            }
            context.evaluatePolicy(policy, localizedReason: reason) { accepted, error in
                DispatchQueue.main.async {
                    completion(accepted, error)
                }
            }
        }
    }

    func invalidate() {
        context.invalidate()
    }
}

@MainActor
final class AppLock: ObservableObject {
    private enum Key {
        static let old = "umtomiRequireDeviceAuthentication"
        static let enabled = "umtomi.lock.enabled"
        static let biometrics = "umtomi.lock.biometrics"
        static let immediate = "umtomi.lock.immediate"
        static let minutes = "umtomi.lock.minutes"
    }

    @Published private(set) var enabled: Bool
    @Published private(set) var requiresPasswordSetup: Bool
    @Published private(set) var biometrics: Bool
    @Published private(set) var immediate: Bool
    @Published private(set) var minutes: Int
    @Published private(set) var locked: Bool
    @Published private(set) var authenticating = false
    @Published private(set) var suppressInactiveShield = false
    @Published private(set) var message: String?

    private var backgroundAt: Date?
    private var activeSession: BiometricSession?
    private var preparedSession: BiometricSession?
    private var attempt = UUID()
    private var phase: ScenePhase = .inactive
    private var failures = 0
    private var retryAfter: Date?
    private let defaults = UserDefaults.standard

    init() {
        let legacy = UserDefaults.standard.bool(forKey: Key.old) && !UserDefaults.standard.bool(forKey: Key.enabled)
        let enabled = legacy || UserDefaults.standard.bool(forKey: Key.enabled)
        self.enabled = enabled
        self.requiresPasswordSetup = legacy
        self.locked = enabled
        self.biometrics = UserDefaults.standard.bool(forKey: Key.biometrics)
        self.immediate = UserDefaults.standard.object(forKey: Key.immediate) as? Bool ?? true
        let storedMinutes = UserDefaults.standard.integer(forKey: Key.minutes)
        self.minutes = (1...1440).contains(storedMinutes) ? storedMinutes : 5
    }

    func phaseChanged(_ next: ScenePhase) {
        phase = next
        if next == .background {
            suppressInactiveShield = false
            if backgroundAt == nil { backgroundAt = Date() }
            invalidateAuthentication()
            if enabled, immediate { lockNow() }
        } else if next == .active {
            if enabled, let since = backgroundAt, Date().timeIntervalSince(since) >= Double(minutes * 60) {
                lockNow()
            }
            backgroundAt = nil
            suppressInactiveShield = false
        }
    }

    func lockNow() {
        guard enabled else { return }
        invalidateAuthentication()
        message = nil
        locked = true
    }

    func setImmediate(_ value: Bool) {
        immediate = value
        defaults.set(value, forKey: Key.immediate)
    }

    func setMinutes(_ value: Int) {
        guard (1...1440).contains(value) else { return }
        minutes = value
        defaults.set(value, forKey: Key.minutes)
    }

    func setBiometrics(_ enabled: Bool) async {
        if enabled {
            guard await authenticate(.deviceOwnerAuthenticationWithBiometrics, reason: "Umtomi 생체 인증 사용") else { return }
            guard phase != .background else { return }
        }
        biometrics = enabled
        defaults.set(enabled, forKey: Key.biometrics)
    }

    func authenticateLegacy() async -> Bool {
        await authenticate(.deviceOwnerAuthentication, reason: "기존 Umtomi 잠금 확인")
    }

    func prepareBiometrics() {
        guard locked, biometrics, !requiresPasswordSetup, !authenticating, phase != .background else { return }
        if preparedSession == nil {
            let session = BiometricSession(policy: .deviceOwnerAuthenticationWithBiometrics, fallbackTitle: "앱 비밀번호 입력")
            preparedSession = session
            session.prepare()
        }
    }

    func unlockWithBiometrics() async {
        guard locked, biometrics, !requiresPasswordSetup, phase != .background else { return }
        _ = await authenticate(.deviceOwnerAuthenticationWithBiometrics, reason: "Umtomi 잠금 해제", unlockOnSuccess: true)
    }

    func unlockWithPassword(_ password: String) async {
        guard locked, !requiresPasswordSetup, !authenticating else { return }
        if let retryAfter, retryAfter > Date() {
            message = "잠시 후 다시 시도해 주세요."
            return
        }
        let id = UUID()
        attempt = id
        message = nil
        authenticating = true
        defer { if attempt == id { authenticating = false } }
        do {
            let matches = try await Task.detached(priority: .userInitiated) { try AppLockPassword.verify(password) }.value
            guard attempt == id, phase != .background else { return }
            if matches {
                unlock()
            } else {
                failures += 1
                if failures.isMultiple(of: 5) { retryAfter = Date().addingTimeInterval(15) }
                message = failures.isMultiple(of: 5) ? "비밀번호가 올바르지 않습니다. 15초 뒤 다시 시도해 주세요." : "비밀번호가 올바르지 않습니다."
            }
        } catch { if attempt == id { message = error.localizedDescription } }
    }

    func savePassword(_ password: String, current: String?, legacyAuthorized: Bool = false) async -> Bool {
        guard !authenticating, password.count >= 4, password.utf8.count <= 256,
              !requiresPasswordSetup || legacyAuthorized,
              !locked || (requiresPasswordSetup && legacyAuthorized) else { return false }
        let verifyExisting = enabled && !requiresPasswordSetup
        let id = UUID()
        attempt = id
        authenticating = true
        message = nil
        defer { if attempt == id { authenticating = false } }
        do {
            let saved = try await Task.detached(priority: .userInitiated) {
                if verifyExisting {
                    guard let current, try AppLockPassword.verify(current) else { return false }
                }
                try AppLockPassword.save(password)
                return true
            }.value
            if !saved { message = "현재 비밀번호가 올바르지 않습니다."; return false }
            enabled = true
            requiresPasswordSetup = false
            defaults.set(true, forKey: Key.enabled)
            defaults.removeObject(forKey: Key.old)
            if attempt == id, phase != .background { unlock() }
            else { locked = true }
            return true
        } catch {
            if attempt == id { message = error.localizedDescription }
            return false
        }
    }

    func disable(password: String) async -> Bool {
        guard enabled, !locked, !authenticating else { return false }
        let id = UUID()
        attempt = id
        authenticating = true
        message = nil
        defer { if attempt == id { authenticating = false } }
        do {
            let matches = try await Task.detached(priority: .userInitiated) { try AppLockPassword.verify(password) }.value
            guard attempt == id, phase != .background else { return false }
            guard matches else { message = "현재 비밀번호가 올바르지 않습니다."; return false }
            enabled = false
            biometrics = false
            defaults.set(false, forKey: Key.enabled)
            defaults.set(false, forKey: Key.biometrics)
            unlock()
            return true
        } catch {
            if attempt == id { message = error.localizedDescription }
            return false
        }
    }

    private func authenticate(_ policy: LAPolicy, reason: String, unlockOnSuccess: Bool = false) async -> Bool {
        guard !authenticating else { return false }
        let session: BiometricSession
        if policy == .deviceOwnerAuthenticationWithBiometrics, let prepared = preparedSession {
            preparedSession = nil
            session = prepared
        } else {
            session = BiometricSession(
                policy: policy,
                fallbackTitle: policy == .deviceOwnerAuthenticationWithBiometrics ? "앱 비밀번호 입력" : nil
            )
        }
        let id = UUID()
        attempt = id
        activeSession = session
        authenticating = true
        message = nil

        return await withCheckedContinuation { continuation in
            session.evaluate(reason: reason) { [weak self] accepted, error in
                MainActor.assumeIsolated {
                    guard let self else {
                        continuation.resume(returning: false)
                        return
                    }
                    let succeeded = accepted && self.attempt == id && self.phase != .background
                    if self.attempt == id {
                        if succeeded && unlockOnSuccess {
                            self.unlock(clearingSession: true)
                        } else {
                            let failureMessage: String?
                            if accepted {
                                failureMessage = nil
                            } else if policy == .deviceOwnerAuthenticationWithBiometrics {
                                failureMessage = Self.biometricFailureMessage(for: error)
                            } else {
                                failureMessage = error?.localizedDescription
                            }
                            self.activeSession = nil
                            self.authenticating = false
                            self.message = failureMessage
                            if !succeeded && self.locked && self.biometrics && !self.requiresPasswordSetup {
                                self.prepareBiometrics()
                            }
                        }
                    }
                    continuation.resume(returning: succeeded)
                }
            }
        }
    }

    private static func biometricFailureMessage(for error: Error?) -> String {
        if let laError = error as? LAError {
            switch laError.code {
            case .biometryNotAvailable, .biometryNotEnrolled, .biometryLockout, .passcodeNotSet:
                return laError.localizedDescription
            default:
                break
            }
        }
        return "앱 비밀번호로 잠금을 해제할 수 있습니다."
    }

    private func unlock(clearingSession: Bool = false) {
        guard phase != .background else { return }
        failures = 0
        retryAfter = nil
        // Authentication can finish while iOS is still dismissing its system prompt.
        let suppress = phase == .inactive
        var transaction = Transaction(animation: nil)
        transaction.disablesAnimations = true
        withTransaction(transaction) {
            authenticating = false
            message = nil
            suppressInactiveShield = suppress
            locked = false
        }
        if clearingSession {
            activeSession = nil
        }
        preparedSession?.invalidate()
        preparedSession = nil
    }

    private func invalidateAuthentication() {
        attempt = UUID()
        activeSession?.invalidate()
        activeSession = nil
        preparedSession?.invalidate()
        preparedSession = nil
        authenticating = false
    }
}
