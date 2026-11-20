import CoreGraphics
import Foundation

enum UmtomiTab: String, CaseIterable, Identifiable, Hashable, Codable {
    case search
    case algorithm
    case downloads
    case history
    case favorites
    case bookshelf
    case settings

    var id: String { rawValue }

    var titleKey: String {
        switch self {
        case .search:
            return "Search"
        case .algorithm:
            return "Algorithm"
        case .downloads:
            return "Downloads"
        case .history:
            return "History"
        case .bookshelf:
            return "Bookshelf"
        case .favorites:
            return "Favorites"
        case .settings:
            return "Settings"
        }
    }

    var systemImage: String {
        switch self {
        case .search:
            return "magnifyingglass"
        case .algorithm:
            return "sparkles"
        case .downloads:
            return "arrow.down.circle.fill"
        case .history:
            return "clock.arrow.circlepath"
        case .bookshelf:
            return "books.vertical.fill"
        case .favorites:
            return "star.fill"
        case .settings:
            return "gearshape"
        }
    }

    static var defaultOrder: [UmtomiTab] {
        [.search, .algorithm, .downloads, .history, .favorites, .bookshelf, .settings]
    }

    static func sanitized(_ order: [UmtomiTab]) -> [UmtomiTab] {
        var seen: Set<UmtomiTab> = []
        var sanitized: [UmtomiTab] = []

        for tab in order where seen.insert(tab).inserted {
            sanitized.append(tab)
        }

        for tab in defaultOrder where seen.insert(tab).inserted {
            sanitized.append(tab)
        }

        return sanitized
    }
}

enum SortMode: String, CaseIterable, Identifiable, Codable {
    case dateAdded = "Date Added"
    case datePublished = "Date Published"
    case popularToday = "Popular: Today"
    case popularWeek = "Popular: Week"
    case popularMonth = "Popular: Month"
    case popularYear = "Popular: Year"
    case random = "Random"

    var id: String { rawValue }

    var orderBy: String {
        switch self {
        case .dateAdded, .datePublished, .random:
            return "date"
        case .popularToday, .popularWeek, .popularMonth, .popularYear:
            return "popular"
        }
    }

    var orderByKey: String {
        switch self {
        case .dateAdded, .random:
            return "added"
        case .datePublished:
            return "published"
        case .popularToday:
            return "today"
        case .popularWeek:
            return "week"
        case .popularMonth:
            return "month"
        case .popularYear:
            return "year"
        }
    }
}

enum ReaderMode: String, CaseIterable, Identifiable, Codable {
    case paged = "Paged"
    case verticalScroll = "Vertical Scroll"

    var id: String { rawValue }
}

enum ReaderResumePrefetchMode: String, CaseIterable, Identifiable, Codable {
    case fromBeginning = "From Beginning"
    case aroundResumePage = "Around Resume Page"

    var id: String { rawValue }
}

enum AppLanguage: String, CaseIterable, Identifiable, Codable {
    case korean = "ko"
    case english = "en"
    case japanese = "ja"

    var id: String { rawValue }

    var displayName: String {
        switch self {
        case .korean:
            return "한국어"
        case .english:
            return "English"
        case .japanese:
            return "日本語"
        }
    }

    var localeIdentifier: String { rawValue }
}

enum FavoriteBrowserSection: String, CaseIterable, Identifiable {
    case works = "Works"
    case artists = "Artists"

    var id: String { rawValue }
}

enum FavoriteSortOrder: String, CaseIterable, Identifiable, Codable {
    case added = "Date Favorited"
    case upload = "Upload Order"
    case name = "Name"

    var id: String { rawValue }
}

struct UmtomiSettings: Codable, Equatable {
    var defaultQuery: String = ""
    var appLanguage: AppLanguage = .english
    var darkModeEnabled: Bool = true
    var alwaysIncludedTags: String = ""
    var alwaysExcludedTags: String = ""
    var algorithmResultCount: Int = 30
    var pageSize: Int = 25
    var rightToLeft: Bool = false
    var readerMode: ReaderMode = .paged
    var resumePrefetchMode: ReaderResumePrefetchMode = .aroundResumePage
    var hideAppInSwitcher: Bool = false
    var tabOrder: [UmtomiTab] = UmtomiTab.defaultOrder
    var showsMoreTab = false
    var moreTabs: [UmtomiTab] = [.algorithm, .downloads]
    var favoriteSortOrder: FavoriteSortOrder = .added
    var favoriteSortAscending: Bool = false

    var barTabs: [UmtomiTab] {
        let order = UmtomiTab.sanitized(tabOrder)
        return showsMoreTab ? order.filter { !moreTabs.contains($0) } : order
    }

