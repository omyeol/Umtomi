import Foundation
import CryptoKit

struct ShelfCloudImage: Codable, Equatable, Sendable {
    let version: Int
    let length: Int
    let sha256: String
    let chunks: [String]
    let files: [Reference]

    struct Reference: Codable, Equatable, Sendable {
        let id: String
        let name: String
    }

    static let chunkSize = 1024 * 1024
    static let maximum = 512 * chunkSize

    func validate() throws {
        func validHash(_ hash: String) -> Bool {
            hash.range(of: "^[0-9a-f]{64}$", options: .regularExpression) != nil
        }
        guard version == 1, (1...Self.maximum).contains(length), validHash(sha256),
              chunks.count == (length + Self.chunkSize - 1) / Self.chunkSize,
              files.count == chunks.count, chunks.allSatisfy(validHash),
              files.allSatisfy({ DriveNamespace.chunk.identity($0.name) != nil }) else {
            throw DriveSyncFailure.invalid
        }
    }

    @MainActor
    static func upload(_ url: URL, via drive: DriveFiles, keys: DriveKeyRing,
                       progress: ((Int) -> Void)? = nil) async throws -> Self {
        let input = try FileHandle(forReadingFrom: url)
        defer { try? input.close() }
        var hashes: [String] = []
        var references: [Reference] = []
        var length = 0
        var hash = SHA256()
        while true {
            try Task.checkCancellation()
            var bytes = Data()
            while bytes.count < chunkSize,
                  let data = try input.read(upToCount: chunkSize - bytes.count), !data.isEmpty {
                bytes += data
            }
            if bytes.isEmpty { break }
            length += bytes.count
            guard length <= maximum else { throw DriveSyncFailure.invalid }
            hash.update(data: bytes)
            let checksum = SyncJSON.digest(bytes)
            let row: SyncObject = ["version": 1, "index": hashes.count, "sha256": checksum,
                                   "data": bytes.base64EncodedString()]
            let plain = try SyncJSON.data(row)
            let file = try await drive.append(keys.encrypt(plain), id: SyncJSON.uuid(), in: .chunk)
            let stored = try await keys.decrypt(drive.read(file, in: .chunk))
            guard stored == plain else { throw DriveSyncFailure.invalid }
            references.append(.init(id: file.id, name: file.name))
            hashes.append(checksum)
            progress?(hashes.count)
        }
        guard length > 0 else { throw DriveSyncFailure.invalid }
        let checksum = hash.finalize().map { String(format: "%02x", $0) }.joined()
        return .init(version: 1, length: length, sha256: checksum, chunks: hashes, files: references)
    }

    func download(to target: URL, via drive: DriveFiles, keys: DriveKeyRing,
                  progress: (@MainActor (Int, Int) -> Void)? = nil) async throws {
        try validate()
        let totalChunks = files.count
        let parts: [(Int, Data)] = try await withThrowingTaskGroup(of: (Int, Data).self) { group in
            var completed = 0
            for (index, reference) in files.enumerated() {
                let expectedHash = chunks[index]
                let expectedLen = min(Self.chunkSize, length - index * Self.chunkSize)
                group.addTask {
                    try Task.checkCancellation()
                    let record = DriveRecord(id: reference.id, name: reference.name)
                    let plain = try await keys.decrypt(try await drive.read(record, in: .chunk))
                    let item = try SyncJSON.read(plain)
                    guard item["version"] as? Int == 1, item["index"] as? Int == index,
                          item["sha256"] as? String == expectedHash,
                          let content = item["data"] as? String, let bytes = Data(base64Encoded: content),
                          bytes.count == expectedLen,
                          SyncJSON.digest(bytes) == expectedHash else { throw DriveSyncFailure.invalid }
                    return (index, bytes)
                }
            }
            var out: [(Int, Data)] = []
            out.reserveCapacity(totalChunks)
            for try await part in group {
                out.append(part)
                completed += 1
                if let progress { await progress(completed, totalChunks) }
            }
            return out.sorted { $0.0 < $1.0 }
        }
        let staging = target.deletingLastPathComponent().appendingPathComponent(".\(UUID().uuidString).partial")
        let fm = FileManager.default
        try fm.createDirectory(at: target.deletingLastPathComponent(), withIntermediateDirectories: true)
        guard fm.createFile(atPath: staging.path, contents: nil) else { throw DriveSyncFailure.invalid }
        defer { try? fm.removeItem(at: staging) }
        let output = try FileHandle(forWritingTo: staging)
        defer { try? output.close() }
        var hash = SHA256()
        for (_, bytes) in parts {
            try output.write(contentsOf: bytes)
            hash.update(data: bytes)
        }
        let whole = hash.finalize().map { String(format: "%02x", $0) }.joined()
        guard whole == sha256 else { throw DriveSyncFailure.invalid }
        try output.close()
        try Task.checkCancellation()
        if !fm.fileExists(atPath: target.path) { try fm.moveItem(at: staging, to: target) }
    }
}
