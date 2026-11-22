import SwiftUI
import UIKit
import UniformTypeIdentifiers
import ImageIO
import CryptoKit

struct ShelfPhoto: Codable, Identifiable {
    var id = UUID()
    var filename: String
    var width: Double
    var height: Double
    var cloud: ShelfCloudImage? = nil
    var sourceURL: String? = nil
    var createdAt: Int64? = nil
}

struct ImageShelf: Codable, Identifiable {
    var id = UUID()
    var name: String
    var parentID: UUID? = nil
    var note = ""
    var columns = 2
    var photos: [ShelfPhoto] = []
    var virtual: Bool? = nil
}

struct ShelfCatalog: Codable {
    var shelves = [ImageShelf(name: "Bookshelf")]
    var selected: UUID?
}

enum ShelfFiles {
    static var privateRoot: URL {
        FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
            .appendingPathComponent("UmtomiBookshelf", isDirectory: true)
    }

    static var root: URL { privateRoot }

    @MainActor
    static func photoURL(for photo: ShelfPhoto) -> URL {
        let fm = FileManager.default
        let accountEmail = DriveSyncCoordinator.shared.connectedAccountEmail
        if let accountRoot = SharedSyncFolderManager.shared.activeAccountRoot(for: accountEmail) {
            if let sha = photo.cloud?.sha256,
               let byHash = SharedSyncFolderManager.existingOriginalURL(sha256: sha, in: accountRoot) {
                return byHash
            }
            let sharedCandidate = SharedSyncFolderManager.originalsDirectory(in: accountRoot).appendingPathComponent(photo.filename)
            if fm.fileExists(atPath: sharedCandidate.path) { return sharedCandidate }
            let privCandidate = privateRoot.appendingPathComponent(photo.filename)
            if fm.fileExists(atPath: privCandidate.path) { return privCandidate }
            if let sha = photo.cloud?.sha256, sha.count == 64 {
                return SharedSyncFolderManager.originalsDirectory(in: accountRoot).appendingPathComponent("\(sha.lowercased()).png")
            }
            return sharedCandidate
        }
        return privateRoot.appendingPathComponent(photo.filename)
    }

    static func digest(_ url: URL) throws -> SHA256.Digest {
        let handle = try FileHandle(forReadingFrom: url)
        defer { try? handle.close() }
        var hash = SHA256()
        while let data = try handle.read(upToCount: 1024 * 1024), !data.isEmpty {
            hash.update(data: data)
        }
        return hash.finalize()
    }

    static func nextName(extension fileExtension: String, excluding reserved: Set<String> = []) throws -> String {
        let existing = Set(try FileManager.default.contentsOfDirectory(atPath: root.path))
        let numbered = existing.union(reserved).compactMap { name -> Int? in
            guard name.hasPrefix("image"), let dot = name.firstIndex(of: ".") else { return nil }
            return Int(name[name.index(name.startIndex, offsetBy: 5)..<dot])
        }
        var number = (numbered.max() ?? 0) + 1
        while true {
            let candidate = "image\(number).\(fileExtension)"
            if !existing.contains(candidate) && !reserved.contains(candidate) { return candidate }
            number += 1
        }
    }