    var overflowTabs: [UmtomiTab] {
        guard showsMoreTab else { return [] }
        return UmtomiTab.sanitized(tabOrder).filter(moreTabs.contains)
    }

    enum CodingKeys: String, CodingKey {
        case defaultQuery
        case appLanguage
        case darkModeEnabled
        case alwaysIncludedTags
        case alwaysExcludedTags
        case algorithmResultCount
        case pageSize
        case rightToLeft
        case readerMode
        case resumePrefetchMode
        case hideAppInSwitcher
        case tabOrder
        case showsMoreTab
        case moreTabs
        case favoriteSortOrder
        case favoriteSortAscending
    }

    init() {}

    init(from decoder: Decoder) throws {
        let container = try decoder.container(keyedBy: CodingKeys.self)
        defaultQuery = try container.decodeIfPresent(String.self, forKey: .defaultQuery) ?? ""
        appLanguage = try container.decodeIfPresent(AppLanguage.self, forKey: .appLanguage) ?? .english
        darkModeEnabled = try container.decodeIfPresent(Bool.self, forKey: .darkModeEnabled) ?? true
        alwaysIncludedTags = try container.decodeIfPresent(String.self, forKey: .alwaysIncludedTags) ?? ""
        alwaysExcludedTags = try container.decodeIfPresent(String.self, forKey: .alwaysExcludedTags) ?? ""
        algorithmResultCount = min(max(try container.decodeIfPresent(Int.self, forKey: .algorithmResultCount) ?? 30, 5), 100)
        pageSize = try container.decodeIfPresent(Int.self, forKey: .pageSize) ?? 25
        rightToLeft = try container.decodeIfPresent(Bool.self, forKey: .rightToLeft) ?? false
        readerMode = try container.decodeIfPresent(ReaderMode.self, forKey: .readerMode) ?? .paged
        resumePrefetchMode = try container.decodeIfPresent(ReaderResumePrefetchMode.self, forKey: .resumePrefetchMode) ?? .aroundResumePage
        hideAppInSwitcher = try container.decodeIfPresent(Bool.self, forKey: .hideAppInSwitcher) ?? false
        tabOrder = UmtomiTab.sanitized(try container.decodeIfPresent([UmtomiTab].self, forKey: .tabOrder) ?? UmtomiTab.defaultOrder)
        showsMoreTab = try container.decodeIfPresent(Bool.self, forKey: .showsMoreTab) ?? false
        let hidden = Set(try container.decodeIfPresent([UmtomiTab].self, forKey: .moreTabs) ?? moreTabs)
        moreTabs = tabOrder.filter(hidden.contains)
        if moreTabs.count == tabOrder.count { moreTabs.removeFirst() }
        favoriteSortOrder = try container.decodeIfPresent(FavoriteSortOrder.self, forKey: .favoriteSortOrder) ?? .added
        favoriteSortAscending = try container.decodeIfPresent(Bool.self, forKey: .favoriteSortAscending) ?? false
    }

    func encode(to encoder: Encoder) throws {
        var container = encoder.container(keyedBy: CodingKeys.self)
        try container.encode(defaultQuery, forKey: .defaultQuery)
        try container.encode(appLanguage, forKey: .appLanguage)
        try container.encode(darkModeEnabled, forKey: .darkModeEnabled)
        try container.encode(alwaysIncludedTags, forKey: .alwaysIncludedTags)
        try container.encode(alwaysExcludedTags, forKey: .alwaysExcludedTags)
        try container.encode(min(max(algorithmResultCount, 5), 100), forKey: .algorithmResultCount)
        try container.encode(pageSize, forKey: .pageSize)
        try container.encode(rightToLeft, forKey: .rightToLeft)
        try container.encode(readerMode, forKey: .readerMode)
        try container.encode(resumePrefetchMode, forKey: .resumePrefetchMode)
        try container.encode(hideAppInSwitcher, forKey: .hideAppInSwitcher)
        try container.encode(UmtomiTab.sanitized(tabOrder), forKey: .tabOrder)
        try container.encode(showsMoreTab, forKey: .showsMoreTab)
        try container.encode(moreTabs, forKey: .moreTabs)
        try container.encode(favoriteSortOrder, forKey: .favoriteSortOrder)
        try container.encode(favoriteSortAscending, forKey: .favoriteSortAscending)
    }
}

struct FavoriteFolder: Identifiable, Codable, Hashable {
    var id: UUID
    var parentID: UUID?
    var name: String
    var manualGalleryIDs: [Int]
    var autoRuleQuery: String
    var createdAt: Date

