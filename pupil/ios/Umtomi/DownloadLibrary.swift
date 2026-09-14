import Foundation

actor DownloadLibrary {
    static let shared = DownloadLibrary()

    struct DownloadManifest: Codable, Identifiable {
        let galleryID: Int
        let coverFileName: String?
        let pageFileNames: [String]
        let downloadedAt: Date
        let info: GalleryInfo

        var id: Int { galleryID }
    }

    private let fileManager = FileManager.default
    private let hitomi = HitomiService.shared
    private let encoder = JSONEncoder()
    private let decoder = JSONDecoder()
    private let baseURL: URL
    private let legacyBaseURLs: [URL]

    init() {
        let fileManager = FileManager.default
        let supportURL = fileManager.urls(for: .applicationSupportDirectory, in: .userDomainMask).first
            ?? fileManager.urls(for: .documentDirectory, in: .userDomainMask).first!
        baseURL = supportURL.appendingPathComponent("UmtomiDownloads", isDirectory: true)
        legacyBaseURLs = [
            supportURL.appendingPathComponent("JotomiDownloads", isDirectory: true),
            supportURL.appendingPathComponent("PupilDownloads", isDirectory: true)
        ]

        do {
            if !fileManager.fileExists(atPath: baseURL.path),
               let legacyBaseURL = legacyBaseURLs.first(where: { fileManager.fileExists(atPath: $0.path) }) {
                try fileManager.moveItem(at: legacyBaseURL, to: baseURL)
            }

            try fileManager.createDirectory(at: baseURL, withIntermediateDirectories: true)

            var values = URLResourceValues()
            values.isExcludedFromBackup = true
            var mutableURL = baseURL
            try? mutableURL.setResourceValues(values)
        } catch {
            assertionFailure("Failed to create download directory: \(error)")
        }
    }

    func isDownloaded(galleryID: Int) -> Bool {
        fileManager.fileExists(atPath: manifestURL(for: galleryID).path)
    }

    func manifest(for galleryID: Int) -> DownloadManifest? {
        loadManifest(for: galleryID)
    }

    func downloadedSummaries() -> [GallerySummary] {
        allManifests().map { manifest in
            manifest.info.summary(coverURL: localCoverURL(for: manifest))
        }
    }

    func downloadGallery(
        info: GalleryInfo,
        progress: @escaping @Sendable (Double) async -> Void = { _ in }
    ) async throws {
        let galleryID = info.numericID
        let finalDirectory = galleryDirectory(for: galleryID)
        let temporaryDirectory = finalDirectory.appendingPathExtension("tmp")

        try removeIfExists(at: temporaryDirectory)
        try fileManager.createDirectory(at: temporaryDirectory, withIntermediateDirectories: true)

        do {
            await progress(0)

            let coverURL = try await hitomi.coverURL(for: info)
            let pages = try await hitomi.readerPages(for: info)
            let totalCount = max(pages.count + (coverURL == nil ? 0 : 1), 1)
            var completedCount = 0

            var coverFileName: String?
            if let coverURL {
                let fileName = "cover.\(safeExtension(from: coverURL))"
                let fileURL = temporaryDirectory.appendingPathComponent(fileName)
                let data = try await hitomi.imageData(for: coverURL)
                try data.write(to: fileURL, options: .atomic)
                coverFileName = fileName

                completedCount += 1
                await progress(Double(completedCount) / Double(totalCount))
            }

            var pageFileNames: [String] = []
            pageFileNames.reserveCapacity(pages.count)

            for (index, page) in pages.enumerated() {
                let fileName = String(format: "page-%04d.%@", index, safeExtension(from: page.url))
                let fileURL = temporaryDirectory.appendingPathComponent(fileName)
                let data = try await hitomi.imageData(for: page.url)
                try data.write(to: fileURL, options: .atomic)
                pageFileNames.append(fileName)

                completedCount += 1
                await progress(Double(completedCount) / Double(totalCount))
            }

            let manifest = DownloadManifest(
                galleryID: galleryID,
                coverFileName: coverFileName,
                pageFileNames: pageFileNames,
                downloadedAt: Date(),
                info: info
            )
            let manifestURL = temporaryDirectory.appendingPathComponent("manifest.json")
            let manifestData = try encoder.encode(manifest)
            try manifestData.write(to: manifestURL, options: .atomic)

            try removeIfExists(at: finalDirectory)
            try fileManager.moveItem(at: temporaryDirectory, to: finalDirectory)
            await progress(1)
        } catch {
            try? removeIfExists(at: temporaryDirectory)
            throw error
        }
    }

    func removeDownload(galleryID: Int) throws {
        try removeIfExists(at: galleryDirectory(for: galleryID))
    }

    func clearDownloads() throws {
        try removeIfExists(at: baseURL)
        try fileManager.createDirectory(at: baseURL, withIntermediateDirectories: true)
    }

    func localReaderPages(for galleryID: Int) -> [ReaderPage] {
        guard let manifest = loadManifest(for: galleryID) else {
            return []
        }

        return manifest.pageFileNames.enumerated().map { index, fileName in
            let file = manifest.info.files.indices.contains(index) ? manifest.info.files[index] : nil
            return ReaderPage(
                id: index,
                url: galleryDirectory(for: galleryID).appendingPathComponent(fileName),
                width: file?.width ?? 1,
                height: file?.height ?? 1
            )
        }
    }

    func localGalleryInfo(for galleryID: Int) -> GalleryInfo? {
        loadManifest(for: galleryID)?.info
    }

    func localCoverURL(for galleryID: Int) -> URL? {
        guard let manifest = loadManifest(for: galleryID) else {
            return nil
        }

        return localCoverURL(for: manifest)
    }

    private func allManifests() -> [DownloadManifest] {
        let directoryURLs = (try? fileManager.contentsOfDirectory(
            at: baseURL,
            includingPropertiesForKeys: [.isDirectoryKey],
            options: [.skipsHiddenFiles]
        )) ?? []

        return directoryURLs.compactMap { directoryURL in
            loadManifest(at: directoryURL)
        }
        .sorted { $0.downloadedAt > $1.downloadedAt }
    }

    private func localCoverURL(for manifest: DownloadManifest) -> URL? {
        guard let coverFileName = manifest.coverFileName else {
            return nil
        }

        let coverURL = galleryDirectory(for: manifest.galleryID).appendingPathComponent(coverFileName)
        return fileManager.fileExists(atPath: coverURL.path) ? coverURL : nil
    }

    private func loadManifest(for galleryID: Int) -> DownloadManifest? {
        loadManifest(at: galleryDirectory(for: galleryID))
    }

    private func loadManifest(at directoryURL: URL) -> DownloadManifest? {
        let manifestURL = directoryURL.appendingPathComponent("manifest.json")

        guard let data = try? Data(contentsOf: manifestURL) else {
            return nil
        }

        return try? decoder.decode(DownloadManifest.self, from: data)
    }

    private func galleryDirectory(for galleryID: Int) -> URL {
        baseURL.appendingPathComponent(String(galleryID), isDirectory: true)
    }

    private func manifestURL(for galleryID: Int) -> URL {
        galleryDirectory(for: galleryID).appendingPathComponent("manifest.json")
    }

    private func safeExtension(from url: URL) -> String {
        let pathExtension = url.pathExtension.trimmingCharacters(in: .whitespacesAndNewlines)
        return pathExtension.isEmpty ? "img" : pathExtension
    }

    private func removeIfExists(at url: URL) throws {
        guard fileManager.fileExists(atPath: url.path) else {
            return
        }

        try fileManager.removeItem(at: url)
    }
}