    static func migrateNames(_ catalog: ShelfCatalog) throws -> ShelfCatalog {
        let manager = FileManager.default
        let photos = catalog.shelves.flatMap(\.photos)
        guard !photos.isEmpty else { return catalog }
        let oldNames = photos.map(\.filename)
        guard Set(oldNames).count == oldNames.count,
              oldNames.allSatisfy({ manager.fileExists(atPath: root.appendingPathComponent($0).path) }) else {
            return catalog // Leave incomplete or ambiguous catalogs untouched.
        }
        var result = catalog
        var created: [URL] = []
        var replacements: [(URL, URL)] = []
        var reserved = Set(oldNames)
        do {
            for shelfIndex in result.shelves.indices {
                for photoIndex in result.shelves[shelfIndex].photos.indices {
                    let oldName = result.shelves[shelfIndex].photos[photoIndex].filename
                    let oldURL = root.appendingPathComponent(oldName)
                    let fileExtension = oldURL.pathExtension.isEmpty ? "image" : oldURL.pathExtension.lowercased()
                    let expected = "image\(photosBefore(result, shelfIndex, photoIndex) + 1).\(fileExtension)"
                    if oldName == expected { continue }
                    // Existing originals remain in place until the new catalog is durable.
                    let name = reserved.contains(expected) || manager.fileExists(atPath: root.appendingPathComponent(expected).path)
                        ? try nextName(extension: fileExtension, excluding: reserved)
                        : expected
                    let newURL = root.appendingPathComponent(name)
                    try manager.copyItem(at: oldURL, to: newURL)
                    created.append(newURL)
                    guard try digest(oldURL) == digest(newURL) else { throw CocoaError(.fileReadCorruptFile) }
                    result.shelves[shelfIndex].photos[photoIndex].filename = name
                    replacements.append((oldURL, newURL))
                    reserved.insert(name)
                }
            }
            guard !replacements.isEmpty else { return catalog }
            try JSONEncoder().encode(result).write(to: root.appendingPathComponent("catalog.json"), options: .atomic)
            for (oldURL, newURL) in replacements where (try? digest(oldURL)) == (try? digest(newURL)) {
                try? manager.removeItem(at: oldURL)
            }
            return result
        } catch {
            created.forEach { try? manager.removeItem(at: $0) }
            throw error
        }
    }

    private static func photosBefore(_ catalog: ShelfCatalog, _ shelfIndex: Int, _ photoIndex: Int) -> Int {
        catalog.shelves.prefix(shelfIndex).reduce(0) { $0 + $1.photos.count } + photoIndex
    }

    @MainActor
    static func thumbnail(_ photo: ShelfPhoto, pixels: Int) -> UIImage? {
        thumbnail(at: photoURL(for: photo), pixels: pixels)
    }

    static func thumbnail(at url: URL, pixels: Int) -> UIImage? {
        guard let source = CGImageSourceCreateWithURL(url as CFURL, nil),
              let image = CGImageSourceCreateThumbnailAtIndex(source, 0, [
                kCGImageSourceCreateThumbnailFromImageAlways: true,
                kCGImageSourceCreateThumbnailWithTransform: true,
                kCGImageSourceThumbnailMaxPixelSize: max(1, pixels)
              ] as CFDictionary) else { return nil }
        return UIImage(cgImage: image)
    }

    static func importPhoto(_ url: URL) throws -> ShelfPhoto {
        let access = url.startAccessingSecurityScopedResource()
        defer { if access { url.stopAccessingSecurityScopedResource() } }
        guard let source = CGImageSourceCreateWithURL(url as CFURL, nil),
              let properties = CGImageSourceCopyPropertiesAtIndex(source, 0, nil) as? [CFString: Any],
              let width = properties[kCGImagePropertyPixelWidth] as? Double,
              let height = properties[kCGImagePropertyPixelHeight] as? Double,
              width > 0, height > 0 else {
            throw CocoaError(.fileReadCorruptFile)
        }
        try FileManager.default.createDirectory(at: root, withIntermediateDirectories: true)
        let name = try nextName(extension: url.pathExtension.isEmpty ? "image" : url.pathExtension.lowercased())
        let target = root.appendingPathComponent(name)
        try FileManager.default.copyItem(at: url, to: target)
        guard try digest(url) == digest(target) else {
            try? FileManager.default.removeItem(at: target)
            throw CocoaError(.fileReadCorruptFile)
        }
        let orientation = properties[kCGImagePropertyOrientation] as? Int ?? 1
        let swapped = (5...8).contains(orientation)
        return ShelfPhoto(filename: name, width: swapped ? height : width, height: swapped ? width : height)
    }