    init(
        id: UUID = UUID(),
        parentID: UUID? = nil,
        name: String,
        manualGalleryIDs: [Int] = [],
        autoRuleQuery: String = "",
        createdAt: Date = Date()
    ) {
        self.id = id
        self.parentID = parentID
        self.name = name
        self.manualGalleryIDs = FavoriteFolder.uniqueGalleryIDs(from: manualGalleryIDs)
        self.autoRuleQuery = autoRuleQuery
        self.createdAt = createdAt
    }

    var trimmedName: String {
        name.trimmingCharacters(in: .whitespacesAndNewlines)
    }

    var hasAutoRules: Bool {
        !autoRuleQuery.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
    }

    func sanitized(validGalleryIDs: Set<Int>) -> FavoriteFolder {
        FavoriteFolder(
            id: id,
            parentID: parentID,
            name: trimmedName.isEmpty ? "Folder" : trimmedName,
            manualGalleryIDs: manualGalleryIDs.filter { validGalleryIDs.contains($0) },
            autoRuleQuery: autoRuleQuery.trimmingCharacters(in: .whitespacesAndNewlines),
            createdAt: createdAt
        )
    }

    private static func uniqueGalleryIDs(from values: [Int]) -> [Int] {
        var seen: Set<Int> = []
        var ordered: [Int] = []

        for value in values where seen.insert(value).inserted {
            ordered.append(value)
        }

        return ordered
    }
}

struct FavoriteLibrary: Codable, Equatable {
    var folders: [FavoriteFolder] = []

    func sanitized(validGalleryIDs: Set<Int>) -> FavoriteLibrary {
        let folderIDs = Set(folders.map(\.id))
        let sanitizedFolders = folders.map { folder in
            var sanitizedFolder = folder.sanitized(validGalleryIDs: validGalleryIDs)
            if let parentID = sanitizedFolder.parentID, !folderIDs.contains(parentID) || parentID == sanitizedFolder.id {
                sanitizedFolder.parentID = nil
            }
            return sanitizedFolder
        }

        return FavoriteLibrary(
            folders: FavoriteLibrary.uniqueFolders(from: sanitizedFolders)
        )
    }

    private static func uniqueFolders(from folders: [FavoriteFolder]) -> [FavoriteFolder] {
        var seen: Set<UUID> = []
        var ordered: [FavoriteFolder] = []

        for folder in folders where seen.insert(folder.id).inserted {
            ordered.append(folder)
        }

        return ordered
    }
}

struct FavoriteArtist: Identifiable, Codable, Hashable {
    var name: String
    var createdAt: Date

    init(name: String, createdAt: Date = Date()) {
        self.name = name
        self.createdAt = createdAt
    }

    var id: String {
        normalizedName
    }

    var trimmedName: String {
        name.trimmingCharacters(in: .whitespacesAndNewlines)
    }

    var normalizedName: String {
        trimmedName
            .lowercased()
            .replacingOccurrences(of: "_", with: " ")
    }

    func sanitized() -> FavoriteArtist {
        FavoriteArtist(
            name: trimmedName.isEmpty ? "Unknown Artist" : trimmedName,
            createdAt: createdAt
        )
    }

    static func uniqueArtists(from artists: [FavoriteArtist]) -> [FavoriteArtist] {
        var seen: Set<String> = []
        var ordered: [FavoriteArtist] = []

        for artist in artists {
            let sanitized = artist.sanitized()
            if seen.insert(sanitized.id).inserted {
                ordered.append(sanitized)
            }
        }

        return ordered
    }
}

struct SearchSuggestion: Identifiable, Hashable {
    let term: String
    let count: Int
    let namespace: String

    var id: String {
        "\(namespace):\(term)"
    }

    var query: String {
        let normalized = term.replacingOccurrences(of: " ", with: "_")

        switch namespace {
        case "", "global":
            return normalized
        case "female", "male":
            return "\(namespace):\(normalized)"
        case "language":
            return "language:\(normalized)"
        default:
            return "\(namespace):\(normalized)"
        }
    }

    var label: String {
        if namespace.isEmpty || namespace == "global" {
            return term
        }

        return "\(namespace):\(term)"
    }
}

struct Artist: Codable, Hashable {
    let artist: String
    let url: String
}

struct GroupInfo: Codable, Hashable {
    let group: String
    let url: String
}

struct Parody: Codable, Hashable {
    let parody: String
    let url: String
}

struct CharacterInfo: Codable, Hashable {
    let character: String
    let url: String
}

struct GalleryTag: Codable, Hashable {
    let tag: String
    let url: String
    let female: String?
    let male: String?

