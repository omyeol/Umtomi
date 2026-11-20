import Foundation
import SwiftUI

@MainActor
final class AppStore: ObservableObject {
    @Published var selectedTab: UmtomiTab = .search
    @Published var searchQuery: String = ""
    @Published var sortMode: SortMode = .dateAdded
    @Published private(set) var suggestions: [SearchSuggestion] = []
    @Published private(set) var visibleSummaries: [GallerySummary] = []
    @Published private(set) var algorithmSummaries: [GallerySummary] = []
    @Published private(set) var downloadSummaries: [GallerySummary] = []
    @Published private(set) var favoriteSummaries: [GallerySummary] = []
    @Published private(set) var historySummaries: [GallerySummary] = []
    @Published private(set) var favoriteFolders: [FavoriteFolder] = []
    @Published private(set) var favoriteArtists: [FavoriteArtist]
    @Published private(set) var favoriteAutoMatchedIDsByFolder: [UUID: [Int]] = [:]
    @Published private(set) var isSearching: Bool = false
    @Published private(set) var isLoadingAlgorithm: Bool = false
    @Published private(set) var isLoadingPage: Bool = false
    @Published private(set) var isBootstrapping: Bool = false
    @Published private(set) var hasCompletedInitialSearch: Bool = false
    @Published private(set) var downloadProgressByGallery: [Int: Double] = [:]
    @Published private(set) var readerTabs: [ReaderTabSession] = []
    @Published var selectedReaderTabID: UUID?
    @Published var presentedGallery: GalleryRoute?
    @Published var errorMessage: String?
    @Published private(set) var recentSearches: [String]
    @Published private(set) var favorites: [Int]
    @Published private(set) var history: [Int]
    @Published private(set) var settings: UmtomiSettings

    lazy var bookshelf = BookshelfLibrary()

    private let service = HitomiService.shared
    private let downloadLibrary = DownloadLibrary.shared
    private var downloadTasks: [Int: Task<Void, Never>] = [:]
    private var clearingDownloads = false
    private var lastReadPageByGallery: [Int: Int]
    private var didBootstrap = false
    private var algorithmRefreshNeeded = true
    private var searchSettingsChanged = false
    private var allSearchResultIDs: [Int] = []
    private var currentPageIndex = 0
    private var favoriteSummariesByID: [Int: GallerySummary] = [:]
    private var favoriteAutoMatchCacheByFolder: [UUID: FavoriteAutoMatchCacheEntry] = [:]
    private var loadingReaderTabIDs: Set<UUID> = []

    private struct FavoriteAutoMatchCacheEntry {
        let query: String
        let favorites: [Int]
        let matchedIDs: [Int]
    }

    private enum StorageKey {
        static let favorites = "umtomi.ios.favorites"
        static let favoriteLibrary = "umtomi.ios.favoriteLibrary"
        static let favoriteArtists = "umtomi.ios.favoriteArtists"
        static let history = "umtomi.ios.history"
        static let recentSearches = "umtomi.ios.recentSearches"
        static let settings = "umtomi.ios.settings"
        static let lastReadPages = "umtomi.ios.lastReadPages"
    }

    init(defaults: UserDefaults = .standard) {
        self.favorites = Self.loadIntArray(
            defaults: defaults,
            key: StorageKey.favorites
        )
        self.history = Self.loadIntArray(
            defaults: defaults,
            key: StorageKey.history
        )
        self.recentSearches = Self.loadStringArray(
            defaults: defaults,
            key: StorageKey.recentSearches
        )
        self.lastReadPageByGallery = Self.loadLastReadPages(
            defaults: defaults,
            key: StorageKey.lastReadPages
        )
            .reduce(into: [:]) { partial, element in
                if let key = Int(element.key) {
                    partial[key] = element.value
                }
            }
        if let data = Self.loadData(defaults: defaults, key: StorageKey.favoriteArtists),
           let artists = try? JSONDecoder().decode([FavoriteArtist].self, from: data) {
            self.favoriteArtists = FavoriteArtist.uniqueArtists(from: artists)
        } else {
            self.favoriteArtists = []
        }

        if let data = Self.loadData(
            defaults: defaults,
            key: StorageKey.settings
        ),
           let settings = try? JSONDecoder().decode(UmtomiSettings.self, from: data) {
            self.settings = UmtomiTab.sanitized(settings.tabOrder) == settings.tabOrder
                ? settings
                : {
                    var sanitizedSettings = settings
                    sanitizedSettings.tabOrder = UmtomiTab.sanitized(settings.tabOrder)
                    return sanitizedSettings
                }()
        } else {
            self.settings = UmtomiSettings()
        }

        if let data = Self.loadData(defaults: defaults, key: StorageKey.favoriteLibrary),
           let library = try? JSONDecoder().decode(FavoriteLibrary.self, from: data) {
            self.favoriteFolders = library.sanitized(validGalleryIDs: Set(self.favorites)).folders
        } else {
            self.favoriteFolders = []
        }

    }

    var totalResults: Int {
        allSearchResultIDs.count
    }

    var totalPageCount: Int {
        guard settings.pageSize > 0 else {
            return 1
        }

        return max(Int(ceil(Double(max(allSearchResultIDs.count, 1)) / Double(settings.pageSize))), 1)
    }

    var currentPageDisplay: Int {
        min(currentPageIndex + 1, totalPageCount)
    }

    var canGoToPreviousPage: Bool {
        currentPageIndex > 0
    }

    var canGoToNextPage: Bool {
        currentPageIndex + 1 < totalPageCount
    }

    var activeDownloads: [ActiveDownload] {
        downloadProgressByGallery
            .map { ActiveDownload(galleryID: $0.key, progress: $0.value) }
            .sorted { $0.galleryID < $1.galleryID }
    }

    var hasReaderTabs: Bool {
        !readerTabs.isEmpty
    }

    var alwaysIncludedTagsDisplay: String {
        Self.sanitizedTagTerms(from: settings.alwaysIncludedTags)
    }

    var orderedTabs: [UmtomiTab] {
        UmtomiTab.sanitized(settings.tabOrder)
    }

    var alwaysExcludedTagsDisplay: String {
        Self.sanitizedTagTerms(from: settings.alwaysExcludedTags)
    }

    var hasRecommendationSeed: Bool {
        !history.isEmpty || !favorites.isEmpty
    }

    var selectedReaderTab: ReaderTabSession? {
        guard let selectedReaderTabID else {
            return readerTabs.first
        }

        return readerTabs.first { $0.id == selectedReaderTabID } ?? readerTabs.first
    }

    var appLocale: Locale {
        Locale(identifier: settings.appLanguage.localeIdentifier)
    }

    func localized(_ key: String) -> String {
        localizationBundle.localizedString(forKey: key, value: key, table: nil)
    }

    func localized(_ key: String, _ arguments: CVarArg...) -> String {
        String(format: localized(key), locale: appLocale, arguments: arguments)
    }

