import XCTest
import Foundation
@testable import Umtomi

final class DriveCompatibilityTests: XCTestCase {
    func testExistingAndroidAndWindowsEncryptionFormats() async throws {
        let id = "11111111111111111111111111111111"
        let secret = "AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8="
        let cases = [
            ("privacy-browser-sync", "omyaru-account-key", #"{"format":"privacy-browser-sync","version":1,"accountKeyId":"11111111111111111111111111111111","kdf":{"name":"pbkdf2-sha256","iterations":210000,"salt":"AAECAwQFBgcICQoLDA0ODw=="},"cipher":{"name":"aes-256-gcm","nonce":"EBESExQVFhcYGRob","ciphertext":"EHnOsA6fP6+0AcLa4tSfSNbMXZAAMEem+e7DSvxgVcuh6uo8d3di+spx3EJ0qCXQ"}}"#),
            ("umtomi-sync", "umtomi-account-key", #"{"format":"umtomi-sync","version":1,"accountKeyId":"11111111111111111111111111111111","kdf":{"name":"pbkdf2-sha256","iterations":210000,"salt":"AAECAwQFBgcICQoLDA0ODw=="},"cipher":{"name":"aes-256-gcm","nonce":"EBESExQVFhcYGRob","ciphertext":"EHnOsA6fP6+0AcLa4tSfSNbMXZAAMEem+e7DSvxgVcsFy85g7S21SheRiA3LH33K"}}"#)
        ]
        let expected = Data(#"{"version":1,"data":"synthetic"}"#.utf8)
        for (format, record, wire) in cases {
            var ring = DriveKeyRing(format: format)
            try ring.add(["format": record, "version": 1, "id": id, "secret": secret],
                         id: id, recordFormat: record)
            let decoded = try await ring.decrypt(Data(wire.utf8))
            XCTAssertEqual(decoded, expected)
            let encoded = try await ring.encrypt(expected)
            let roundTrip = try await ring.decrypt(encoded)
            XCTAssertEqual(roundTrip, expected)
        }
    }

    func testShelfTombstoneAndUserMerge() throws {
        let directory = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        defer { try? FileManager.default.removeItem(at: directory) }
        let id = "11111111111111111111111111111111"
        var shelf = try DriveReplica(directory: directory, kind: .shelf)
        let fields: SyncObject = ["folders/\(id)/name": "Parent/Child", "folders/\(id)/columns": 3,
                                   "folders/\(id)/note": "", "folders/\(id)/order": 0]
        try shelf.capture(fields)
        let projected = try DriveShelfBridge.restore(shelf.project(), previous: ShelfCatalog())
        XCTAssertEqual(projected.shelves.map(\.name), ["Parent", "Child"])
        XCTAssertEqual(projected.shelves[1].parentID, projected.shelves[0].id)
        XCTAssertTrue(projected.shelves[0].virtual == true)
        try shelf.capture([:])
        XCTAssertTrue(shelf.project().isEmpty)

        var user = try DriveReplica(directory: directory, kind: .user)
        try user.capture(["favorite/123": true, "history/123": 0])
        try user.save()
        var restored = try DriveReplica(directory: directory, kind: .user)
        try restored.capture(["history/123": 0])
        XCTAssertNil(restored.project()["favorite/123"])
        XCTAssertEqual(restored.project()["history/123"] as? Int, 0)
    }
}