    enum CodingKeys: String, CodingKey {
        case tag
        case url
        case female
        case male
    }

    init(from decoder: Decoder) throws {
        let container = try decoder.container(keyedBy: CodingKeys.self)
        tag = try container.decode(String.self, forKey: .tag)
        url = try container.decode(String.self, forKey: .url)
        female = try Self.decodeNamespaceFlag(from: container, forKey: .female)
        male = try Self.decodeNamespaceFlag(from: container, forKey: .male)
    }

    private static func decodeNamespaceFlag(
        from container: KeyedDecodingContainer<CodingKeys>,
        forKey key: CodingKeys
    ) throws -> String? {
        if let stringValue = try? container.decodeIfPresent(String.self, forKey: key) {
            let trimmed = stringValue.trimmingCharacters(in: .whitespacesAndNewlines)
            return trimmed.isEmpty || trimmed == "0" ? nil : trimmed
        }

        if let intValue = try? container.decodeIfPresent(Int.self, forKey: key) {
            return intValue == 0 ? nil : String(intValue)
        }

        if let boolValue = try? container.decodeIfPresent(Bool.self, forKey: key) {
            return boolValue ? "1" : nil
        }

        return nil
    }
}

struct GalleryLanguage: Codable, Hashable {
    let galleryid: String
    let url: String
    let languageLocalname: String
    let name: String

    enum CodingKeys: String, CodingKey {
        case galleryid
        case url
        case languageLocalname = "language_localname"
        case name
    }

    init(from decoder: Decoder) throws {
        let container = try decoder.container(keyedBy: CodingKeys.self)

        if let stringValue = try? container.decode(String.self, forKey: .galleryid) {
            galleryid = stringValue
        } else if let intValue = try? container.decode(Int.self, forKey: .galleryid) {
            galleryid = String(intValue)
        } else {
            throw DecodingError.typeMismatch(
                String.self,
                DecodingError.Context(
                    codingPath: container.codingPath + [CodingKeys.galleryid],
                    debugDescription: "Expected galleryid as string or int."
                )
            )
        }

        url = try container.decode(String.self, forKey: .url)
        languageLocalname = try container.decode(String.self, forKey: .languageLocalname)
        name = try container.decode(String.self, forKey: .name)
    }
}

struct GalleryFile: Codable, Hashable {
    let width: Int
    let hash: String
    let haswebp: Int?
    let name: String
    let height: Int
    let hasavif: Int?
    let hasavifsmalltn: Int?
}

struct GalleryInfo: Codable, Hashable, Identifiable {
    let id: String
    let title: String
    let japaneseTitle: String?
    let language: String?
    let type: String
    let date: String
    let artists: [Artist]?
    let groups: [GroupInfo]?
    let parodys: [Parody]?
    let tags: [GalleryTag]?
    let related: [Int]?
    let languages: [GalleryLanguage]?
    let characters: [CharacterInfo]?
    let sceneIndexes: [Int]?
    let files: [GalleryFile]

    enum CodingKeys: String, CodingKey {
        case id
        case title
        case japaneseTitle = "japanese_title"
        case language
        case type
        case date
        case artists
        case groups
        case parodys
        case tags
        case related
        case languages
        case characters
        case sceneIndexes = "scene_indexes"
        case files
    }

    init(from decoder: Decoder) throws {
        let container = try decoder.container(keyedBy: CodingKeys.self)

        if let stringValue = try? container.decode(String.self, forKey: .id) {
            id = stringValue
        } else if let intValue = try? container.decode(Int.self, forKey: .id) {
            id = String(intValue)
        } else {
            throw DecodingError.typeMismatch(
                String.self,
                DecodingError.Context(
                    codingPath: container.codingPath + [CodingKeys.id],
                    debugDescription: "Expected id as string or int."
                )
            )
        }

        title = try container.decode(String.self, forKey: .title)
        japaneseTitle = try container.decodeIfPresent(String.self, forKey: .japaneseTitle)
        language = try container.decodeIfPresent(String.self, forKey: .language)
        type = try container.decode(String.self, forKey: .type)
        date = try container.decode(String.self, forKey: .date)
        artists = try container.decodeIfPresent([Artist].self, forKey: .artists)
        groups = try container.decodeIfPresent([GroupInfo].self, forKey: .groups)
        parodys = try container.decodeIfPresent([Parody].self, forKey: .parodys)
        tags = try container.decodeIfPresent([GalleryTag].self, forKey: .tags)
        related = try container.decodeIfPresent([Int].self, forKey: .related)
        languages = try container.decodeIfPresent([GalleryLanguage].self, forKey: .languages)
        characters = try container.decodeIfPresent([CharacterInfo].self, forKey: .characters)
        sceneIndexes = try container.decodeIfPresent([Int].self, forKey: .sceneIndexes)
        files = try container.decode([GalleryFile].self, forKey: .files)
    }