    func bootstrap() async {
        guard !didBootstrap else {
            return
        }

        didBootstrap = true
        isBootstrapping = true
        defer { isBootstrapping = false }
        await refreshDownloads()
        await refreshSavedCollections()
        await search()
    }

    func search() async {
        isSearching = true
        errorMessage = nil
        currentPageIndex = 0
        visibleSummaries = []

        let userQuery = searchQuery.trimmingCharacters(in: .whitespacesAndNewlines)
        let effectiveQuery = effectiveQuery(for: userQuery)

        do {
            allSearchResultIDs = try await service.search(query: effectiveQuery, sortMode: sortMode)

            if !userQuery.isEmpty {
                addRecentSearch(userQuery)
            }

            await loadCurrentPage()
        } catch {
            visibleSummaries = []
            allSearchResultIDs = []
            errorMessage = error.localizedDescription
        }

        hasCompletedInitialSearch = true
        isSearching = false
    }

    func updateSortMode(_ sortMode: SortMode) async {
        self.sortMode = sortMode
        await search()
    }

    func goToNextPage() async {
        guard canGoToNextPage else {
            return
        }

        currentPageIndex += 1
        await loadCurrentPage()
    }

    func goToPreviousPage() async {
        guard canGoToPreviousPage else {
            return
        }

        currentPageIndex -= 1
        await loadCurrentPage()
    }

    func goToPage(_ page: Int) async {
        let normalizedPage = min(max(page, 1), totalPageCount)
        let targetIndex = normalizedPage - 1

        guard targetIndex != currentPageIndex else {
            return
        }

        currentPageIndex = targetIndex
        await loadCurrentPage()
    }

    func applySuggestion(_ suggestion: SearchSuggestion) {
        searchQuery = suggestion.query
    }

    func loadSuggestions(for query: String) async {
        let trimmed = query.trimmingCharacters(in: .whitespacesAndNewlines)

        if trimmed.isEmpty || query.last?.isWhitespace == true {
            suggestions = []
            return
        }

        do {
            suggestions = try await service.suggestions(for: trimmed)
        } catch {
            suggestions = []
        }
    }

    func openGallery(id: Int, presentation: GalleryRoute.Presentation = .detail) {
        if presentation == .reader {
            openReaderTab(galleryID: id, activate: true)
            return
        }

        presentedGallery = GalleryRoute(galleryID: id, presentation: presentation)
    }

    func openReaderTab(galleryID: Int, initialInfo: GalleryInfo? = nil, activate: Bool) {
        if let existing = readerTabs.first(where: { $0.galleryID == galleryID }) {
            if activate || selectedReaderTabID == nil {
                selectedReaderTabID = existing.id
            }

            if activate {
                presentReaderWorkspace()
            }
            return
        }

        let session = ReaderTabSession(galleryID: galleryID, seedInfo: initialInfo)
        readerTabs.append(session)

        if selectedReaderTabID == nil || activate {
            selectedReaderTabID = session.id
        }

        if activate {
            presentReaderWorkspace()
        }
    }

    func dismissReaderWorkspace() {
        presentedGallery = nil
    }

    func showReaderWorkspace() {
        presentReaderWorkspace()
    }

    func handlePresentedRouteDismissal() {
        if presentedGallery != nil {
            presentedGallery = nil
        }
    }

    func openRandomGallery() {
        guard let randomID = allSearchResultIDs.randomElement() else {
            return
        }

        openGallery(id: randomID)
    }

    func handleIncomingURL(_ url: URL) {
        if let route = DeepLinkParser.galleryRoute(from: url) {
            openGallery(id: route.galleryID, presentation: route.presentation)
        } else {
            errorMessage = localized("Unsupported link: %@", url.absoluteString)
        }
    }

    func isFavorite(_ galleryID: Int) -> Bool {
        favorites.contains(galleryID)
    }

    func isFavoriteArtist(_ artistName: String) -> Bool {
        let normalized = Self.normalizeMatchValue(artistName)
        return favoriteArtists.contains { $0.id == normalized }
    }

    func isDownloaded(_ galleryID: Int) async -> Bool {
        await downloadLibrary.isDownloaded(galleryID: galleryID)
    }

    func isDownloading(_ galleryID: Int) -> Bool {
        downloadProgressByGallery[galleryID] != nil
    }

    func downloadProgress(for galleryID: Int) -> Double? {
        downloadProgressByGallery[galleryID]
    }

    func toggleFavorite(_ galleryID: Int) async {
        if favorites.contains(galleryID) {
            favorites.removeAll { $0 == galleryID }
            sanitizeFavoriteFolders()
        } else {
            favorites.removeAll { $0 == galleryID }
            favorites.insert(galleryID, at: 0)
        }

        persistFavorites()
        await refreshFavorites()
        algorithmRefreshNeeded = true
    }

    func toggleFavoriteArtist(_ artistName: String) {
        let trimmedArtistName = artistName.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmedArtistName.isEmpty else {
            return
        }
        let candidate = FavoriteArtist(name: trimmedArtistName).sanitized()

        if let index = favoriteArtists.firstIndex(where: { $0.id == candidate.id }) {
            favoriteArtists.remove(at: index)
        } else {
            favoriteArtists.insert(candidate, at: 0)
        }

        favoriteArtists = FavoriteArtist.uniqueArtists(from: favoriteArtists)
        persistFavoriteArtists()
    }

    func recordHistory(_ galleryID: Int) async {
        history.removeAll { $0 == galleryID }
        history.insert(galleryID, at: 0)

        persistHistory()
        await refreshHistory()
        algorithmRefreshNeeded = true
    }

    func searchForTag(_ query: String) async {
        selectedTab = .search
        searchQuery = query
        await search()
    }

    func toggleDownload(_ galleryID: Int, info: GalleryInfo? = nil) async {
        guard downloadTasks[galleryID] == nil, !clearingDownloads else { return }
        let task = Task { await performDownloadAction(galleryID, info: info) }
        downloadTasks[galleryID] = task
        await task.value
        downloadTasks[galleryID] = nil
    }

    func cancelDownload(_ galleryID: Int) {
        guard downloadProgressByGallery[galleryID] != nil else { return }
        downloadTasks[galleryID]?.cancel()
    }

    private func performDownloadAction(_ galleryID: Int, info: GalleryInfo?) async {
        if await downloadLibrary.isDownloaded(galleryID: galleryID) {
            do {
                try await downloadLibrary.removeDownload(galleryID: galleryID)
                await refreshDownloads()
            } catch {
                errorMessage = error.localizedDescription
            }
            return
        }

        guard !Task.isCancelled else { return }
        downloadProgressByGallery[galleryID] = 0

        do {
            let resolvedInfo: GalleryInfo

            if let info {
                resolvedInfo = info
            } else if let localInfo = await downloadLibrary.localGalleryInfo(for: galleryID) {
                resolvedInfo = localInfo
            } else {
                resolvedInfo = try await service.galleryInfo(id: galleryID)
            }

            try await downloadLibrary.downloadGallery(info: resolvedInfo) { progress in
                await MainActor.run {
                    self.downloadProgressByGallery[galleryID] = progress
                }
            }

            downloadProgressByGallery[galleryID] = nil
            await refreshDownloads()
        } catch {
            downloadProgressByGallery[galleryID] = nil
            if !Task.isCancelled { errorMessage = error.localizedDescription }
        }
    }