    static func pdf(_ shelf: ImageShelf, landscape: Bool, resolved: [UUID: URL] = [:]) throws -> URL {
        let width: CGFloat = landscape ? 842 : 595
        let height: CGFloat = landscape ? 595 : 842
        let columnWidth = width / CGFloat(min(6, max(1, shelf.columns)))
        var heights = Array(repeating: CGFloat.zero, count: min(6, max(1, shelf.columns)))
        let placements: [(ShelfPhoto, CGRect)] = shelf.photos.map { photo in
            let column = heights.indices.min(by: { heights[$0] < heights[$1] }) ?? 0
            let rect = CGRect(x: CGFloat(column) * columnWidth, y: heights[column],
                              width: columnWidth, height: columnWidth * photo.height / max(1, photo.width))
            heights[column] += rect.height
            return (photo, rect)
        }
        let pages = max(1, Int(ceil((heights.max() ?? 0) / height)))
        let url = FileManager.default.temporaryDirectory.appendingPathComponent("Umtomi-\(UUID().uuidString).pdf")
        let renderer = UIGraphicsPDFRenderer(bounds: CGRect(x: 0, y: 0, width: width, height: height))
        try renderer.writePDF(to: url) { context in
            for page in 0..<pages {
                context.beginPage()
                let visible = CGRect(x: 0, y: CGFloat(page) * height, width: width, height: height)
                for (photo, rect) in placements where rect.intersects(visible) {
                    autoreleasepool {
                        thumbnail(at: resolved[photo.id] ?? root.appendingPathComponent(photo.filename), pixels: 2048)?.draw(in: rect.offsetBy(dx: 0, dy: -visible.minY))
                    }
                }
            }
        }
        return url
    }
}

@MainActor
final class BookshelfLibrary: ObservableObject {
    @Published private(set) var catalog = ShelfCatalog()
    @Published var error: String?
    @Published var busy = false
    private var canSave = true

    init() {
        let file = ShelfFiles.root.appendingPathComponent("catalog.json")
        guard FileManager.default.fileExists(atPath: file.path) else {
            catalog.shelves[0].virtual = true
            return
        }
        do {
            catalog = try JSONDecoder().decode(ShelfCatalog.self, from: Data(contentsOf: file))
            if catalog.shelves.isEmpty { catalog.shelves = [ImageShelf(name: "Bookshelf")] }
            for i in catalog.shelves.indices { catalog.shelves[i].columns = min(6, max(1, catalog.shelves[i].columns)) }
            // Older catalogs have no parent IDs. Keep file paths and image order intact.
            if catalog.shelves.flatMap(\.photos).allSatisfy({ $0.cloud == nil }),
               catalog.shelves.flatMap(\.photos).contains(where: { !$0.filename.hasPrefix("image") }) {
                catalog = try ShelfFiles.migrateNames(catalog)
            }
        } catch {
            canSave = false // Preserve a damaged catalog for recovery instead of overwriting it.
            self.error = error.localizedDescription
        }
    }

    func syncCatalog() throws -> ShelfCatalog {
        guard canSave else { throw DriveSyncFailure.invalid }
        guard !busy else { throw DriveSyncFailure.busy }
        return catalog
    }

    func validateSyncCatalog(matching snapshot: ShelfCatalog) throws {
        try Task.checkCancellation()
        let current = try syncCatalog()
        let encoder = JSONEncoder()
        encoder.outputFormatting = [.sortedKeys]
        guard try encoder.encode(current.shelves) == encoder.encode(snapshot.shelves) else { throw DriveSyncFailure.changed }
    }