    var numericID: Int {
        Int(id) ?? 0
    }

    var pageCount: Int {
        files.count
    }

    var primaryArtists: [String] {
        artists?.map(\.artist) ?? []
    }

    var primaryGroups: [String] {
        groups?.map(\.group) ?? []
    }

    var primarySeries: [String] {
        parodys?.map(\.parody) ?? []
    }

    var primaryCharacters: [String] {
        characters?.map(\.character) ?? []
    }

    var displayLanguage: String {
        language ?? "Unknown"
    }

    func summary(coverURL: URL?) -> GallerySummary {
        GallerySummary(
            id: numericID,
            title: title,
            coverURL: coverURL,
            artists: primaryArtists,
            groups: primaryGroups,
            series: primarySeries,
            type: type,
            language: displayLanguage,
            pageCount: pageCount
        )
    }
}

struct GallerySummary: Identifiable, Hashable {
    let id: Int
    let title: String
    let coverURL: URL?
    let artists: [String]
    let groups: [String]
    let series: [String]
    let type: String
    let language: String
    let pageCount: Int
}

struct ActiveDownload: Identifiable, Hashable {
    let galleryID: Int
    let progress: Double

    var id: Int { galleryID }
}

struct ReaderPage: Identifiable, Hashable {
    let id: Int
    let url: URL
    let width: Int
    let height: Int

    var aspectRatio: CGFloat {
        let safeHeight = max(CGFloat(height), 1)
        return max(CGFloat(width), 1) / safeHeight
    }
}

struct ReaderTabSession: Identifiable, Hashable {
    let id: UUID
    let galleryID: Int
    var title: String
    var seedInfo: GalleryInfo?
    var info: GalleryInfo?
    var pages: [ReaderPage]
    var selectedPage: Int
    var scrollPosition: Int?
    var isLoading: Bool
    var errorMessage: String?
    var resumePage: Int?
    var isResumePromptPresented: Bool
    var prefetchAnchorPage: Int
    let createdAt: Date

    init(id: UUID = UUID(), galleryID: Int, seedInfo: GalleryInfo? = nil, createdAt: Date = Date()) {
        self.id = id
        self.galleryID = galleryID
        self.title = seedInfo?.title ?? "Gallery \(galleryID)"
        self.seedInfo = seedInfo
        self.info = seedInfo
        self.pages = []
        self.selectedPage = 0
        self.scrollPosition = 0
        self.isLoading = true
        self.errorMessage = nil
        self.resumePage = nil
        self.isResumePromptPresented = false
        self.prefetchAnchorPage = 0
        self.createdAt = createdAt
    }

    var displayTitle: String {
        let resolvedTitle = (info?.title ?? title).trimmingCharacters(in: .whitespacesAndNewlines)
        return resolvedTitle.isEmpty ? "Gallery \(galleryID)" : resolvedTitle
    }
}

struct GalleryRoute: Identifiable, Hashable {
    enum Presentation: String, Hashable {
        case detail
        case reader
    }

    let galleryID: Int
    let presentation: Presentation

    var id: String {
        switch presentation {
        case .detail:
            return "detail-\(galleryID)"
        case .reader:
            return "reader-workspace"
        }
    }
}

enum DeepLinkParser {
    static func galleryRoute(from url: URL) -> GalleryRoute? {
        guard let galleryID = extractGalleryID(from: url) else {
            return nil
        }

        return GalleryRoute(galleryID: galleryID, presentation: .reader)
    }

    static func extractGalleryID(from url: URL) -> Int? {
        guard let host = url.host?.lowercased() else {
            return nil
        }

        if host.hasSuffix("hasha.in") {
            return Int(url.lastPathComponent)
        }

        if host == "hitomi.la" {
            let lastPath = url.lastPathComponent
            let pattern = #"([0-9]+)\.html$"#

            guard let regex = try? NSRegularExpression(pattern: pattern),
                  let match = regex.firstMatch(in: lastPath, range: NSRange(lastPath.startIndex..., in: lastPath)),
                  let range = Range(match.range(at: 1), in: lastPath) else {
                return nil
            }

            return Int(String(lastPath[range]))
        }

        if host == "e-hentai.org" {
            let components = url.pathComponents.filter { $0 != "/" }
            guard components.count >= 2, components[0] == "g" else {
                return nil
            }

            return Int(components[1])
        }

        return nil
    }
}