    func clearHistory() async {
        history = []
        historySummaries = []
        algorithmSummaries = []
        lastReadPageByGallery = [:]
        algorithmRefreshNeeded = true
        UserDefaults.standard.set(history, forKey: StorageKey.history)
        persistLastReadPages()
    }

    func clearRecentSearches() {
        recentSearches = []
        UserDefaults.standard.set(recentSearches, forKey: StorageKey.recentSearches)
    }

    func clearDownloads() async {
        guard !clearingDownloads else { return }
        clearingDownloads = true
        defer { clearingDownloads = false }
        let tasks = Array(downloadTasks.values)
        for task in tasks { task.cancel() }
        for task in tasks { await task.value }
        do {
            try await downloadLibrary.clearDownloads()
            downloadProgressByGallery = [:]
            await refreshDownloads()
        } catch {
            errorMessage = error.localizedDescription
        }
    }

    func updateDefaultQuery(_ query: String) {
        settings.defaultQuery = query
        searchSettingsChanged = true
        persistSettings()
    }

    func updateAlwaysIncludedTags(_ tags: String) {
        settings.alwaysIncludedTags = tags
        searchSettingsChanged = true
        algorithmRefreshNeeded = true
        persistSettings()
    }

    func updateAppLanguage(_ language: AppLanguage) {
        settings.appLanguage = language
        persistSettings()
    }

    func updateDarkModeEnabled(_ value: Bool) {
        settings.darkModeEnabled = value
        persistSettings()
    }

    func updateAlwaysExcludedTags(_ tags: String) {
        settings.alwaysExcludedTags = tags
        searchSettingsChanged = true
        algorithmRefreshNeeded = true
        persistSettings()
    }

    func updateAlgorithmResultCount(_ count: Int) {
        settings.algorithmResultCount = min(max(count, 5), 100)
        algorithmRefreshNeeded = true
        persistSettings()
    }

    func updatePageSize(_ pageSize: Int) async {
        settings.pageSize = min(max(pageSize, 10), 100)
        persistSettings()
        currentPageIndex = 0
        await loadCurrentPage()
    }

    func updateRightToLeft(_ value: Bool) {
        settings.rightToLeft = value
        persistSettings()
    }

    func updateReaderMode(_ value: ReaderMode) {
        settings.readerMode = value
        persistSettings()
    }

    func updateResumePrefetchMode(_ value: ReaderResumePrefetchMode) {
        settings.resumePrefetchMode = value
        persistSettings()
    }

    func updateHideAppInSwitcher(_ value: Bool) {
        settings.hideAppInSwitcher = value
        persistSettings()
    }

    func restoreFavorites(_ galleryIDs: [Int]) async {
        await restoreFavorites(galleryIDs, library: nil, artists: nil)
    }

    func restoreFavorites(_ galleryIDs: [Int], library: FavoriteLibrary?) async {
        await restoreFavorites(galleryIDs, library: library, artists: nil)
    }

    func restoreFavorites(_ galleryIDs: [Int], library: FavoriteLibrary?, artists: [FavoriteArtist]?) async {
        favorites = Self.uniqueIDs(galleryIDs)
        favoriteFolders = (library ?? FavoriteLibrary())
            .sanitized(validGalleryIDs: Set(favorites))
            .folders
        favoriteArtists = FavoriteArtist.uniqueArtists(from: artists ?? favoriteArtists)
        persistFavorites()
        persistFavoriteLibrary()
        persistFavoriteArtists()
        await refreshFavorites()
        algorithmRefreshNeeded = true
    }

    func searchPreviewSummaries(for userQuery: String, limit: Int = 40) async throws -> [GallerySummary] {
        let ids = try await service.search(query: effectiveQuery(for: userQuery), sortMode: sortMode)
        return await service.gallerySummaries(ids: Array(ids.prefix(max(limit, 1))))
    }

    func moveTab(fromOffsets source: IndexSet, toOffset destination: Int) {
        var order = orderedTabs
        order.move(fromOffsets: source, toOffset: destination)
        settings.tabOrder = UmtomiTab.sanitized(order)
        persistSettings()
    }

    func updateShowsMoreTab(_ enabled: Bool) {
        settings.showsMoreTab = enabled
        persistSettings()
    }

    func placeTab(_ tab: UmtomiTab, inMore: Bool) {
        if inMore {
            guard settings.barTabs.count > 1, !settings.moreTabs.contains(tab) else { return }
            settings.moreTabs.append(tab)
        } else {
            settings.moreTabs.removeAll { $0 == tab }
        }
        persistSettings()
    }

    func moveTabGroup(from offsets: IndexSet, to destination: Int, inMore: Bool) {
        var group = inMore ? settings.overflowTabs : settings.barTabs
        group.move(fromOffsets: offsets, toOffset: destination)
        let members = Set(group)
        var reordered = group.makeIterator()
        settings.tabOrder = orderedTabs.map { members.contains($0) ? (reordered.next() ?? $0) : $0 }
        persistSettings()
    }

    func favoriteFolder(id: UUID) -> FavoriteFolder? {
        favoriteFolders.first { $0.id == id }
    }

    func readerTab(id: UUID) -> ReaderTabSession? {
        readerTabs.first { $0.id == id }
    }

    func selectReaderTab(_ tabID: UUID) {
        guard readerTabs.contains(where: { $0.id == tabID }) else {
            return
        }

        selectedReaderTabID = tabID
    }

    func closeReaderTab(_ tabID: UUID) {
        guard let closingIndex = readerTabs.firstIndex(where: { $0.id == tabID }) else {
            return
        }

        let closingSession = readerTabs[closingIndex]
        service.clearCachedImages(for: closingSession.pages.map(\.url))

        readerTabs.remove(at: closingIndex)
        loadingReaderTabIDs.remove(tabID)

        if readerTabs.isEmpty {
            selectedReaderTabID = nil
            presentedGallery = nil
            return
        }

        if selectedReaderTabID == tabID {
            let fallbackIndex = min(closingIndex, readerTabs.count - 1)
            selectedReaderTabID = readerTabs[fallbackIndex].id
        }
    }

    func favoriteFolders(in parentID: UUID?) -> [FavoriteFolder] {
        favoriteFolders
            .filter { $0.parentID == parentID }
            .sorted { lhs, rhs in
                let nameComparison = lhs.name.localizedCaseInsensitiveCompare(rhs.name)
                if nameComparison == .orderedSame {
                    return lhs.createdAt < rhs.createdAt
                }
                return nameComparison == .orderedAscending
            }
    }

    func favoriteGalleryCount(in folderID: UUID) -> Int {
        favoriteDisplayedGalleryIDs(in: folderID).count
    }

    func favoriteFolderAutoRulesDisplay(_ folder: FavoriteFolder) -> String {
        Self.sanitizedTagTerms(from: folder.autoRuleQuery)
    }

