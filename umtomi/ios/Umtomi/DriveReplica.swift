import Foundation

struct DriveReplica {
    enum Kind: String { case shelf, user }

    let directory: URL
    let kind: Kind
    private(set) var state: SyncObject

    init(directory: URL, kind: Kind) throws {
        self.directory = directory
        self.kind = kind
        let file = directory.appendingPathComponent(kind.rawValue + ".json")
        if FileManager.default.fileExists(atPath: file.path) {
            state = try SyncJSON.read(Data(contentsOf: file))
            guard state["clock"] is Int64, state["registers"] is SyncObject, state["baseline"] is SyncObject,
                  state["seen"] is [String], state["pending"] is [SyncObject], state["actor"] is String else {
                throw DriveSyncFailure.invalid
            }
        } else {
            state = ["actor": newID(kind), "clock": Int64(0), "registers": SyncObject(),
                     "baseline": SyncObject(), "seen": [String](), "pending": [SyncObject]()]
        }
    }

    var baseline: SyncObject { state["baseline"] as? SyncObject ?? [:] }
    var pending: [SyncObject] { state["pending"] as? [SyncObject] ?? [] }
    var seen: Set<String> { Set(state["seen"] as? [String] ?? []) }

    func save() throws {
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        try SyncJSON.data(state).write(to: directory.appendingPathComponent(kind.rawValue + ".json"), options: .atomic)
    }

    mutating func acknowledge(_ projected: SyncObject) { state["baseline"] = projected }

    mutating func markSeen(_ id: String) { state["seen"] = Array(seen.union([id])) }

    mutating func pop(_ cloudID: String) {
        state["pending"] = Array(pending.dropFirst())
        markSeen(cloudID)
    }

    mutating func capture(_ local: SyncObject) throws {
        let before = baseline
        var changes = local.filter { !SyncJSON.same(before[$0.key], $0.value) }
        if kind == .shelf {
            let current = Set(local.keys.map { $0.split(separator: "/").dropLast().joined(separator: "/") })
            for key in before.keys {
                let entity = key.split(separator: "/").dropLast().joined(separator: "/")
                if !current.contains(entity) { changes[entity + "/$deleted"] = true }
            }
        } else {
            for key in before.keys where local[key] == nil { changes[key] = NSNull() }
        }
        let keys = changes.keys.sorted()
        let batchSize = kind == .shelf ? 16 : 64
        for index in stride(from: 0, to: keys.count, by: batchSize) {
            let part = keys[index..<min(keys.count, index + batchSize)]
            var batch: SyncObject = ["id": newID(kind), "actor": state["actor"] as? String ?? "",
                                     "clock": (state["clock"] as? Int64 ?? 0) + 1]
            if kind == .shelf {
                batch["protocol"] = 1
                batch["changes"] = part.map { key -> SyncObject in
                    let pieces = key.split(separator: "/").map(String.init)
                    return ["kind": pieces[0], "id": pieces[1], "field": pieces[2], "value": changes[key]!]
                }
            } else {
                batch["format"] = "umtomi-userdata"
                batch["changes"] = Dictionary(uniqueKeysWithValues: part.map { ($0, changes[$0]!) })
            }
            try apply(batch)
            state["pending"] = pending + [batch]
        }
        acknowledge(local)
    }

    mutating func apply(_ batch: SyncObject) throws {
        guard let id = batch["id"] as? String, let actor = batch["actor"] as? String,
              let clock = (batch["clock"] as? NSNumber)?.int64Value, (1...9_000_000_000_000_000).contains(clock),
              validID(id), validID(actor) else { throw DriveSyncFailure.invalid }
        let changes: SyncObject
        if kind == .shelf {
            guard batch["protocol"] as? Int == 1, let rows = batch["changes"] as? [SyncObject],
                  (1...256).contains(rows.count) else { throw DriveSyncFailure.invalid }
            var values: SyncObject = [:]
            for row in rows {
                guard let group = row["kind"] as? String, let entity = row["id"] as? String,
                      let field = row["field"] as? String, let value = row["value"],
                      SyncJSON.isCompactID(entity), Self.validShelfField(group, field, value) else {
                    throw DriveSyncFailure.invalid
                }
                values["\(group)/\(entity)/\(field)"] = value
            }
            changes = values
        } else {
            guard batch["format"] as? String == "umtomi-userdata",
                  let values = batch["changes"] as? SyncObject, values.count <= 64,
                  values.keys.allSatisfy({ $0.range(of: "^(favorite|history|folder|tag|artist)/.{1,2048}$",
                                                   options: .regularExpression) != nil }) else {
                throw DriveSyncFailure.invalid
            }
            changes = values
        }

        var registers = state["registers"] as? [String: SyncObject] ?? [:]
        for (key, value) in changes {
            let old = registers[key]
            let oldClock = (old?["clock"] as? NSNumber)?.int64Value ?? 0
            let oldActor = old?["actor"] as? String ?? ""
            if clock > oldClock || (clock == oldClock && actor > oldActor) {
                registers[key] = ["actor": actor, "clock": clock, "value": value]
            }
        }
        state["registers"] = registers
        state["clock"] = max(clock, state["clock"] as? Int64 ?? 0)
    }

    func project() -> SyncObject {
        let registers = state["registers"] as? [String: SyncObject] ?? [:]
        var snapshot: SyncObject = [:]
        for (key, row) in registers {
            guard let value = row["value"], !(value is NSNull) else { continue }
            if kind == .shelf {
                let entity = key.split(separator: "/").dropLast().joined(separator: "/")
                if registers[entity + "/$deleted"] != nil { continue }
            }
            snapshot[key] = value
        }
        return snapshot
    }

    private func validID(_ id: String) -> Bool {
        kind == .shelf ? SyncJSON.isCompactID(id) : UUID(uuidString: id) != nil
    }

    private static func validShelfField(_ group: String, _ field: String, _ value: Any) -> Bool {
        guard ["folders", "images"].contains(group) else { return false }
        if field == "$deleted" { return value as? Bool == true }
        let number = (value as? NSNumber)?.int64Value
        switch (group, field) {
        case ("folders", "name"): return (value as? String).map { !$0.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty && $0.utf16.count <= 512 } ?? false
        case ("folders", "note"): return (value as? String).map { $0.utf16.count <= 4000 } ?? false
        case ("folders", "columns"): return number.map { (1...6).contains($0) } ?? false
        case (_, "order"): return number.map { (0...1_000_000).contains($0) } ?? false
        case ("images", "folderId"): return (value as? String).map(SyncJSON.isCompactID) ?? false
        case ("images", "sourceUrl"): return (value as? String).map { $0.utf16.count <= 8192 } ?? false
        case ("images", "width"), ("images", "height"): return number.map { (1...1_000_000).contains($0) } ?? false
        case ("images", "createdAt"): return number.map { (0...9_000_000_000_000_000).contains($0) } ?? false
        case ("images", "manifest"):
            return value is SyncObject && ((try? SyncJSON.data(value).count) ?? Int.max) <= 131_072
        default: return false
        }
    }
}

private func newID(_ kind: DriveReplica.Kind) -> String {
    kind == .shelf ? SyncJSON.uuid() : UUID().uuidString.lowercased()
}