    func applyCloudCatalog(_ next: ShelfCatalog, matching snapshot: ShelfCatalog) throws {
        try validateSyncCatalog(matching: snapshot)
        let current = catalog
        let encoder = JSONEncoder()
        encoder.outputFormatting = [.sortedKeys]
        var updated = next
        if updated.shelves.contains(where: { $0.id == current.selected }) { updated.selected = current.selected }
        guard try encoder.encode(updated) != encoder.encode(current) else { return }
        let original = ShelfFiles.root.appendingPathComponent("catalog.json")
        if FileManager.default.fileExists(atPath: original.path) {
            try Data(contentsOf: original).write(to: ShelfFiles.root.appendingPathComponent("catalog.before-sync.json"), options: .atomic)
        }
        guard commit(updated) else { throw DriveSyncFailure.invalid }
    }

    var index: Int { catalog.shelves.firstIndex { $0.id == catalog.selected } ?? 0 }
    var shelf: ImageShelf { catalog.shelves[index] }
    var displayedShelf: ImageShelf {
        var result = shelf
        result.photos = subtree(of: shelf.id).flatMap(\.photos)
        return result
    }

    func children(of id: UUID?) -> [ImageShelf] {
        catalog.shelves.filter { $0.parentID == id }
    }

    func subtree(of root: UUID) -> [ImageShelf] {
        var visited = Set<UUID>()
        func walk(_ id: UUID) -> [ImageShelf] {
            guard visited.insert(id).inserted,
                  let item = catalog.shelves.first(where: { $0.id == id }) else { return [] }
            return [item] + children(of: id).flatMap { walk($0.id) }
        }
        return walk(root)
    }

    @discardableResult
    private func commit(_ next: ShelfCatalog) -> Bool {
        guard canSave else { return false }
        do {
            try FileManager.default.createDirectory(at: ShelfFiles.root, withIntermediateDirectories: true)
            try JSONEncoder().encode(next).write(to: ShelfFiles.root.appendingPathComponent("catalog.json"), options: .atomic)
            catalog = next
            return true
        } catch {
            self.error = error.localizedDescription
            return false
        }
    }

    func select(_ id: UUID) {
        guard !busy, catalog.shelves.contains(where: { $0.id == id }) else { return }
        var next = catalog
        next.selected = id
        commit(next)
    }

    func update(_ action: (inout ImageShelf) -> Void) {
        guard !busy else { return }
        var next = catalog
        action(&next.shelves[index])
        next.shelves[index].virtual = nil
        commit(next)
    }

    @discardableResult
    func name(_ raw: String, rename: Bool, parentID: UUID? = nil, selectNew: Bool = true) -> UUID? {
        let name = raw.trimmingCharacters(in: .whitespacesAndNewlines)
        let parent = rename ? shelf.parentID : parentID
        guard !busy, !name.isEmpty, !name.contains("/"), name.utf16.count <= 200,
              name != ".", name != "..",
              parent == nil || catalog.shelves.contains(where: { $0.id == parent }),
              !children(of: parent).contains(where: {
                  $0.name.localizedCaseInsensitiveCompare(name) == .orderedSame && (!rename || $0.id != shelf.id)
              }) else { return nil }
        var next = catalog
        if rename {
            next.shelves[index].name = name
            return commit(next) ? shelf.id : nil
        }
        let item = ImageShelf(name: name, parentID: parent)
        next.shelves.append(item)
        if selectNew { next.selected = item.id }
        return commit(next) ? item.id : nil
    }

    func moveShelf(by step: Int) {
        guard !busy else { return }
        let siblings = children(of: shelf.parentID)
        guard let position = siblings.firstIndex(where: { $0.id == shelf.id }),
              siblings.indices.contains(position + step),
              let destination = catalog.shelves.firstIndex(where: { $0.id == siblings[position + step].id }) else { return }
        var next = catalog
        next.shelves.swapAt(index, destination)
        commit(next)
    }