    func favoriteGallerySummaries(in folderID: UUID?) -> [GallerySummary] {
        let ids = favoriteDisplayedGalleryIDs(in: folderID)
        return ids.compactMap { favoriteSummariesByID[$0] }
    }

    func isFavoriteManuallyContained(_ galleryID: Int, in folderID: UUID) -> Bool {
        favoriteFolder(id: folderID)?.manualGalleryIDs.contains(galleryID) == true
    }

    func createFavoriteFolder(parentID: UUID?) -> FavoriteFolder {
        FavoriteFolder(parentID: parentID, name: "New Folder")
    }

    func saveFavoriteFolder(_ folder: FavoriteFolder) {
        let sanitized = folder.sanitized(validGalleryIDs: Set(favorites))

        if let index = favoriteFolders.firstIndex(where: { $0.id == sanitized.id }) {
            favoriteFolders[index] = sanitized
        } else {
            favoriteFolders.append(sanitized)
        }

        invalidateFavoriteAutoMatchCache(for: sanitized.id)
        persistFavoriteLibrary()
    }

    func removeGallery(_ galleryID: Int, from folderID: UUID) {
        guard let index = favoriteFolders.firstIndex(where: { $0.id == folderID }) else {
            return
        }

        favoriteFolders[index].manualGalleryIDs.removeAll { $0 == galleryID }
        persistFavoriteLibrary()
    }

    func deleteFavoriteFolder(_ folderID: UUID) {
        let descendantIDs = descendantFavoriteFolderIDs(of: folderID)
        let removalSet = descendantIDs.union([folderID])
        favoriteFolders.removeAll { removalSet.contains($0.id) }
        invalidateFavoriteAutoMatchCache(for: removalSet)
        persistFavoriteLibrary()
    }

    func prepareFavoriteAutoMatchesIfNeeded(for folderID: UUID) async {
        guard let folder = favoriteFolder(id: folderID) else {
            return
        }

        let autoRuleTerms = Self.tagTerms(from: folder.autoRuleQuery)
        guard !autoRuleTerms.isEmpty else {
            favoriteAutoMatchedIDsByFolder[folderID] = []
            favoriteAutoMatchCacheByFolder[folderID] = FavoriteAutoMatchCacheEntry(
                query: "",
                favorites: favorites,
                matchedIDs: []
            )
            return
        }

        if let cached = favoriteAutoMatchCacheByFolder[folderID],
           cached.query == folder.autoRuleQuery,
           cached.favorites == favorites {
            if favoriteAutoMatchedIDsByFolder[folderID] != cached.matchedIDs {
                favoriteAutoMatchedIDsByFolder[folderID] = cached.matchedIDs
            }
            return
        }

        let favoriteSet = Set(favorites)
        var matchedFavoriteIDs: Set<Int> = []

        for term in autoRuleTerms {
            do {
                let ids = try await service.search(query: term, sortMode: .dateAdded)
                for id in ids where favoriteSet.contains(id) {
                    matchedFavoriteIDs.insert(id)
                }
            } catch {
                continue
            }
        }

        let orderedMatches = favorites.filter { matchedFavoriteIDs.contains($0) }
        favoriteAutoMatchedIDsByFolder[folderID] = orderedMatches
        favoriteAutoMatchCacheByFolder[folderID] = FavoriteAutoMatchCacheEntry(
            query: folder.autoRuleQuery,
            favorites: favorites,
            matchedIDs: orderedMatches
        )
    }

    func lastReadPage(for galleryID: Int) -> Int? {
        guard let page = lastReadPageByGallery[galleryID], page > 0 else {
            return nil
        }

        return page
    }

    func updateLastReadPage(_ page: Int, for galleryID: Int) {
        guard galleryID > 0 else {
            return
        }

        lastReadPageByGallery[galleryID] = max(page, 0)
        persistLastReadPages()
    }

    func clearLastReadPage(for galleryID: Int) {
        lastReadPageByGallery[galleryID] = 0
        persistLastReadPages()
    }

    func ensureReaderTabLoaded(_ tabID: UUID) async {
        guard loadingReaderTabIDs.insert(tabID).inserted else {
            return
        }

        defer { loadingReaderTabIDs.remove(tabID) }

        guard let initialSession = readerTab(id: tabID) else {
            return
        }

        if !initialSession.pages.isEmpty || initialSession.errorMessage != nil {
            return
        }

        updateReaderTab(tabID) { session in
            session.isLoading = true
            session.errorMessage = nil
        }

        do {
            let downloadedPages = await downloadLibrary.localReaderPages(for: initialSession.galleryID)
            let resolvedInfo: GalleryInfo
            let resolvedPages: [ReaderPage]

            if !downloadedPages.isEmpty {
                if let seedInfo = initialSession.seedInfo {
                    resolvedInfo = seedInfo
                } else if let localInfo = await downloadLibrary.localGalleryInfo(for: initialSession.galleryID) {
                    resolvedInfo = localInfo
                } else {
                    throw HitomiServiceError.invalidGalleryInfo
                }
                resolvedPages = downloadedPages
            } else {
                let galleryInfo: GalleryInfo

                if let seedInfo = initialSession.seedInfo {
                    galleryInfo = seedInfo
                } else {
                    galleryInfo = try await service.galleryInfo(id: initialSession.galleryID)
                }

                resolvedInfo = galleryInfo
                resolvedPages = try await service.readerPages(for: galleryInfo)
            }

            let savedPage = lastReadPage(for: initialSession.galleryID).flatMap { savedPage in
                resolvedPages.indices.contains(savedPage) ? savedPage : nil
            }
            let prefetchAnchor = settings.resumePrefetchMode == .aroundResumePage ? (savedPage ?? 0) : 0

            updateReaderTab(tabID) { session in
                session.info = resolvedInfo
                session.title = resolvedInfo.title
                session.pages = resolvedPages
                session.selectedPage = 0
                session.scrollPosition = 0
                session.isLoading = false
                session.errorMessage = nil
                session.resumePage = savedPage
                session.isResumePromptPresented = savedPage != nil
                session.prefetchAnchorPage = prefetchAnchor
            }

            await recordHistory(initialSession.galleryID)
        } catch {
            updateReaderTab(tabID) { session in
                session.isLoading = false
                session.errorMessage = error.localizedDescription
            }
        }
    }

    func restoreReaderTabToResumePage(_ tabID: UUID) {
        guard let session = readerTab(id: tabID),
              let resumePage = session.resumePage,
              session.pages.indices.contains(resumePage) else {
            return
        }

        updateReaderTab(tabID) { mutableSession in
            mutableSession.selectedPage = resumePage
            mutableSession.scrollPosition = resumePage
            mutableSession.isResumePromptPresented = false
            mutableSession.resumePage = nil
            mutableSession.prefetchAnchorPage = settings.resumePrefetchMode == .aroundResumePage ? resumePage : 0
        }
        updateLastReadPage(resumePage, for: session.galleryID)
    }

