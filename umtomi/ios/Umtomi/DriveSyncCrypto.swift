import Foundation
import CryptoKit
import CommonCrypto
import Security

typealias SyncObject = [String: Any]

enum DriveSyncFailure: LocalizedError {
    case configuration, account, key(String), invalid, http(Int), timeout(String), changed, busy

    var errorDescription: String? {
        switch self {
        case .configuration: return "이 빌드에 iOS용 Google OAuth 클라이언트가 설정되지 않았습니다."
        case .account: return "동기화에 사용하던 Google 계정으로 연결해 주세요."
        case .key(let name): return "\(name) 동기화 키를 찾지 못했습니다. 기존 기기와 같은 Google 계정 및 프로젝트인지 확인해 주세요."
        case .invalid: return "동기화 데이터 또는 사진 원본을 검증하지 못했습니다."
        case .http(let code): return "Google Drive 요청 실패 (\(code))."
        case .timeout(let stage): return "\(stage) 응답 시간이 초과되었습니다. 연결 상태를 확인한 뒤 다시 시도해 주세요."
        case .changed: return "동기화 중에 목록이 바뀌었습니다. 다시 동기화해 주세요."
        case .busy: return "다른 책장 작업이 진행 중입니다."
        }
    }
}

enum SyncJSON {
    static func read(_ data: Data) throws -> SyncObject {
        guard let value = try JSONSerialization.jsonObject(with: data) as? SyncObject else {
            throw DriveSyncFailure.invalid
        }
        return value
    }

    static func data(_ value: Any) throws -> Data {
        try JSONSerialization.data(withJSONObject: value, options: [.sortedKeys, .fragmentsAllowed])
    }

    static func same(_ lhs: Any?, _ rhs: Any?) -> Bool {
        guard let lhs, let rhs else { return lhs == nil && rhs == nil }
        return (try? data(lhs)) == (try? data(rhs))
    }

    static func digest(_ data: Data) -> String {
        SHA256.hash(data: data).map { String(format: "%02x", $0) }.joined()
    }

    static func uuid(_ id: UUID = UUID()) -> String {
        id.uuidString.replacingOccurrences(of: "-", with: "").lowercased()
    }

    static func isCompactID(_ value: String) -> Bool {
        value.range(of: "^[0-9a-f]{32}$", options: .regularExpression) != nil &&
            value != String(repeating: "0", count: 32)
    }

    static func uuid(from value: String) -> UUID? {
        guard isCompactID(value) else { return nil }
        let bytes = Array(value)
        return UUID(uuidString: [0..<8, 8..<12, 12..<16, 16..<20, 20..<32]
            .map { String(bytes[$0]) }.joined(separator: "-"))
    }
}

struct DriveKeyRing: Sendable {
    let format: String
    private var keys = [String: String]()
    private var legacy = [String]()
    var isEmpty: Bool { keys.isEmpty }

    init(format: String) {
        self.format = format
    }

    mutating func add(_ record: SyncObject, id: String, recordFormat: String) throws {
        guard record["format"] as? String == recordFormat,
              record["version"] as? Int == 1,
              record["id"] as? String == id,
              let secret = record["secret"] as? String,
              Data(base64Encoded: secret)?.count == 32,
              keys[id] == nil || keys[id] == secret else { throw DriveSyncFailure.invalid }
        if let wrapped = record["legacyEnvelope"] as? String {
            let old = try SyncJSON.read(open(try SyncJSON.read(Data(wrapped.utf8)), password: secret))
            guard let password = old["passphrase"] as? String, password.count >= 6 else { throw DriveSyncFailure.invalid }
            legacy.append(password)
        }
        keys[id] = secret
    }

    func decrypt(_ data: Data) async throws -> Data {
        try await Task.detached(priority: .utility) {
            let envelope = try SyncJSON.read(data)
            if let id = envelope["accountKeyId"] as? String {
                guard let password = keys[id] else { throw DriveSyncFailure.key(id) }
                return try open(envelope, password: password)
            }
            for password in legacy {
                if let result = try? open(envelope, password: password) { return result }
            }
            throw DriveSyncFailure.invalid
        }.value
    }

    func encrypt(_ data: Data) async throws -> Data {
        try await Task.detached(priority: .utility) {
            guard let id = keys.keys.sorted().first, let password = keys[id] else {
                throw DriveSyncFailure.key(format)
            }
            let salt = try randomBytes(count: 16)
            let iterations = 210_000
            let key = try derive(password, salt: salt, iterations: iterations)
            let box = try AES.GCM.seal(data, using: key, authenticating: Data("\(format)-v1".utf8))
            return try SyncJSON.data([
                "format": format, "version": 1, "accountKeyId": id,
                "kdf": ["name": "pbkdf2-sha256", "iterations": iterations, "salt": salt.base64EncodedString()],
                "cipher": ["name": "aes-256-gcm", "nonce": Data(box.nonce).base64EncodedString(),
                           "ciphertext": (box.ciphertext + box.tag).base64EncodedString()]
            ])
        }.value
    }

    private func open(_ envelope: SyncObject, password: String) throws -> Data {
        guard envelope["format"] as? String == format, envelope["version"] as? Int == 1,
              let kdf = envelope["kdf"] as? SyncObject, kdf["name"] as? String == "pbkdf2-sha256",
              let rounds = kdf["iterations"] as? Int, (100_000...1_000_000).contains(rounds),
              let saltText = kdf["salt"] as? String, let salt = Data(base64Encoded: saltText), salt.count == 16,
              let cipher = envelope["cipher"] as? SyncObject, cipher["name"] as? String == "aes-256-gcm",
              let nonceText = cipher["nonce"] as? String, let nonce = Data(base64Encoded: nonceText), nonce.count == 12,
              let bodyText = cipher["ciphertext"] as? String, let body = Data(base64Encoded: bodyText),
              body.count >= 16, body.count <= 4 * 1024 * 1024 else { throw DriveSyncFailure.invalid }
        let sealed = try AES.GCM.SealedBox(nonce: AES.GCM.Nonce(data: nonce),
                                          ciphertext: body.dropLast(16), tag: body.suffix(16))
        return try AES.GCM.open(sealed, using: derive(password, salt: salt, iterations: rounds),
                                authenticating: Data("\(format)-v1".utf8))
    }

    private func derive(_ password: String, salt: Data, iterations: Int) throws -> SymmetricKey {
        var bytes = [UInt8](repeating: 0, count: 32)
        let status = salt.withUnsafeBytes { buffer in
            password.withCString { text in
                CCKeyDerivationPBKDF(CCPBKDFAlgorithm(kCCPBKDF2), text, password.utf8.count,
                    buffer.bindMemory(to: UInt8.self).baseAddress, salt.count,
                    CCPseudoRandomAlgorithm(kCCPRFHmacAlgSHA256), UInt32(iterations), &bytes, 32)
            }
        }
        guard status == kCCSuccess else { throw DriveSyncFailure.invalid }
        return SymmetricKey(data: bytes)
    }

    private func randomBytes(count: Int) throws -> Data {
        var bytes = [UInt8](repeating: 0, count: count)
        guard SecRandomCopyBytes(kSecRandomDefault, count, &bytes) == errSecSuccess else {
            throw DriveSyncFailure.invalid
        }
        return Data(bytes)
    }
}