    func moveShelf(to parentID: UUID?) {
        guard !busy, shelf.parentID != parentID,
              parentID == nil || catalog.shelves.contains(where: { $0.id == parentID }),
              !subtree(of: shelf.id).contains(where: { $0.id == parentID }) else { return }
        guard !children(of: parentID).contains(where: { $0.name.localizedCaseInsensitiveCompare(shelf.name) == .orderedSame }) else {
            error = String(localized: "A shelf with this name already exists there.")
            return
        }
        var next = catalog
        var item = next.shelves.remove(at: index)
        item.parentID = parentID
        next.shelves.append(item)
        commit(next)
    }

    func deleteShelf() {
        guard !busy else { return }
        let parent = shelf.parentID
        let deleted = subtree(of: shelf.id)
        let ids = Set(deleted.map(\.id))
        var next = catalog
        next.shelves.removeAll { ids.contains($0.id) }
        if next.shelves.isEmpty { next.shelves.append(ImageShelf(name: "Bookshelf")) }
        next.selected = parent ?? next.shelves.first?.id
        if commit(next) { removeUnreferenced(deleted.flatMap(\.photos)) }
    }

    func deletePhoto(_ photo: ShelfPhoto) {
        guard !busy, let owner = catalog.shelves.firstIndex(where: { $0.photos.contains { $0.id == photo.id } }) else { return }
        var next = catalog
        next.shelves[owner].photos.removeAll { $0.id == photo.id }
        if commit(next) { removeUnreferenced([photo]) }
    }

    private func removeUnreferenced(_ photos: [ShelfPhoto]) {
        let live = Set(catalog.shelves.flatMap(\.photos).map(\.filename))
        for photo in photos where !live.contains(photo.filename) {
            try? FileManager.default.removeItem(at: ShelfFiles.root.appendingPathComponent(photo.filename))
        }
    }

    @discardableResult
    func add(_ urls: [URL], to destination: UUID? = nil) async -> Int {
        guard !busy, canSave else { return 0 }
        let id = destination ?? shelf.id
        guard let target = catalog.shelves.firstIndex(where: { $0.id == id }) else { return 0 }
        busy = true
        defer { busy = false }
        var added = 0
        for url in urls {
            do {
                let photo = try await Task.detached(priority: .userInitiated) { try ShelfFiles.importPhoto(url) }.value
                var next = catalog
                next.shelves[target].photos.append(photo)
                next.shelves[target].virtual = nil
                if commit(next) { added += 1 }
                else { removeUnreferenced([photo]) }
            } catch { self.error = error.localizedDescription }
        }
        return added
    }

}

private struct ShelfThumbnail: View {
    let photo: ShelfPhoto
    let width: CGFloat
    @State private var image: UIImage?
    @State private var failed = false
    var body: some View {
        Group {
            if let image { Image(uiImage: image).resizable().scaledToFit() }
            else if failed {
                Button("Retry photo") { failed = false; Task { await load() } }
                    .frame(maxWidth: .infinity, maxHeight: .infinity)
            } else { Rectangle().fill(.secondary.opacity(0.15)).overlay { ProgressView() } }
        }
        .frame(width: width, height: width * photo.height / max(1, photo.width))
        .clipped()
        .task(id: "\(Int(width))-\(photo.filename)") { await load() }
    }

    private func load() async {
        let pixels = Int(max(1, width * UIScreen.main.scale) * max(1, photo.height / max(1, photo.width)))
        do {
            let url = try await DriveSyncCoordinator.shared.openPhoto(photo)
            defer { DriveSyncCoordinator.discardTemporaryPhoto(url) }
            image = await Task.detached { ShelfFiles.thumbnail(at: url, pixels: min(4096, pixels)) }.value
            failed = image == nil
        } catch { if !Task.isCancelled { failed = true } }
    }
}

struct BookshelfView: View {
    @ObservedObject var library: BookshelfLibrary
    let store: AppStore
    @Environment(\.colorScheme) private var colorScheme
    @State private var importing = false
    @State private var naming = false
    @State private var renaming = false
    @State private var newChild = false
    @State private var showingTree = false
    @State private var moving = false
    @State private var name = ""
    @State private var fullscreen = false
    @State private var deletingShelf = false
    @State private var deletingPhoto: ShelfPhoto?
    @State private var pdfURL: URL?
    @State private var pdfOrientation = false