    func restartReaderTabFromBeginning(_ tabID: UUID) {
        guard let session = readerTab(id: tabID) else {
            return
        }

        clearLastReadPage(for: session.galleryID)
        updateReaderTab(tabID) { mutableSession in
            mutableSession.selectedPage = 0
            mutableSession.scrollPosition = 0
            mutableSession.isResumePromptPresented = false
            mutableSession.resumePage = nil
            mutableSession.prefetchAnchorPage = 0
        }
    }

    func updateReaderTabPage(_ page: Int, for tabID: UUID) {
        guard let session = readerTab(id: tabID), !session.pages.isEmpty else {
            return
        }

        let boundedPage = min(max(page, 0), session.pages.count - 1)
        updateReaderTab(tabID) { mutableSession in
            mutableSession.selectedPage = boundedPage
        }
        updateLastReadPage(boundedPage, for: session.galleryID)
    }

    func updateReaderTabScrollPosition(_ position: Int?, for tabID: UUID) {
        updateReaderTab(tabID) { session in
            session.scrollPosition = position
        }
    }

    func readerPrefetchPageOrder(for tabID: UUID) -> [Int] {
        guard let session = readerTab(id: tabID), !session.pages.isEmpty else {
            return []
        }

        switch settings.resumePrefetchMode {
        case .fromBeginning:
            return Array(session.pages.indices)
        case .aroundResumePage:
            let anchor = min(max(session.prefetchAnchorPage, 0), session.pages.count - 1)
            return Self.readerLoadOrder(pageCount: session.pages.count, anchor: anchor)
        }
    }

    func readerPrefetchURLs(for tabID: UUID) -> [URL] {
        guard let session = readerTab(id: tabID), !session.pages.isEmpty else {
            return []
        }

        return readerPrefetchPageOrder(for: tabID).map { session.pages[$0].url }
    }

    func artistSearchQuery(for artistName: String) -> String {
        "artist:\(Self.searchQueryComponent(from: artistName))"
    }

    func consumeSearchSettingsChange() -> Bool {
        let changed = searchSettingsChanged
        searchSettingsChanged = false
        return changed
    }

    func consumeAlgorithmRefreshRequest() -> Bool {
        let changed = algorithmRefreshNeeded
        algorithmRefreshNeeded = false
        return changed
    }

    private func loadCurrentPage() async {
        isLoadingPage = true
        defer { isLoadingPage = false }

        let ids = currentPageIDs()
        visibleSummaries = await service.gallerySummaries(ids: ids)
    }

    func driveUserSnapshot() throws -> SyncObject {
        var result: SyncObject = [:]
        for id in favorites where id > 0 { result["favorite/\(id)"] = true }
        for (index, id) in history.enumerated() where id > 0 { result["history/\(id)"] = index }
        let encoder = JSONEncoder()
        encoder.dateEncodingStrategy = .iso8601
        for folder in favoriteFolders {
            let key = "folder/\(folder.id.uuidString.lowercased())"
            result[key] = try SyncJSON.read(encoder.encode(folder))
        }
        for artist in favoriteArtists {
            result["artist/\(SyncJSON.digest(Data(artist.name.utf8)))"] = try SyncJSON.read(encoder.encode(artist))
        }
        return result
    }

    func applyDriveUserSnapshot(_ snapshot: SyncObject, matching before: SyncObject) throws {
        guard try SyncJSON.same(driveUserSnapshot(), before) else { throw DriveSyncFailure.changed }
        let decoder = JSONDecoder()
        decoder.dateDecodingStrategy = .custom { decoder in
            let text = try decoder.singleValueContainer().decode(String.self)
            let parser = ISO8601DateFormatter()
            parser.formatOptions = [.withInternetDateTime, .withFractionalSeconds]
            if let date = parser.date(from: text) { return date }
            parser.formatOptions = [.withInternetDateTime]
            guard let date = parser.date(from: text) else { throw DriveSyncFailure.invalid }
            return date
        }
        var incomingFavorites: [Int] = []
        var incomingHistory: [(id: Int, position: Int)] = []
        var incomingFolders: [FavoriteFolder] = []
        var incomingArtists: [FavoriteArtist] = []
        for (key, value) in snapshot.sorted(by: { $0.key < $1.key }) {
            let components = key.split(separator: "/", maxSplits: 1)
            guard components.count == 2 else { throw DriveSyncFailure.invalid }
            switch components[0] {
            case "favorite":
                guard let id = Int(components[1]), id > 0, value as? Bool == true else { throw DriveSyncFailure.invalid }
                incomingFavorites.append(id)
            case "history":
                guard let id = Int(components[1]), id > 0, let position = value as? Int else { throw DriveSyncFailure.invalid }
                incomingHistory.append((id, position))
            case "folder":
                incomingFolders.append(try decoder.decode(FavoriteFolder.self, from: SyncJSON.data(value)))
            case "artist":
                incomingArtists.append(try decoder.decode(FavoriteArtist.self, from: SyncJSON.data(value)))
            default: break
            }
        }
        incomingFolders = FavoriteLibrary(folders: incomingFolders).sanitized(validGalleryIDs: Set(incomingFavorites)).folders
        let parents = Dictionary(uniqueKeysWithValues: incomingFolders.map { ($0.id, $0.parentID) })
        for index in incomingFolders.indices {
            var seen: Set<UUID> = [incomingFolders[index].id]
            var parent = incomingFolders[index].parentID
            while let id = parent {
                if !seen.insert(id).inserted { incomingFolders[index].parentID = nil; break }
                parent = parents[id] ?? nil
            }
        }
        let ids = Set(incomingFavorites)
        let existing = favorites.filter(ids.contains)
        let existingSet = Set(existing)
        favorites = existing + incomingFavorites.filter { !existingSet.contains($0) }
        history = incomingHistory.sorted { $0.position == $1.position ? $0.id < $1.id : $0.position < $1.position }.map(\.id)
        favoriteFolders = incomingFolders
        favoriteArtists = FavoriteArtist.uniqueArtists(from: incomingArtists)
        persistFavorites()
        persistHistory()
        persistFavoriteLibrary()
        persistFavoriteArtists()
        algorithmRefreshNeeded = true
        Task { await refreshSavedCollections() }
    }

    private func refreshSavedCollections() async {
        await refreshFavorites()
        await refreshHistory()
    }

    private func refreshDownloads() async {
        downloadSummaries = await downloadLibrary.downloadedSummaries()
    }

    private func refreshFavorites() async {
        favoriteSummaries = await service.gallerySummaries(ids: favorites)
        favoriteSummariesByID = Dictionary(uniqueKeysWithValues: favoriteSummaries.map { ($0.id, $0) })
        invalidateFavoriteAutoMatchCache()
    }

    private func refreshHistory() async {
        historySummaries = await service.gallerySummaries(ids: history)
    }

    private func currentPageIDs() -> [Int] {
        guard !allSearchResultIDs.isEmpty else {
            return []
        }

        let lowerBound = currentPageIndex * settings.pageSize
        let upperBound = min(lowerBound + settings.pageSize, allSearchResultIDs.count)

        guard lowerBound < upperBound else {
            return []
        }

        return Array(allSearchResultIDs[lowerBound..<upperBound])
    }

