import Foundation
import SwiftUI
import UniformTypeIdentifiers

struct FavoritesBackupPayload: Codable {
    let appName: String
    let exportedAt: Date
    let favorites: [Int]
    let favoriteLibrary: FavoriteLibrary?
    let favoriteArtists: [FavoriteArtist]?
}

struct FavoritesBackupDocument: FileDocument {
    static var readableContentTypes: [UTType] { [.json] }

    let payload: FavoritesBackupPayload

    var favorites: [Int] {
        payload.favorites
    }

    var favoriteLibrary: FavoriteLibrary {
        payload.favoriteLibrary ?? FavoriteLibrary()
    }

    var favoriteArtists: [FavoriteArtist] {
        FavoriteArtist.uniqueArtists(from: payload.favoriteArtists ?? [])
    }

    init(
        favorites: [Int],
        favoriteLibrary: FavoriteLibrary = FavoriteLibrary(),
        favoriteArtists: [FavoriteArtist] = [],
        exportedAt: Date = Date()
    ) {
        payload = FavoritesBackupPayload(
            appName: "Umtomi",
            exportedAt: exportedAt,
            favorites: Self.uniqueFavorites(from: favorites),
            favoriteLibrary: favoriteLibrary.sanitized(validGalleryIDs: Set(favorites)),
            favoriteArtists: FavoriteArtist.uniqueArtists(from: favoriteArtists)
        )
    }

    init(configuration: ReadConfiguration) throws {
        guard let data = configuration.file.regularFileContents else {
            throw CocoaError(.fileReadCorruptFile)
        }

        payload = try Self.decoder.decode(FavoritesBackupPayload.self, from: data)
    }

    func fileWrapper(configuration: WriteConfiguration) throws -> FileWrapper {
        let data = try Self.encoder.encode(payload)
        return .init(regularFileWithContents: data)
    }

    static func load(from url: URL) throws -> FavoritesBackupDocument {
        let didAccess = url.startAccessingSecurityScopedResource()
        defer {
            if didAccess {
                url.stopAccessingSecurityScopedResource()
            }
        }

        let data = try Data(contentsOf: url)
        let payload = try decoder.decode(FavoritesBackupPayload.self, from: data)
        return FavoritesBackupDocument(payload: payload)
    }

    private init(payload: FavoritesBackupPayload) {
        let favorites = Self.uniqueFavorites(from: payload.favorites)
        self.payload = FavoritesBackupPayload(
            appName: payload.appName.isEmpty ? "Umtomi" : payload.appName,
            exportedAt: payload.exportedAt,
            favorites: favorites,
            favoriteLibrary: (payload.favoriteLibrary ?? FavoriteLibrary()).sanitized(validGalleryIDs: Set(favorites)),
            favoriteArtists: FavoriteArtist.uniqueArtists(from: payload.favoriteArtists ?? [])
        )
    }

    private static func uniqueFavorites(from values: [Int]) -> [Int] {
        var seen: Set<Int> = []
        var ordered: [Int] = []

        for value in values where seen.insert(value).inserted {
            ordered.append(value)
        }

        return ordered
    }

    private static let encoder: JSONEncoder = {
        let encoder = JSONEncoder()
        encoder.outputFormatting = [.prettyPrinted, .sortedKeys]
        encoder.dateEncodingStrategy = .iso8601
        return encoder
    }()

    private static let decoder: JSONDecoder = {
        let decoder = JSONDecoder()
        decoder.dateDecodingStrategy = .iso8601
        return decoder
    }()
}