    var body: some View {
        VStack(spacing: 8) {
            Button { showingTree = true } label: {
                Label(library.shelf.name, systemImage: "folder")
                    .frame(maxWidth: .infinity, alignment: .leading)
            }
            .buttonStyle(.bordered)
            .accessibilityHint("Open shelf folders")
            TextField("Shelf note", text: Binding(get: { library.shelf.note }, set: { value in library.update { $0.note = value } }), axis: .vertical)
                .lineLimit(1...3).textFieldStyle(.roundedBorder)
            Stepper("Columns: \(library.shelf.columns)", value: Binding(get: { library.shelf.columns }, set: { value in library.update { $0.columns = value } }), in: 1...6)
            wall
        }
        .padding(.horizontal, 12)
        .background(colorScheme == .dark ? Color.black : Color.clear)
        .navigationTitle("Bookshelf")
        .toolbar {
            ToolbarItem(placement: .topBarTrailing) {
                NavigationLink {
                    DriveSyncSettings(store: store)
                } label: {
                    Label("Google Drive 동기화", systemImage: "icloud")
                }
            }
            ToolbarItem(placement: .primaryAction) {
                Menu {
                    Button("Add images", systemImage: "photo.badge.plus") { importing = true }
                    Button("Paste image", systemImage: "doc.on.clipboard") {
                        guard let data = UIPasteboard.general.image?.pngData() else { return }
                        let url = FileManager.default.temporaryDirectory.appendingPathComponent("shelf-\(UUID().uuidString).png")
                        do {
                            try data.write(to: url, options: .atomic)
                            Task { await library.add([url]); try? FileManager.default.removeItem(at: url) }
                        } catch { library.error = error.localizedDescription }
                    }
                    Button("New shelf", systemImage: "folder.badge.plus") { name = ""; newChild = false; renaming = false; naming = true }
                    Button("New child shelf", systemImage: "folder.badge.plus") { name = ""; newChild = true; renaming = false; naming = true }
                    Button("Move up", systemImage: "arrow.up") { library.moveShelf(by: -1) }
                    Button("Move down", systemImage: "arrow.down") { library.moveShelf(by: 1) }
                    Button("Move to folder", systemImage: "folder") { moving = true }
                    Button("Rename shelf", systemImage: "pencil") { name = library.shelf.name; renaming = true; naming = true }
                    Button("Full screen", systemImage: "arrow.up.left.and.arrow.down.right") { fullscreen = true }
                    Button("Export PDF", systemImage: "square.and.arrow.up") { pdfOrientation = true }
                    Button("Delete shelf", role: .destructive) { deletingShelf = true }
                } label: { Image(systemName: "ellipsis.circle") }
                .disabled(library.busy)
            }
        }
        .overlay { if library.busy { ProgressView().padding().background(.regularMaterial, in: RoundedRectangle(cornerRadius: 12)) } }
        .fileImporter(isPresented: $importing, allowedContentTypes: [.image], allowsMultipleSelection: true) { result in
            switch result {
            case .success(let urls): Task { await library.add(urls) }
            case .failure(let error): library.error = error.localizedDescription
            }
        }
        .alert("Shelf name", isPresented: $naming) {
            TextField("Name", text: $name)
            Button("Save") {
                if library.name(name, rename: renaming, parentID: newChild ? library.shelf.id : nil) == nil,
                   library.error == nil { library.error = String(localized: "Enter a unique shelf name.") }
            }
            Button("Cancel", role: .cancel) { }
        }
        .confirmationDialog("Delete this shelf, its subfolders, and their images?", isPresented: $deletingShelf, titleVisibility: .visible) {
            Button("Delete", role: .destructive) { library.deleteShelf() }
        }
        .confirmationDialog("Delete image?", isPresented: Binding(get: { deletingPhoto != nil }, set: { if !$0 { deletingPhoto = nil } }), titleVisibility: .visible) {
            Button("Delete", role: .destructive) { if let photo = deletingPhoto { library.deletePhoto(photo) }; deletingPhoto = nil }
        }
        .confirmationDialog("PDF orientation", isPresented: $pdfOrientation) {
            Button("Portrait") { exportPDF(false) }
            Button("Landscape") { exportPDF(true) }
        }
        .alert("Error", isPresented: Binding(get: { library.error != nil }, set: { if !$0 { library.error = nil } })) {
            Button("OK") { library.error = nil }
        } message: { Text(library.error ?? "") }
        .sheet(isPresented: Binding(get: { pdfURL != nil }, set: { if !$0 { if let url = pdfURL { try? FileManager.default.removeItem(at: url) }; pdfURL = nil } })) {
            if let pdfURL { ShelfShareSheet(url: pdfURL) }
        }
        .sheet(isPresented: $showingTree) {
            ShelfFolderPicker(library: library, title: "Bookshelf", selectedID: library.shelf.id) { id in
                library.select(id)
                showingTree = false
            }
        }
        .sheet(isPresented: $moving) {
            ShelfFolderPicker(library: library, title: "Move to folder",
                              excluded: Set(library.subtree(of: library.shelf.id).map(\.id)),
                              moveToRoot: { library.moveShelf(to: nil); moving = false }) { id in
                library.moveShelf(to: id)
                moving = false
            }
        }
        .fullScreenCover(isPresented: $fullscreen) {
            wall.background(.black).ignoresSafeArea().statusBarHidden()
                .overlay(alignment: .topTrailing) {
                    Button { fullscreen = false } label: { Image(systemName: "xmark.circle.fill").font(.largeTitle) }.padding(20)
                }
        }
    }