    private func effectiveQuery(for userQuery: String) -> String {
        [
            normalizedIncludedTags(),
            settings.defaultQuery,
            userQuery,
            normalizedExcludedTags()
        ]
            .map { $0.trimmingCharacters(in: .whitespacesAndNewlines) }
            .filter { !$0.isEmpty }
            .joined(separator: " ")
    }

    private func normalizedIncludedTags() -> String {
        Self.tagTerms(from: settings.alwaysIncludedTags).joined(separator: " ")
    }

    private func normalizedExcludedTags() -> String {
        Self.tagTerms(from: settings.alwaysExcludedTags)
            .map { "-\($0)" }
            .joined(separator: " ")
    }

    private func addRecentSearch(_ query: String) {
        recentSearches.removeAll { $0.caseInsensitiveCompare(query) == .orderedSame }
        recentSearches.insert(query, at: 0)

        if recentSearches.count > 10 {
            recentSearches = Array(recentSearches.prefix(10))
        }

        UserDefaults.standard.set(recentSearches, forKey: StorageKey.recentSearches)
    }

    private func persistFavorites() {
        UserDefaults.standard.set(favorites, forKey: StorageKey.favorites)
    }

    private func persistFavoriteLibrary() {
        let library = FavoriteLibrary(folders: favoriteFolders).sanitized(validGalleryIDs: Set(favorites))
        favoriteFolders = library.folders

        guard let data = try? JSONEncoder().encode(library) else {
            return
        }

        UserDefaults.standard.set(data, forKey: StorageKey.favoriteLibrary)
    }

    private func persistFavoriteArtists() {
        let artists = FavoriteArtist.uniqueArtists(from: favoriteArtists)
        favoriteArtists = artists

        guard let data = try? JSONEncoder().encode(artists) else {
            return
        }

        UserDefaults.standard.set(data, forKey: StorageKey.favoriteArtists)
    }

    private func persistHistory() {
        UserDefaults.standard.set(history, forKey: StorageKey.history)
    }

    private func persistSettings() {
        guard let data = try? JSONEncoder().encode(settings) else {
            return
        }

        UserDefaults.standard.set(data, forKey: StorageKey.settings)
    }

    private func persistLastReadPages() {
        let encoded = Dictionary(uniqueKeysWithValues: lastReadPageByGallery.map { (String($0.key), $0.value) })
        UserDefaults.standard.set(encoded, forKey: StorageKey.lastReadPages)
    }

    private func sanitizeFavoriteFolders() {
        favoriteFolders = FavoriteLibrary(folders: favoriteFolders)
            .sanitized(validGalleryIDs: Set(favorites))
            .folders
        invalidateFavoriteAutoMatchCache()
        persistFavoriteLibrary()
    }

    private func favoriteDisplayedGalleryIDs(in folderID: UUID?) -> [Int] {
        guard let folderID, let folder = favoriteFolder(id: folderID) else {
            return favorites
        }

        let autoMatchedIDs = favoriteAutoMatchedIDsByFolder[folderID] ?? []
        return Self.uniqueIDs(folder.manualGalleryIDs + autoMatchedIDs)
    }

    private func invalidateFavoriteAutoMatchCache(for folderID: UUID? = nil) {
        if let folderID {
            favoriteAutoMatchCacheByFolder.removeValue(forKey: folderID)
            favoriteAutoMatchedIDsByFolder.removeValue(forKey: folderID)
        } else {
            favoriteAutoMatchCacheByFolder = [:]
            favoriteAutoMatchedIDsByFolder = [:]
        }
    }

    private func invalidateFavoriteAutoMatchCache(for folderIDs: Set<UUID>) {
        for folderID in folderIDs {
            favoriteAutoMatchCacheByFolder.removeValue(forKey: folderID)
            favoriteAutoMatchedIDsByFolder.removeValue(forKey: folderID)
        }
    }

    private func descendantFavoriteFolderIDs(of folderID: UUID) -> Set<UUID> {
        var descendants: Set<UUID> = []
        var queue: [UUID] = [folderID]

        while let current = queue.first {
            queue.removeFirst()

            let children = favoriteFolders
                .filter { $0.parentID == current }
                .map(\.id)

            for childID in children where descendants.insert(childID).inserted {
                queue.append(childID)
            }
        }

        return descendants
    }

    private func updateReaderTab(_ tabID: UUID, mutate: (inout ReaderTabSession) -> Void) {
        guard let index = readerTabs.firstIndex(where: { $0.id == tabID }) else {
            return
        }

        mutate(&readerTabs[index])
    }

    private func presentReaderWorkspace() {
        if presentedGallery?.presentation == .reader {
            return
        }

        guard let activeTab = selectedReaderTab ?? readerTabs.first else {
            return
        }

        presentedGallery = GalleryRoute(galleryID: activeTab.galleryID, presentation: .reader)
    }

    private func clearReaderTabs() {
        readerTabs = []
        selectedReaderTabID = nil
        loadingReaderTabIDs = []
    }

    private func matchesPersistentTagFilters(_ info: GalleryInfo) -> Bool {
        let includedTerms = Self.tagTerms(from: settings.alwaysIncludedTags)
        let excludedTerms = Self.tagTerms(from: settings.alwaysExcludedTags)

        if !includedTerms.allSatisfy({ matchesFilterTerm($0, in: info) }) {
            return false
        }

        if excludedTerms.contains(where: { matchesFilterTerm($0, in: info) }) {
            return false
        }

        return true
    }

    private func similarityScore(for info: GalleryInfo, profile: RecommendationProfile) -> Double {
        var score = 0.0

        if let topLanguage = profile.topLanguage, Self.normalizeMatchValue(info.displayLanguage) == topLanguage {
            score += 18
        }

        if let topType = profile.topType, Self.normalizeMatchValue(info.type) == topType {
            score += 12
        }

        score += Double(Set(info.primaryArtists.map(Self.normalizeMatchValue(_:))).intersection(profile.artists).count) * 26
        score += Double(Set(info.primaryGroups.map(Self.normalizeMatchValue(_:))).intersection(profile.groups).count) * 22
        score += Double(Set(info.primarySeries.map(Self.normalizeMatchValue(_:))).intersection(profile.series).count) * 28
        score += Double(Set(info.primaryCharacters.map(Self.normalizeMatchValue(_:))).intersection(profile.characters).count) * 14
        score += Double(Set((info.tags ?? []).map { Self.normalizeMatchValue($0.tag) }).intersection(profile.tags).count) * 8

        return score
    }

