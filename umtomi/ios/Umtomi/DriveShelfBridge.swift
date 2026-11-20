import Foundation

enum DriveShelfBridge {
    static func flatten(_ catalog: ShelfCatalog, manifests: [UUID: ShelfCloudImage]) throws -> SyncObject {
        var shelves: [UUID: ImageShelf] = [:]
        for shelf in catalog.shelves {
            guard shelves.updateValue(shelf, forKey: shelf.id) == nil else { throw DriveSyncFailure.invalid }
        }
        var photoIDs = Set<UUID>()
        func path(_ root: ImageShelf) throws -> String {
            var names = [root.name]
            var next = root.parentID
            var seen: Set<UUID> = [root.id]
            while let id = next {
                guard seen.insert(id).inserted, let parent = shelves[id] else { throw DriveSyncFailure.invalid }
                names.append(parent.name)
                next = parent.parentID
            }
            return names.reversed().joined(separator: "/")
        }

        var fields: SyncObject = [:]
        var folderOrder = 0
        var photoOrder = 0
        for shelf in catalog.shelves where shelf.virtual != true || !shelf.photos.isEmpty {
            let folder = SyncJSON.uuid(shelf.id)
            let root = "folders/\(folder)"
            fields[root + "/name"] = try path(shelf)
            fields[root + "/note"] = shelf.note
            fields[root + "/columns"] = shelf.columns
            fields[root + "/order"] = folderOrder
            folderOrder += 1
            for photo in shelf.photos {
                guard photoIDs.insert(photo.id).inserted, let manifest = manifests[photo.id] ?? photo.cloud else { throw DriveSyncFailure.invalid }
                let key = "images/\(SyncJSON.uuid(photo.id))"
                fields[key + "/folderId"] = folder
                fields[key + "/manifest"] = try SyncJSON.read(JSONEncoder().encode(manifest))
                fields[key + "/sourceUrl"] = photo.sourceURL ?? ""
                fields[key + "/width"] = Int(photo.width.rounded())
                fields[key + "/height"] = Int(photo.height.rounded())
                fields[key + "/createdAt"] = photo.createdAt ?? 0
                fields[key + "/order"] = photoOrder
                photoOrder += 1
            }
        }
        return fields
    }

    static func restore(_ fields: SyncObject, previous: ShelfCatalog) throws -> ShelfCatalog {
        let previousPhotos = Dictionary(grouping: previous.shelves.flatMap(\.photos), by: \.id)
        var folders: [UUID: (path: String, note: String, columns: Int)] = [:]
        var names: [String: UUID] = [:]
        var orderedIDs: [UUID] = []
        let folderRows = rows(fields, group: "folders")
        for (id, row) in folderRows {
            guard let raw = row["name"] as? String, let note = row["note"] as? String,
                  let columns = row["columns"] as? Int else { continue }
            let parts = raw.split(separator: "/", omittingEmptySubsequences: false)
            guard (1...32).contains(parts.count),
                  parts.allSatisfy({ !$0.isEmpty && $0 != "." && $0 != ".." }) else { throw DriveSyncFailure.invalid }
            let name = names[raw.lowercased()] == nil ? raw : "\(raw) (\(SyncJSON.uuid(id).prefix(8)))"
            guard names[name.lowercased()] == nil else { throw DriveSyncFailure.invalid }
            orderedIDs.append(id)
            folders[id] = (name, note, columns)
            names[name.lowercased()] = id
        }

        var inserted = Set<UUID>()
        var shelves: [ImageShelf] = []
        func insert(_ path: String) throws -> UUID {
            let key = path.lowercased()
            let id: UUID
            if let current = names[key] {
                id = current
            } else {
                var candidate = SyncJSON.digest(Data("umtomi-virtual/\(key)".utf8))
                var value: UUID? = SyncJSON.uuid(from: String(candidate.prefix(32)))
                while let existing = value, folders[existing] != nil || inserted.contains(existing) {
                    candidate = SyncJSON.digest(Data(candidate.utf8))
                    value = SyncJSON.uuid(from: String(candidate.prefix(32)))
                }
                guard let resolved = value else { throw DriveSyncFailure.invalid }
                id = resolved
                names[key] = id
            }
            guard !inserted.contains(id) else { return id }
            let slash = path.lastIndex(of: "/")
            let parent = try slash.map { try insert(String(path[..<$0])) }
            let name = slash.map { String(path[path.index(after: $0)...]) } ?? path
            let row = folders[id]
            shelves.append(ImageShelf(id: id, name: name, parentID: parent,
                                      note: row?.note ?? "", columns: row?.columns ?? 2, virtual: row == nil ? true : nil))
            inserted.insert(id)
            return id
        }
        for id in orderedIDs {
            if let row = folders[id] { _ = try insert(row.path) }
        }
        let indices = Dictionary(uniqueKeysWithValues: shelves.enumerated().map { ($0.element.id, $0.offset) })
        for (id, row) in rows(fields, group: "images") {
            guard let folder = row["folderId"] as? String, let folderID = SyncJSON.uuid(from: folder),
                  let index = indices[folderID],
                  let raw = row["manifest"] as? SyncObject,
                  let width = (row["width"] as? NSNumber)?.doubleValue, let height = (row["height"] as? NSNumber)?.doubleValue,
                  let source = row["sourceUrl"] as? String, let created = (row["createdAt"] as? NSNumber)?.int64Value,
                  row["order"] is Int else { continue }
            let manifest = try JSONDecoder().decode(ShelfCloudImage.self, from: SyncJSON.data(raw))
            try manifest.validate()
            let old = previousPhotos[id]?.first
            var filename = "image-sync-\(SyncJSON.uuid(id))-\(manifest.sha256).image"
            if let old, DriveSyncCoordinator.safeFilename(old.filename), old.cloud == nil || old.cloud?.sha256 == manifest.sha256 {
                let local = ShelfFiles.root.appendingPathComponent(old.filename)
                if FileManager.default.fileExists(atPath: local.path) {
                    let knownOriginal = old.cloud?.sha256 == manifest.sha256
                    if knownOriginal || (try? ShelfFiles.digest(local).map({ String(format: "%02x", $0) }).joined()) == manifest.sha256 {
                        filename = old.filename
                    }
                }
            }
            shelves[index].photos.append(.init(id: id, filename: filename, width: width, height: height,
                                               cloud: manifest, sourceURL: source, createdAt: created))
        }
        if shelves.isEmpty {
            var empty = ShelfCatalog()
            empty.shelves[0].virtual = true
            return empty
        }
        return ShelfCatalog(shelves: shelves, selected: previous.selected)
    }

    static func rows(_ fields: SyncObject, group: String) -> [(id: UUID, values: SyncObject)] {
        var rows: [UUID: SyncObject] = [:]
        for (key, value) in fields where key.hasPrefix(group + "/") {
            let parts = key.split(separator: "/")
            guard parts.count == 3, let id = SyncJSON.uuid(from: String(parts[1])) else { continue }
            rows[id, default: [:]][String(parts[2])] = value
        }
        return rows.map { ($0.key, $0.value) }.sorted {
            let left = $0.1["order"] as? Int ?? Int.max
            let right = $1.1["order"] as? Int ?? Int.max
            return left == right ? SyncJSON.uuid($0.0) < SyncJSON.uuid($1.0) : left < right
        }
    }
}