    private var wall: some View {
        GeometryReader { geometry in
            let count = library.shelf.columns
            let width = geometry.size.width / CGFloat(count)
            let columns = photoColumns(count)
            ScrollView {
                HStack(alignment: .top, spacing: 0) {
                    ForEach(0..<count, id: \.self) { column in
                        LazyVStack(spacing: 0) {
                            ForEach(columns[column]) { photo in
                                ShelfThumbnail(photo: photo, width: width)
                                    .contextMenu { Button("Delete image", role: .destructive) { deletingPhoto = photo } }
                            }
                        }
                    }
                }
            }
            .id(library.shelf.id)
        }
    }

    private func photoColumns(_ count: Int) -> [[ShelfPhoto]] {
        var columns = Array(repeating: [ShelfPhoto](), count: count)
        var heights = Array(repeating: 0.0, count: count)
        for photo in library.displayedShelf.photos {
            let i = heights.indices.min(by: { heights[$0] < heights[$1] }) ?? 0
            columns[i].append(photo)
            heights[i] += photo.height / max(1, photo.width)
        }
        return columns
    }

    private func exportPDF(_ landscape: Bool) {
        let shelf = library.displayedShelf
        library.busy = true
        Task {
            defer { library.busy = false }
            do {
                var resolved: [UUID: URL] = [:]
                defer { resolved.values.forEach(DriveSyncCoordinator.discardTemporaryPhoto) }
                for photo in shelf.photos { resolved[photo.id] = try await DriveSyncCoordinator.shared.openPhoto(photo, forExport: true) }
                let files = resolved
                pdfURL = try await Task.detached { try ShelfFiles.pdf(shelf, landscape: landscape, resolved: files) }.value
            }
            catch { library.error = error.localizedDescription }
        }
    }
}

struct ShelfFolderPicker: View {
    @Environment(\.dismiss) private var dismiss
    @Environment(\.colorScheme) private var colorScheme
    @ObservedObject var library: BookshelfLibrary
    let title: LocalizedStringKey
    var selectedID: UUID? = nil
    var excluded: Set<UUID> = []
    var moveToRoot: (() -> Void)? = nil
    var createFolder: (() -> Void)? = nil
    let select: (UUID) -> Void
    @State private var pendingID: UUID? = nil
    @State private var pendingRoot = false