    private func matchesFilterTerm(_ term: String, in info: GalleryInfo) -> Bool {
        let normalizedTerm = Self.normalizeMatchValue(term)
        guard !normalizedTerm.isEmpty else {
            return true
        }

        let parts = normalizedTerm.split(separator: ":", maxSplits: 1).map(String.init)
        let namespace = parts.count == 2 ? parts[0] : "global"
        let value = parts.count == 2 ? parts[1] : normalizedTerm

        let tagNames = Set((info.tags ?? []).map { Self.normalizeMatchValue($0.tag) })
        let femaleTags = Set<String>((info.tags ?? []).compactMap { tag -> String? in
            guard tag.female?.isEmpty == false else { return nil }
            return Self.normalizeMatchValue(tag.tag)
        })
        let maleTags = Set<String>((info.tags ?? []).compactMap { tag -> String? in
            guard tag.male?.isEmpty == false else { return nil }
            return Self.normalizeMatchValue(tag.tag)
        })
        let artists = Set(info.primaryArtists.map(Self.normalizeMatchValue(_:)))
        let groups = Set(info.primaryGroups.map(Self.normalizeMatchValue(_:)))
        let series = Set(info.primarySeries.map(Self.normalizeMatchValue(_:)))
        let characters = Set(info.primaryCharacters.map(Self.normalizeMatchValue(_:)))
        let language = Self.normalizeMatchValue(info.displayLanguage)
        let type = Self.normalizeMatchValue(info.type)
        let title = Self.normalizeMatchValue(info.title)
        let japaneseTitle = Self.normalizeMatchValue(info.japaneseTitle ?? "")

        switch namespace {
        case "language":
            return language == value || language.contains(value)
        case "female":
            return femaleTags.contains(value)
        case "male":
            return maleTags.contains(value)
        case "tag":
            return tagNames.contains(value)
        case "artist":
            return artists.contains(value)
        case "group":
            return groups.contains(value)
        case "parody", "series":
            return series.contains(value)
        case "character":
            return characters.contains(value)
        case "type":
            return type == value || type.contains(value)
        default:
            return title.contains(value)
                || japaneseTitle.contains(value)
                || language.contains(value)
                || type.contains(value)
                || tagNames.contains(value)
                || femaleTags.contains(value)
                || maleTags.contains(value)
                || artists.contains(value)
                || groups.contains(value)
                || series.contains(value)
                || characters.contains(value)
        }
    }

    func refreshAlgorithmRecommendations() async {
        algorithmRefreshNeeded = false
        isLoadingAlgorithm = true
        defer { isLoadingAlgorithm = false }
        let desiredResultCount = max(settings.algorithmResultCount, 5)

        let historySeedIDs = Array(history.prefix(18))
        let favoriteSeedIDs = Array(favorites.prefix(18))
        let seedIDs = Self.uniqueIDs(historySeedIDs + favoriteSeedIDs)

        guard !seedIDs.isEmpty else {
            algorithmSummaries = []
            return
        }

        let seedInfos = await service.galleryInfos(ids: seedIDs)
        guard !seedInfos.isEmpty else {
            algorithmSummaries = []
            return
        }

        let seedIDSet = Set(seedIDs)
        let seedInfosByID = Dictionary(uniqueKeysWithValues: seedInfos.map { ($0.numericID, $0) })
        let profile = RecommendationProfile(seedInfos: seedInfos)
        var candidateScores: [Int: Double] = [:]

        for (index, galleryID) in historySeedIDs.enumerated() {
            guard let info = seedInfosByID[galleryID] else {
                continue
            }

            let weight = Double(max(1, 18 - index)) * 1.8
            for relatedID in Array((info.related ?? []).prefix(20)) where !seedIDSet.contains(relatedID) {
                candidateScores[relatedID, default: 0] += 120 + weight
            }
        }

        for (index, galleryID) in favoriteSeedIDs.enumerated() {
            guard let info = seedInfosByID[galleryID] else {
                continue
            }

            let weight = Double(max(1, 18 - index)) * 1.4
            for relatedID in Array((info.related ?? []).prefix(20)) where !seedIDSet.contains(relatedID) {
                candidateScores[relatedID, default: 0] += 96 + weight
            }
        }

        await expandRecommendationCandidates(
            for: profile,
            excluding: seedIDSet,
            into: &candidateScores
        )

        let candidateIDs = candidateScores
            .sorted { lhs, rhs in
                if lhs.value == rhs.value {
                    return lhs.key > rhs.key
                }
                return lhs.value > rhs.value
            }
            .map(\.key)

        guard !candidateIDs.isEmpty else {
            algorithmSummaries = []
            return
        }

        let candidateFetchLimit = min(max(desiredResultCount * 6, 120), 320)
        let candidateInfos = await service.galleryInfos(ids: Array(candidateIDs.prefix(candidateFetchLimit)))

        let rankedIDs = candidateInfos.compactMap { info -> (Int, Double)? in
            let id = info.numericID

            guard !seedIDSet.contains(id) else {
                return nil
            }

            guard matchesPersistentTagFilters(info) else {
                return nil
            }

            return (id, (candidateScores[id] ?? 0) + similarityScore(for: info, profile: profile))
        }
        .sorted { lhs, rhs in
            if lhs.1 == rhs.1 {
                return lhs.0 > rhs.0
            }
            return lhs.1 > rhs.1
        }
        .map(\.0)

        algorithmSummaries = await service.gallerySummaries(ids: Array(rankedIDs.prefix(desiredResultCount)))
    }

    private static func sanitizedTagTerms(from raw: String) -> String {
        tagTerms(from: raw).joined(separator: ", ")
    }

    private static func tagTerms(from raw: String) -> [String] {
        raw
            .replacingOccurrences(of: "，", with: ",")
            .split(separator: ",")
            .map { term in
                term
                    .trimmingCharacters(in: .whitespacesAndNewlines)
                    .replacingOccurrences(of: #"\s+"#, with: "_", options: .regularExpression)
            }
            .filter { !$0.isEmpty }
    }

    private static func uniqueIDs(_ ids: [Int]) -> [Int] {
        var seen: Set<Int> = []
        var ordered: [Int] = []

        for id in ids where seen.insert(id).inserted {
            ordered.append(id)
        }

        return ordered
    }

    private func expandRecommendationCandidates(
        for profile: RecommendationProfile,
        excluding seedIDSet: Set<Int>,
        into candidateScores: inout [Int: Double]
    ) async {
        let searchSeeds = recommendationSearchSeeds(for: profile)

        guard !searchSeeds.isEmpty else {
            return
        }

        let weightedCandidates = await withTaskGroup(of: [(Int, Double)].self) { group in
            for seed in searchSeeds {
                group.addTask { [service] in
                    do {
                        let ids = try await service.search(query: seed.query, sortMode: .dateAdded)
                        return Array(ids.prefix(seed.resultLimit)).enumerated().map { index, id in
                            let rankScore = Double(max(seed.resultLimit - index, 1)) * seed.rankWeight
                            return (id, seed.baseScore + rankScore)
                        }
                    } catch {
                        return []
                    }
                }
            }

            var combined: [(Int, Double)] = []

            for await batch in group {
                combined.append(contentsOf: batch)
            }

            return combined
        }

        for (candidateID, score) in weightedCandidates where !seedIDSet.contains(candidateID) {
            candidateScores[candidateID, default: 0] += score
        }
    }

    private func recommendationSearchSeeds(for profile: RecommendationProfile) -> [RecommendationSearchSeed] {
        var seeds: [RecommendationSearchSeed] = []
        var seenQueries: Set<String> = []

        func appendSeeds(
            prefix: String,
            values: [String],
            baseScore: Double,
            scoreStep: Double,
            rankWeight: Double,
            resultLimit: Int
        ) {
            for (index, value) in values.enumerated() {
                let query = "\(prefix):\(Self.searchQueryComponent(from: value))"
                guard seenQueries.insert(query).inserted else {
                    continue
                }

                seeds.append(
                    RecommendationSearchSeed(
                        query: query,
                        baseScore: max(baseScore - (Double(index) * scoreStep), 6),
                        rankWeight: rankWeight,
                        resultLimit: resultLimit
                    )
                )
            }
        }

        appendSeeds(
            prefix: "series",
            values: Array(profile.topSeries.prefix(3)),
            baseScore: 72,
            scoreStep: 8,
            rankWeight: 0.90,
            resultLimit: 80
        )
        appendSeeds(
            prefix: "artist",
            values: Array(profile.topArtists.prefix(4)),
            baseScore: 66,
            scoreStep: 6,
            rankWeight: 0.82,
            resultLimit: 90
        )
        appendSeeds(
            prefix: "group",
            values: Array(profile.topGroups.prefix(3)),
            baseScore: 54,
            scoreStep: 6,
            rankWeight: 0.64,
            resultLimit: 70
        )
        appendSeeds(
            prefix: "character",
            values: Array(profile.topCharacters.prefix(3)),
            baseScore: 40,
            scoreStep: 5,
            rankWeight: 0.48,
            resultLimit: 60
        )
        appendSeeds(
            prefix: "tag",
            values: Array(profile.topTags.prefix(8)),
            baseScore: 24,
            scoreStep: 2,
            rankWeight: 0.28,
            resultLimit: 55
        )

        if let topLanguage = profile.topLanguage {
            appendSeeds(
                prefix: "language",
                values: [topLanguage],
                baseScore: 12,
                scoreStep: 0,
                rankWeight: 0.14,
                resultLimit: 120
            )
        }

        return seeds
    }

    private static func loadIntArray(defaults: UserDefaults, key: String) -> [Int] {
        defaults.array(forKey: key) as? [Int] ?? []
    }

    private static func loadStringArray(defaults: UserDefaults, key: String) -> [String] {
        defaults.stringArray(forKey: key) ?? []
    }

    private static func loadData(defaults: UserDefaults, key: String) -> Data? {
        defaults.data(forKey: key)
    }

    private static func loadLastReadPages(defaults: UserDefaults, key: String) -> [String: Int] {
        defaults.dictionary(forKey: key) as? [String: Int] ?? [:]
    }

    private static func searchQueryComponent(from value: String) -> String {
        normalizeMatchValue(value).replacingOccurrences(of: " ", with: "_")
    }

    private static func readerLoadOrder(pageCount: Int, anchor: Int) -> [Int] {
        guard pageCount > 0 else {
            return []
        }

        let boundedAnchor = min(max(anchor, 0), pageCount - 1)
        var indices: [Int] = [boundedAnchor]
        var distance = 1

        while indices.count < pageCount {
            let previousIndex = boundedAnchor - distance
            if previousIndex >= 0 {
                indices.append(previousIndex)
            }

            let nextIndex = boundedAnchor + distance
            if nextIndex < pageCount {
                indices.append(nextIndex)
            }

            distance += 1
        }

        return indices
    }

    nonisolated fileprivate static func normalizeMatchValue(_ value: String) -> String {
        value
            .lowercased()
            .replacingOccurrences(of: "_", with: " ")
            .trimmingCharacters(in: .whitespacesAndNewlines)
    }

    private var localizationBundle: Bundle {
        guard let path = Bundle.main.path(forResource: settings.appLanguage.localeIdentifier, ofType: "lproj"),
              let bundle = Bundle(path: path) else {
            return .main
        }

        return bundle
    }
}

private struct RecommendationProfile {
    let artists: Set<String>
    let groups: Set<String>
    let series: Set<String>
    let characters: Set<String>
    let tags: Set<String>
    let topArtists: [String]
    let topGroups: [String]
    let topSeries: [String]
    let topCharacters: [String]
    let topTags: [String]
    let topLanguage: String?
    let topType: String?

    init(seedInfos: [GalleryInfo]) {
        artists = Set(seedInfos.flatMap { $0.primaryArtists }.map(AppStore.normalizeMatchValue(_:)))
        groups = Set(seedInfos.flatMap { $0.primaryGroups }.map(AppStore.normalizeMatchValue(_:)))
        series = Set(seedInfos.flatMap { $0.primarySeries }.map(AppStore.normalizeMatchValue(_:)))
        characters = Set(seedInfos.flatMap { $0.primaryCharacters }.map(AppStore.normalizeMatchValue(_:)))
        tags = Set(seedInfos.flatMap { info in
            (info.tags ?? []).map { AppStore.normalizeMatchValue($0.tag) }
        })
        topArtists = RecommendationProfile.topValues(
            from: seedInfos.flatMap { $0.primaryArtists }.map(AppStore.normalizeMatchValue(_:)),
            limit: 4
        )
        topGroups = RecommendationProfile.topValues(
            from: seedInfos.flatMap { $0.primaryGroups }.map(AppStore.normalizeMatchValue(_:)),
            limit: 3
        )
        topSeries = RecommendationProfile.topValues(
            from: seedInfos.flatMap { $0.primarySeries }.map(AppStore.normalizeMatchValue(_:)),
            limit: 3
        )
        topCharacters = RecommendationProfile.topValues(
            from: seedInfos.flatMap { $0.primaryCharacters }.map(AppStore.normalizeMatchValue(_:)),
            limit: 3
        )
        topTags = RecommendationProfile.topValues(
            from: seedInfos.flatMap { info in
                (info.tags ?? []).map { AppStore.normalizeMatchValue($0.tag) }
            },
            limit: 8
        )

        topLanguage = RecommendationProfile.topValue(
            from: seedInfos.map { AppStore.normalizeMatchValue($0.displayLanguage) }
        )
        topType = RecommendationProfile.topValue(
            from: seedInfos.map { AppStore.normalizeMatchValue($0.type) }
        )
    }

    private static func topValue(from values: [String]) -> String? {
        topValues(from: values, limit: 1).first
    }

    private static func topValues(from values: [String], limit: Int) -> [String] {
        let counts = values.reduce(into: [String: Int]()) { partial, value in
            guard !value.isEmpty else {
                return
            }
            partial[value, default: 0] += 1
        }

        return counts
            .sorted { lhs, rhs in
                if lhs.value == rhs.value {
                    return lhs.key < rhs.key
                }
                return lhs.value > rhs.value
            }
            .prefix(limit)
            .map(\.key)
    }
}

private struct RecommendationSearchSeed: Hashable {
    let query: String
    let baseScore: Double
    let rankWeight: Double
    let resultLimit: Int
}