    var body: some View {
        NavigationStack {
            List {
                if moveToRoot != nil {
                    Button {
                        pendingRoot = true
                        pendingID = nil
                    } label: {
                        HStack {
                            Label("Move to top level", systemImage: pendingRoot ? "checkmark.circle.fill" : "folder")
                            Spacer()
                            if pendingRoot {
                                Image(systemName: "checkmark").foregroundStyle(.tint)
                            }
                        }
                    }
                }
                ForEach(library.children(of: nil)) { item in
                    if !excluded.contains(item.id) { row(item) }
                }
                if let createFolder { Button("New shelf", systemImage: "folder.badge.plus", action: createFolder) }
            }
            .scrollContentBackground(colorScheme == .dark ? .hidden : .automatic)
            .background(colorScheme == .dark ? Color.black : Color.clear)
            .navigationTitle(title)
            .onAppear {
                if moveToRoot == nil { pendingID = selectedID }
            }
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button("Close") { dismiss() } }
                ToolbarItem(placement: .confirmationAction) {
                    Button("Confirm") {
                        if pendingRoot, let moveToRoot {
                            moveToRoot()
                        } else if let id = pendingID {
                            select(id)
                        }
                    }
                    .disabled(!pendingRoot && pendingID == nil)
                }
            }
        }
    }

    private func row(_ root: ImageShelf) -> some View {
        ShelfFolderRow(
            library: library,
            shelf: root,
            selectedID: pendingRoot ? nil : (pendingID ?? selectedID),
            excluded: excluded,
            isMoveMode: moveToRoot != nil,
            onPick: { id in
                pendingRoot = false
                pendingID = id
            },
            onConfirm: { id in
                select(id)
            }
        )
    }
}

private struct ShelfFolderRow: View {
    @ObservedObject var library: BookshelfLibrary
    let shelf: ImageShelf
    let selectedID: UUID?
    let excluded: Set<UUID>
    let isMoveMode: Bool
    let onPick: (UUID) -> Void
    let onConfirm: (UUID) -> Void
    @State private var expanded = true

    var body: some View {
        let children = library.children(of: shelf.id).filter { !excluded.contains($0.id) }
        if children.isEmpty {
            label
        } else {
            DisclosureGroup(isExpanded: $expanded) {
                ForEach(children) { child in
                    ShelfFolderRow(
                        library: library,
                        shelf: child,
                        selectedID: selectedID,
                        excluded: excluded,
                        isMoveMode: isMoveMode,
                        onPick: onPick,
                        onConfirm: onConfirm
                    )
                }
            } label: { label }
        }
    }

    private var label: some View {
        HStack {
            Button {
                onPick(shelf.id)
                if !isMoveMode { onConfirm(shelf.id) }
            } label: {
                Label(shelf.name, systemImage: selectedID == shelf.id ? "folder.fill" : "folder")
            }
            .buttonStyle(.borderless)
            Spacer()
            if isMoveMode {
                Button(selectedID == shelf.id ? "Move here" : "Select") {
                    if selectedID == shelf.id {
                        onConfirm(shelf.id)
                    } else {
                        onPick(shelf.id)
                    }
                }
                .buttonStyle(.borderedProminent)
                .controlSize(.small)
            } else if selectedID == shelf.id {
                Image(systemName: "checkmark").foregroundStyle(.tint)
            }
        }
    }
}

private struct ShelfShareSheet: UIViewControllerRepresentable {
    let url: URL
    func makeUIViewController(context: Context) -> UIActivityViewController {
        UIActivityViewController(activityItems: [url], applicationActivities: nil)
    }
    func updateUIViewController(_ controller: UIActivityViewController, context: Context) { }
}
