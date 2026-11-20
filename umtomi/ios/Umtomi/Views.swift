import SwiftUI
import UIKit
import UniformTypeIdentifiers

struct RootView: View {
    @ObservedObject var store: AppStore
    @Environment(\.scenePhase) private var scenePhase

    @State private var openedTabs: Set<UmtomiTab> = []
    @State private var showingMore = false
    @State private var showingSettings = false

    var body: some View {
        ZStack {
            ForEach(store.orderedTabs.filter { openedTabs.contains($0) || $0 == store.selectedTab }) { tab in
                let visible = !showingMore && tab == store.selectedTab
                tabContent(for: tab)
                    .opacity(visible ? 1 : 0)
                    .allowsHitTesting(visible)
                    .accessibilityHidden(!visible)
            }
            if showingMore && store.settings.showsMoreTab { moreMenu }
        }
        .safeAreaInset(edge: .bottom, spacing: 0) { tabBar }
        .onChange(of: store.selectedTab, initial: true) { _, tab in
            openedTabs.insert(tab)
            showingMore = false
        }
        .onChange(of: store.settings.showsMoreTab) { _, enabled in
            if !enabled { showingMore = false }
        }
        .sheet(isPresented: $showingSettings) {
            NavigationStack { SettingsView(store: store) }
        }
        .task {
            await store.bootstrap()
        }
        .overlay {
            if store.hasReaderTabs && store.presentedGallery?.presentation != .reader && scenePhase == .active {
                ReaderFloatingButton {
                    store.showReaderWorkspace()
                }
            }
        }
        .sheet(item: $store.presentedGallery, onDismiss: {
            store.handlePresentedRouteDismissal()
        }) { route in
            NavigationStack {
                switch route.presentation {
                case .detail:
                    GalleryDetailView(store: store, galleryID: route.galleryID)
                case .reader:
                    ReaderWorkspaceView(store: store)
                }
            }
        }
        .onOpenURL { url in
            store.handleIncomingURL(url)
        }
        .alert("Error", isPresented: errorBinding) {
            Button("OK", role: .cancel) {
                store.errorMessage = nil
            }
        } message: {
            Text(store.errorMessage ?? "")
        }
    }

    private var tabBar: some View {
        let barTabs = store.settings.barTabs
        let moreSelected = showingMore || store.settings.overflowTabs.contains(store.selectedTab)
        var items = barTabs.map { tab in
            NativeBottomTabBar.Item(id: tab.rawValue, title: store.localized(tab.titleKey), symbol: tab.systemImage)
        }
        if store.settings.showsMoreTab {
            items.append(.init(id: "more", title: store.localized("More"), symbol: "ellipsis"))
        }
        return NativeBottomTabBar(items: items, selection: moreSelected ? "more" : store.selectedTab.rawValue) { id in
            if id == "more" { showingMore = true }
            else if let tab = UmtomiTab(rawValue: id) {
                showingMore = false
                store.selectedTab = tab
            }
        }
        .frame(height: 49)
        .background { Rectangle().fill(.bar).ignoresSafeArea(edges: .bottom) }
    }

    private var moreMenu: some View {
        NavigationStack {
            List {
                ForEach(store.settings.overflowTabs) { tab in
                    Button {
                        showingMore = false
                        store.selectedTab = tab
                    } label: {
                        Label(LocalizedStringKey(tab.titleKey), systemImage: tab.systemImage)
                    }
                }
                Button {
                    showingSettings = true
                } label: {
                    Label("Settings", systemImage: "gearshape")
                }
            }
            .navigationTitle("More")
        }
    }

    @ViewBuilder
    private func tabContent(for tab: UmtomiTab) -> some View {
        switch tab {
        case .search:
            NavigationStack {
                BrowseView(store: store)
            }
        case .algorithm:
            NavigationStack {
                AlgorithmView(store: store)
            }
        case .downloads:
            NavigationStack {
                DownloadsListView(store: store)
            }
        case .history:
            NavigationStack {
                SavedGalleryListView(
                    title: "History",
                    summaries: store.historySummaries,
                    emptyTitle: "No reading history yet",
                    emptyMessage: "Open a gallery to start building your history.",
                    store: store
                )
            }
        case .bookshelf:
            NavigationStack { BookshelfView(library: store.bookshelf, store: store) }
        case .favorites:
            NavigationStack {
                FavoritesView(store: store, folderID: nil)
            }
        }
    }

    private var errorBinding: Binding<Bool> {
        Binding(
            get: { store.errorMessage != nil },
            set: { isPresented in
                if !isPresented {
                    store.errorMessage = nil
                }
            }
        )
    }
}

// The native control provides the system tab appearance while RootView owns
// tab selection, so iOS does not create a second automatic More screen.
private struct NativeBottomTabBar: UIViewRepresentable {
    struct Item: Equatable {
        let id: String
        let title: String
        let symbol: String
    }

    let items: [Item]
    let selection: String
    let select: (String) -> Void

    func makeCoordinator() -> Coordinator { Coordinator() }

    func makeUIView(context: Context) -> UITabBar {
        let bar = UITabBar()
        bar.delegate = context.coordinator
        bar.itemPositioning = .fill
        return bar
    }

    func updateUIView(_ bar: UITabBar, context: Context) {
        let coordinator = context.coordinator
        coordinator.select = select
        if coordinator.items != items {
            coordinator.items = items
            bar.setItems(items.enumerated().map { index, item in
                UITabBarItem(title: item.title, image: UIImage(systemName: item.symbol), tag: index)
            }, animated: false)
        }
        let index = items.firstIndex { $0.id == selection }
        bar.selectedItem = index.flatMap { bar.items?[$0] }
    }

    final class Coordinator: NSObject, UITabBarDelegate {
        var items: [Item] = []
        var select: ((String) -> Void)?

        func tabBar(_ tabBar: UITabBar, didSelect item: UITabBarItem) {
            guard items.indices.contains(item.tag) else { return }
            select?(items[item.tag].id)
        }
    }
}

struct ReaderFloatingButton: View {
    let action: () -> Void

    var body: some View {
        VStack {
            Spacer()

            HStack {
                Spacer()

                Button(action: action) {
                    Label("Reader", systemImage: "book.pages")
                        .labelStyle(.iconOnly)
                        .font(.title3.weight(.semibold))
                        .frame(width: 54, height: 54)
                }
                .buttonStyle(.borderedProminent)
                .clipShape(Circle())
                .shadow(color: .black.opacity(0.28), radius: 12, x: 0, y: 6)
                .accessibilityLabel(Text("Reader"))
                .padding(.trailing, 18)
                .padding(.bottom, 72)
            }
        }
    }
}

struct AppSwitcherShieldView: View {
    var body: some View {
        ZStack {
            LinearGradient(
                colors: [Color.black, Color(red: 0.08, green: 0.08, blue: 0.10)],
                startPoint: .top,
                endPoint: .bottom
            )

            VStack(spacing: 14) {
                Image(systemName: "eye.slash.fill")
                    .font(.system(size: 34, weight: .semibold))
                    .foregroundStyle(.white)

                Text("Umtomi")
                    .font(.title3.weight(.semibold))
                    .foregroundStyle(.white)
            }
        }
        .ignoresSafeArea()
    }
}

struct BrowseView: View {
    @ObservedObject var store: AppStore

    @State private var isOpenByIDPresented = false
    @State private var isJumpToPagePresented = false
    @State private var isSettingsPresented = false
    @State private var pendingGalleryIDToOpen: Int?
    @State private var suggestionTask: Task<Void, Never>?

    private let columns = [GridItem(.adaptive(minimum: 150, maximum: 220), spacing: 16)]

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 20) {
                headerSection
                pageSection

                if store.isBootstrapping && !store.hasCompletedInitialSearch {
                    initialLoadingView
                } else if store.isSearching || store.isLoadingPage {
                    HStack(spacing: 12) {
                        ProgressView()
                        Text("Loading galleries...")
                            .foregroundStyle(.secondary)
                    }
                    .frame(maxWidth: .infinity, alignment: .leading)
                } else if store.visibleSummaries.isEmpty {
                    ContentUnavailableView(
                        "No Result",
                        systemImage: "rectangle.stack.badge.magnifyingglass",
                        description: Text("Adjust the query or sort mode and try again.")
                    )
                } else {
                    LazyVGrid(columns: columns, spacing: 16) {
                        ForEach(store.visibleSummaries) { summary in
                            NavigationLink {
                                GalleryDetailView(store: store, galleryID: summary.id)
                            } label: {
                                GalleryCardView(summary: summary)
                            }
                            .buttonStyle(.plain)
                        }
                    }
                }
            }
            .padding()
        }
        .navigationTitle("Umtomi")
        .searchable(text: $store.searchQuery, placement: .navigationBarDrawer(displayMode: .always), prompt: "Search galleries")
        .onSubmit(of: .search) {
            Task {
                await store.search()
            }
        }
        .searchSuggestions {
            if store.searchQuery.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
                ForEach(store.recentSearches, id: \.self) { query in
                    Button(query) {
                        store.searchQuery = query
                    }
                    .searchCompletion(query)
                }
            } else {
                ForEach(store.suggestions) { suggestion in
                    Button("\(suggestion.label) (\(suggestion.count))") {
                        store.applySuggestion(suggestion)
                    }
                    .searchCompletion(suggestion.query)
                }
            }
        }
        .onChange(of: store.searchQuery) { _, newValue in
            suggestionTask?.cancel()
            suggestionTask = Task {
                try? await Task.sleep(for: .milliseconds(250))
                guard !Task.isCancelled else {
                    return
                }
                await store.loadSuggestions(for: newValue)
            }
        }
        .toolbar {
            ToolbarItem(placement: .topBarTrailing) {
                Menu {
                    Button {
                        isSettingsPresented = true
                    } label: {
                        Label("Settings", systemImage: "gearshape")
                    }

                    Button {
                        isOpenByIDPresented = true
                    } label: {
                        Label("Open Gallery by ID", systemImage: "number.square")
                    }

                    Button {
                        store.openRandomGallery()
                    } label: {
                        Label("Random Gallery", systemImage: "dice")
                    }
                } label: {
                    Image(systemName: "ellipsis.circle")
                }
            }
        }
        .sheet(isPresented: $isOpenByIDPresented, onDismiss: {
            guard let galleryID = pendingGalleryIDToOpen else {
                return
            }

            pendingGalleryIDToOpen = nil
            store.openGallery(id: galleryID)
        }) {
            OpenGallerySheet { galleryID in
                pendingGalleryIDToOpen = galleryID
                isOpenByIDPresented = false
            }
        }
        .sheet(isPresented: $isSettingsPresented) {
            NavigationStack {
                SettingsView(store: store)
            }
        }
        .sheet(isPresented: $isJumpToPagePresented) {
            IntegerInputSheet(
                title: "Jump to Page",
                message: store.localized("Enter a page between 1 and %lld.", Int64(store.totalPageCount)),
                placeholder: "\(store.currentPageDisplay)",
                actionTitle: "Go"
            ) { value in
                Task {
                    await store.goToPage(value)
                }
            }
        }
        .onDisappear {
            suggestionTask?.cancel()
        }
        .onAppear {
            if store.consumeSearchSettingsChange() {
                Task {
                    await store.search()
                }
            }
        }
    }

    private var headerSection: some View {
        HStack(spacing: 12) {
            Text("Sort Order")
                .font(.subheadline.weight(.semibold))

            Picker("Sort", selection: sortBinding) {
                ForEach(SortMode.allCases) { mode in
                    Text(mode.rawValue).tag(mode)
                }
            }
            .pickerStyle(.menu)

            Spacer(minLength: 0)
        }
    }

    private var initialLoadingView: some View {
        VStack(alignment: .leading, spacing: 12) {
            HStack(spacing: 12) {
                ProgressView()
                    .controlSize(.large)

                VStack(alignment: .leading, spacing: 4) {
                    Text("Loading galleries...")
                        .font(.headline)
                    Text("Fetching the first results from Hitomi.")
                        .foregroundStyle(.secondary)
                }
            }

            RoundedRectangle(cornerRadius: 16)
                .fill(Color.secondary.opacity(0.12))
                .frame(height: 180)
                .overlay {
                    Image(systemName: "photo.on.rectangle.angled")
                        .font(.system(size: 32, weight: .medium))
                        .foregroundStyle(.secondary)
                }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
    }

    private var pageSection: some View {
        VStack(alignment: .leading, spacing: 8) {
            HStack {
                Text(resultSummaryText)
                    .font(.headline)

                Spacer()

                Text(pageSummaryText)
                    .foregroundStyle(.secondary)
            }

            HStack(spacing: 12) {
                Button("Previous") {
                    Task {
                        await store.goToPreviousPage()
                    }
                }
                .buttonStyle(.bordered)
                .disabled(!store.canGoToPreviousPage)

                Button("Jump") {
                    isJumpToPagePresented = true
                }
                .buttonStyle(.bordered)
                .disabled(store.totalResults == 0)

                Button("Next") {
                    Task {
                        await store.goToNextPage()
                    }
                }
                .buttonStyle(.borderedProminent)
                .disabled(!store.canGoToNextPage)
            }
        }
    }

    private var sortBinding: Binding<SortMode> {
        Binding(
            get: { store.sortMode },
            set: { newValue in
                Task {
                    await store.updateSortMode(newValue)
                }
            }
        )
    }

    private var resultSummaryText: String {
        if store.isBootstrapping && !store.hasCompletedInitialSearch {
            return "Loading results..."
        }

        return store.localized("%lld results", Int64(store.totalResults))
    }

    private var pageSummaryText: String {
        if store.isBootstrapping && !store.hasCompletedInitialSearch {
            return "Preparing first page..."
        }

        return store.localized("Page %lld/%lld", Int64(store.currentPageDisplay), Int64(store.totalPageCount))
    }
}

struct PersistentTagFiltersView: View {
    let includedTags: String
    let excludedTags: String

    var body: some View {
        if !includedTags.isEmpty || !excludedTags.isEmpty {
            VStack(alignment: .leading, spacing: 8) {
                if !includedTags.isEmpty {
                    TagFilterCardView(
                        title: "Always Included Tags",
                        value: includedTags,
                        tint: .green
                    )
                }

                if !excludedTags.isEmpty {
                    TagFilterCardView(
                        title: "Always Excluded Tags",
                        value: excludedTags,
                        tint: .red
                    )
                }
            }
        }
    }
}

struct TagFilterCardView: View {
    let title: String
    let value: String
    let tint: Color

    var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            Text(title)
                .font(.caption.weight(.semibold))
                .foregroundStyle(.secondary)

            Text(value)
                .font(.footnote.monospaced())
                .foregroundStyle(.primary)
        }
        .padding(10)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(tint.opacity(0.10), in: RoundedRectangle(cornerRadius: 12))
    }
}

struct AlgorithmView: View {
    @ObservedObject var store: AppStore

    var body: some View {
        Group {
            if store.isLoadingAlgorithm {
                VStack(spacing: 12) {
                    ProgressView()
                    Text("Building recommendations...")
                        .foregroundStyle(.secondary)
                }
            } else if store.algorithmSummaries.isEmpty {
                ContentUnavailableView(
                    store.hasRecommendationSeed ? "No Matching Recommendations" : "No Recommendation Seed Yet",
                    systemImage: store.hasRecommendationSeed ? "sparkles.slash" : "sparkles.rectangle.stack",
                    description: Text(
                        store.hasRecommendationSeed
                        ? "Read or favorite more galleries, or loosen the included and excluded tag filters."
                        : "Read or favorite galleries to start getting similar recommendations."
                    )
                )
            } else {
                List {
                    Section("Recommended For You") {
                        ForEach(store.algorithmSummaries) { summary in
                            NavigationLink {
                                GalleryDetailView(store: store, galleryID: summary.id)
                            } label: {
                                GalleryRowView(summary: summary)
                            }
                        }
                    }
                }
                .listStyle(.plain)
            }
        }
        .navigationTitle("Algorithm")
        .onAppear {
            Task {
                if store.consumeAlgorithmRefreshRequest() || store.algorithmSummaries.isEmpty {
                    await store.refreshAlgorithmRecommendations()
                }
            }
        }
        .refreshable {
            await store.refreshAlgorithmRecommendations()
        }
    }
}

struct GalleryCardView: View {
    let summary: GallerySummary

    var body: some View {
        VStack(alignment: .leading, spacing: 10) {
            ZStack {
                RoundedRectangle(cornerRadius: 14)
                    .fill(Color.secondary.opacity(0.08))

                RemoteImageView(
                    url: summary.coverURL,
                    contentMode: .fit,
                    expandsToContainer: false
                )
                .padding(8)
            }
                .frame(maxWidth: .infinity)
                .frame(height: 230)
                .clipped()
                .clipShape(RoundedRectangle(cornerRadius: 14))

            Text(summary.title)
                .font(.headline)
                .lineLimit(2)
                .frame(maxWidth: .infinity, alignment: .leading)

            Text(subtitle)
                .font(.subheadline)
                .foregroundStyle(.secondary)
                .lineLimit(3)
                .frame(maxWidth: .infinity, alignment: .leading)

            HStack {
                Label(summary.type.capitalized, systemImage: "books.vertical")
                Spacer()
                Text("\(summary.pageCount)P")
            }
            .font(.caption)
            .foregroundStyle(.secondary)
        }
        .padding(12)
        .frame(maxWidth: .infinity, alignment: .topLeading)
        .background(.thinMaterial, in: RoundedRectangle(cornerRadius: 18))
    }

    private var subtitle: String {
        if !summary.artists.isEmpty {
            return summary.artists.joined(separator: ", ")
        }
        if !summary.series.isEmpty {
            return summary.series.joined(separator: ", ")
        }
        if !summary.groups.isEmpty {
            return summary.groups.joined(separator: ", ")
        }
        return summary.language
    }
}

struct SavedGalleryListView: View {
    let title: String
    let summaries: [GallerySummary]
    let emptyTitle: String
    let emptyMessage: String
    @ObservedObject var store: AppStore

    var body: some View {
        Group {
            if summaries.isEmpty {
                ContentUnavailableView(
                    LocalizedStringKey(emptyTitle),
                    systemImage: "tray",
                    description: Text(LocalizedStringKey(emptyMessage))
                )
            } else {
                List(summaries) { summary in
                    NavigationLink {
                        GalleryDetailView(store: store, galleryID: summary.id)
                    } label: {
                        GalleryRowView(summary: summary)
                    }
                }
                .listStyle(.plain)
            }
        }
        .navigationTitle(LocalizedStringKey(title))
    }
}

private struct FavoriteFolderEditorContext: Identifiable {
    let folder: FavoriteFolder
    let isNew: Bool

    var id: UUID { folder.id }
}

struct FavoritesView: View {
    @ObservedObject var store: AppStore
    let folderID: UUID?

    @Environment(\.dismiss) private var dismiss

    @State private var editorContext: FavoriteFolderEditorContext?
    @State private var pendingDeleteFolder: FavoriteFolder?
    @State private var selectedRootSection: FavoriteBrowserSection = .works

    private var currentFolder: FavoriteFolder? {
        guard let folderID else {
            return nil
        }

        return store.favoriteFolder(id: folderID)
    }

    private var childFolders: [FavoriteFolder] {
        store.favoriteFolders(in: folderID)
    }

    private var summaries: [GallerySummary] {
        store.favoriteGallerySummaries(in: folderID)
    }

    private var title: String {
        currentFolder?.name ?? "Favorites"
    }

    private var isShowingWorksSection: Bool {
        folderID != nil || selectedRootSection == .works
    }

    var body: some View {
        Group {
            if folderID == nil {
                VStack(spacing: 0) {
                    Picker("Favorites", selection: $selectedRootSection) {
                        ForEach(FavoriteBrowserSection.allCases) { section in
                            Text(section.rawValue).tag(section)
                        }
                    }
                    .pickerStyle(.segmented)
                    .padding(.horizontal)
                    .padding(.top, 12)

                    if isShowingWorksSection {
                        worksContent
                    } else {
                        artistsContent
                    }
                }
            } else {
                worksContent
            }
        }
        .navigationTitle(title)
        .toolbar {
            if isShowingWorksSection {
                ToolbarItem(placement: .topBarTrailing) {
                    Menu {
                        Button {
                            editorContext = FavoriteFolderEditorContext(
                                folder: store.createFavoriteFolder(parentID: folderID),
                                isNew: true
                            )
                        } label: {
                            Label(folderID == nil ? "New Folder" : "New Subfolder", systemImage: "folder.badge.plus")
                        }

                        if let currentFolder {
                            Button {
                                editorContext = FavoriteFolderEditorContext(folder: currentFolder, isNew: false)
                            } label: {
                                Label("Edit Folder", systemImage: "pencil")
                            }

                            Button(role: .destructive) {
                                pendingDeleteFolder = currentFolder
                            } label: {
                                Label("Delete Folder", systemImage: "trash")
                            }
                        }
                    } label: {
                        Image(systemName: "ellipsis.circle")
                    }
                }
            }
        }
        .sheet(item: $editorContext) { context in
            NavigationStack {
                FavoriteFolderEditorView(
                    store: store,
                    folder: context.folder,
                    isNewFolder: context.isNew
                )
            }
        }
        .onAppear {
            guard let folderID else {
                return
            }

            Task {
                await store.prepareFavoriteAutoMatchesIfNeeded(for: folderID)
            }
        }
        .alert(
            "Delete Folder?",
            isPresented: pendingDeleteAlertBinding,
            presenting: pendingDeleteFolder
        ) { folder in
            Button("Cancel", role: .cancel) {
                pendingDeleteFolder = nil
            }

            Button("Delete", role: .destructive) {
                let shouldDismiss = folder.id == folderID
                store.deleteFavoriteFolder(folder.id)
                pendingDeleteFolder = nil

                if shouldDismiss {
                    dismiss()
                }
            }
        } message: { folder in
            Text("This deletes \(folder.name) and its subfolders. Favorited galleries themselves will stay saved.")
        }
    }

    @ViewBuilder
    private var worksContent: some View {
        if folderID != nil && currentFolder == nil {
            ContentUnavailableView(
                "Folder Not Available",
                systemImage: "folder.badge.questionmark",
                description: Text("The folder could not be found.")
            )
        } else if childFolders.isEmpty && summaries.isEmpty {
            ContentUnavailableView(
                folderID == nil ? "No favorites yet" : "No items in this folder",
                systemImage: folderID == nil ? "star" : "folder",
                description: Text(
                    folderID == nil
                    ? "Star a gallery from its detail screen to keep it here."
                    : "Add manual favorites or set auto sorting rules for this folder."
                )
            )
        } else {
            favoritesList
        }
    }

    @ViewBuilder
    private var artistsContent: some View {
        if store.favoriteArtists.isEmpty {
            ContentUnavailableView(
                "No favorite artists yet",
                systemImage: "person.crop.square",
                description: Text("Favorite artists from a gallery detail screen to keep them here.")
            )
        } else {
            List {
                Section("Favorite Artists") {
                    ForEach(store.favoriteArtists) { artist in
                        favoriteArtistRow(for: artist)
                    }
                }
            }
            .listStyle(.plain)
        }
    }

    private var favoritesList: some View {
        List {
            autoSortingSection

            if !childFolders.isEmpty {
                Section(folderID == nil ? "Folders" : "Subfolders") {
                    ForEach(childFolders) { folder in
                        childFolderLink(for: folder)
                    }
                }
            }

            if !summaries.isEmpty {
                Section(folderID == nil ? "All Favorites" : "Favorites") {
                    ForEach(summaries) { summary in
                        summaryLink(for: summary)
                    }
                }
            }
        }
        .listStyle(.plain)
    }

    @ViewBuilder
    private var autoSortingSection: some View {
        if let currentFolder {
            let autoRules = store.favoriteFolderAutoRulesDisplay(currentFolder)
            if !autoRules.isEmpty {
                Section("Auto Sorting") {
                    VStack(alignment: .leading, spacing: 6) {
                        Text(autoRules)
                            .font(.footnote.monospaced())
                        Text("These rules only affect this folder.")
                            .font(.caption)
                            .foregroundStyle(.secondary)
                    }
                    .padding(.vertical, 4)
                }
            }
        }
    }

    private func childFolderLink(for folder: FavoriteFolder) -> some View {
        NavigationLink {
            FavoritesView(store: store, folderID: folder.id)
        } label: {
            FavoriteFolderRowView(
                folder: folder,
                galleryCount: store.favoriteGalleryCount(in: folder.id),
                childFolderCount: store.favoriteFolders(in: folder.id).count,
                autoRulesDisplay: store.favoriteFolderAutoRulesDisplay(folder)
            )
        }
        .swipeActions(edge: .trailing, allowsFullSwipe: false) {
            Button("Delete", role: .destructive) {
                pendingDeleteFolder = folder
            }

            Button("Edit") {
                editorContext = FavoriteFolderEditorContext(folder: folder, isNew: false)
            }
            .tint(.blue)
        }
    }

    private func summaryLink(for summary: GallerySummary) -> some View {
        NavigationLink {
            GalleryDetailView(store: store, galleryID: summary.id)
        } label: {
            GalleryRowView(summary: summary)
        }
        .swipeActions(edge: .trailing, allowsFullSwipe: true) {
            if let folderID, store.isFavoriteManuallyContained(summary.id, in: folderID) {
                Button("Remove") {
                    store.removeGallery(summary.id, from: folderID)
                }
                .tint(.orange)
            }
        }
    }

    private func favoriteArtistRow(for artist: FavoriteArtist) -> some View {
        Button {
            Task {
                await store.searchForTag(store.artistSearchQuery(for: artist.name))
            }
        } label: {
            HStack(spacing: 12) {
                Image(systemName: "person.crop.square.fill")
                    .font(.title3)
                    .foregroundStyle(.orange)
                    .frame(width: 28)

                VStack(alignment: .leading, spacing: 4) {
                    Text(artist.name)
                        .font(.headline)
                        .foregroundStyle(.primary)

                    Text("Tap to search this artist")
                        .font(.caption)
                        .foregroundStyle(.secondary)
                }

                Spacer(minLength: 0)

                Image(systemName: "magnifyingglass")
                    .foregroundStyle(.secondary)
            }
            .padding(.vertical, 4)
        }
        .buttonStyle(.plain)
        .swipeActions(edge: .trailing, allowsFullSwipe: true) {
            Button("Remove", role: .destructive) {
                store.toggleFavoriteArtist(artist.name)
            }
        }
    }

    private var pendingDeleteAlertBinding: Binding<Bool> {
        Binding(
            get: { pendingDeleteFolder != nil },
            set: { isPresented in
                if !isPresented {
                    pendingDeleteFolder = nil
                }
            }
        )
    }
}

struct FavoriteFolderRowView: View {
    let folder: FavoriteFolder
    let galleryCount: Int
    let childFolderCount: Int
    let autoRulesDisplay: String

    var body: some View {
        HStack(spacing: 12) {
            Image(systemName: childFolderCount > 0 ? "folder.fill.badge.plus" : "folder.fill")
                .font(.title3)
                .foregroundStyle(.yellow)
                .frame(width: 28)

            VStack(alignment: .leading, spacing: 4) {
                Text(folder.name)
                    .font(.headline)

                HStack(spacing: 8) {
                    Text("\(galleryCount) galleries")
                    if childFolderCount > 0 {
                        Text("\(childFolderCount) folders")
                    }
                }
                .font(.caption)
                .foregroundStyle(.secondary)

                if !autoRulesDisplay.isEmpty {
                    Text(autoRulesDisplay)
                        .font(.caption.monospaced())
                        .foregroundStyle(.secondary)
                        .lineLimit(2)
                }
            }
        }
        .padding(.vertical, 4)
    }
}

struct FavoriteFolderEditorView: View {
    @ObservedObject var store: AppStore
    let folder: FavoriteFolder
    let isNewFolder: Bool

    @Environment(\.dismiss) private var dismiss

    @State private var name: String
    @State private var autoRuleQuery: String
    @State private var selectedGalleryIDs: Set<Int>

    init(store: AppStore, folder: FavoriteFolder, isNewFolder: Bool) {
        self.store = store
        self.folder = folder
        self.isNewFolder = isNewFolder
        _name = State(initialValue: folder.name)
        _autoRuleQuery = State(initialValue: folder.autoRuleQuery)
        _selectedGalleryIDs = State(initialValue: Set(folder.manualGalleryIDs))
    }

    var body: some View {
        Form {
            Section("Folder") {
                TextField("Folder name", text: $name)
            }

            Section("Auto Sorting Rules") {
                TextField("artist:name, group:name, female:ahegao", text: $autoRuleQuery, axis: .vertical)
                    .lineLimit(2...5)

                Text("Matches any rule in this folder only. Parent and child folders are not affected.")
                    .font(.footnote)
                    .foregroundStyle(.secondary)
            }

            Section("Manual Favorites") {
                if store.favoriteSummaries.isEmpty {
                    Text("Favorite a gallery first, then it can be placed in folders.")
                        .font(.footnote)
                        .foregroundStyle(.secondary)
                } else {
                    ForEach(store.favoriteSummaries) { summary in
                        Button {
                            toggle(summary.id)
                        } label: {
                            FavoriteFolderSelectionRow(
                                summary: summary,
                                isSelected: selectedGalleryIDs.contains(summary.id)
                            )
                        }
                        .buttonStyle(.plain)
                    }
                }
            }
        }
        .navigationTitle(isNewFolder ? "New Folder" : "Edit Folder")
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            ToolbarItem(placement: .topBarLeading) {
                Button("Cancel") {
                    dismiss()
                }
            }

            ToolbarItem(placement: .topBarTrailing) {
                Button("Save") {
                    save()
                }
                .disabled(name.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)
            }
        }
    }

    private func toggle(_ galleryID: Int) {
        if selectedGalleryIDs.contains(galleryID) {
            selectedGalleryIDs.remove(galleryID)
        } else {
            selectedGalleryIDs.insert(galleryID)
        }
    }

    private func save() {
        store.saveFavoriteFolder(
            FavoriteFolder(
                id: folder.id,
                parentID: folder.parentID,
                name: name,
                manualGalleryIDs: Array(selectedGalleryIDs),
                autoRuleQuery: autoRuleQuery,
                createdAt: folder.createdAt
            )
        )
        dismiss()
    }
}

struct FavoriteFolderSelectionRow: View {
    let summary: GallerySummary
    let isSelected: Bool

    var body: some View {
        HStack(spacing: 12) {
            GalleryRowView(summary: summary)

            Spacer(minLength: 8)

            Image(systemName: isSelected ? "checkmark.circle.fill" : "circle")
                .font(.title3)
                .foregroundStyle(isSelected ? Color.accentColor : .secondary)
        }
    }
}

struct DownloadsListView: View {
    @ObservedObject var store: AppStore

    var body: some View {
        Group {
            if store.downloadSummaries.isEmpty && store.activeDownloads.isEmpty {
                ContentUnavailableView(
                    "No downloads yet",
                    systemImage: "arrow.down.circle",
                    description: Text("Download a gallery from its detail screen to read it offline.")
                )
            } else {
                List {
                    if !store.activeDownloads.isEmpty {
                        Section("Active Downloads") {
                            ForEach(store.activeDownloads) { download in
                                VStack(alignment: .leading, spacing: 8) {
                                    Text("Gallery \(download.galleryID)")
                                        .font(.headline)

                                    ProgressView(value: download.progress)

                                    Text("\(Int(download.progress * 100))% complete")
                                        .font(.caption)
                                        .foregroundStyle(.secondary)

                                    Button("Cancel Download", role: .cancel) {
                                        store.cancelDownload(download.galleryID)
                                    }
                                    .buttonStyle(.borderless)
                                }
                                .padding(.vertical, 4)
                            }
                        }
                    }

                    if !store.downloadSummaries.isEmpty {
                        Section("Saved Offline") {
                            ForEach(store.downloadSummaries) { summary in
                                NavigationLink {
                                    GalleryDetailView(store: store, galleryID: summary.id)
                                } label: {
                                    GalleryRowView(summary: summary)
                                }
                                .swipeActions(edge: .trailing, allowsFullSwipe: true) {
                                    Button("Delete", role: .destructive) {
                                        Task {
                                            await store.toggleDownload(summary.id)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
                .listStyle(.plain)
            }
        }
        .navigationTitle("Downloads")
    }
}

struct GalleryRowView: View {
    let summary: GallerySummary

    var body: some View {
        HStack(spacing: 12) {
            ZStack {
                RoundedRectangle(cornerRadius: 10)
                    .fill(Color.secondary.opacity(0.08))

                RemoteImageView(
                    url: summary.coverURL,
                    contentMode: .fit,
                    expandsToContainer: false
                )
                .padding(6)
            }
                .frame(width: 72, height: 100)
                .clipped()
                .clipShape(RoundedRectangle(cornerRadius: 10))

            VStack(alignment: .leading, spacing: 6) {
                Text(summary.title)
                    .font(.headline)
                    .lineLimit(2)

                Text(summary.artists.isEmpty ? summary.language : summary.artists.joined(separator: ", "))
                    .font(.subheadline)
                    .foregroundStyle(.secondary)
                    .lineLimit(2)

                HStack {
                    Text(summary.type.capitalized)
                    Text("|")
                    Text("\(summary.pageCount) pages")
                }
                .font(.caption)
                .foregroundStyle(.secondary)
            }
        }
        .padding(.vertical, 4)
    }
}

struct GalleryDetailView: View {
    @ObservedObject var store: AppStore
    let galleryID: Int

    @State private var info: GalleryInfo?
    @State private var coverURL: URL?
    @State private var relatedSummaries: [GallerySummary] = []
    @State private var isLoading = true
    @State private var searchOverlayRequest: DetailSearchOverlayRequest?

    private let service = HitomiService.shared
    private let downloadLibrary = DownloadLibrary.shared

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 18) {
                if let coverURL {
                    RemoteImageView(url: coverURL, contentMode: .fit)
                        .frame(maxWidth: .infinity)
                        .frame(height: 320)
                        .clipShape(RoundedRectangle(cornerRadius: 18))
                }

                if let info {
                    VStack(alignment: .leading, spacing: 12) {
                        Text(info.title)
                            .font(.title2.bold())

                        if let japaneseTitle = info.japaneseTitle, !japaneseTitle.isEmpty, japaneseTitle != info.title {
                            Text(japaneseTitle)
                                .font(.subheadline)
                                .foregroundStyle(.secondary)
                        }

                        HStack(spacing: 12) {
                            Menu {
                                Button {
                                    store.openReaderTab(galleryID: galleryID, initialInfo: info, activate: true)
                                } label: {
                                    Label("Open", systemImage: "book.fill")
                                }

                                Button {
                                    store.openReaderTab(galleryID: galleryID, initialInfo: info, activate: false)
                                } label: {
                                    Label("Open in Background Tab", systemImage: "plus.square.on.square")
                                }
                            } label: {
                                Label("Read", systemImage: "book.fill")
                                    .frame(maxWidth: .infinity)
                            }
                            .buttonStyle(.borderedProminent)

                            Button {
                                Task {
                                    await store.toggleFavorite(galleryID)
                                }
                            } label: {
                                Label(store.isFavorite(galleryID) ? "Favorited" : "Favorite", systemImage: store.isFavorite(galleryID) ? "star.fill" : "star")
                                    .frame(maxWidth: .infinity)
                            }
                            .buttonStyle(.bordered)
                        }

                        Button {
                            Task {
                                await store.toggleDownload(galleryID, info: info)
                            }
                        } label: {
                            HStack {
                                if let progress = store.downloadProgress(for: galleryID) {
                                    ProgressView(value: progress)
                                        .frame(maxWidth: 72)
                                    Text(store.localized("Downloading %lld%%", Int64(progress * 100)))
                                        .fontWeight(.semibold)
                                } else {
                                    Label(
                                        isDownloaded ? "Remove Offline Copy" : "Download for Offline Use",
                                        systemImage: isDownloaded ? "trash" : "arrow.down.circle"
                                    )
                                }
                        }
                            .frame(maxWidth: .infinity)
                        }
                        .buttonStyle(.borderedProminent)
                    }

                    GroupBox("Metadata") {
                        VStack(alignment: .leading, spacing: 10) {
                            MetadataRow(label: "Type", value: info.type.capitalized)
                            MetadataRow(label: "Language", value: info.displayLanguage)
                            MetadataRow(label: "Date", value: info.date)
                            MetadataRow(label: "Pages", value: "\(info.pageCount)")
                            ArtistMetadataRow(
                                label: "Artists",
                                values: info.primaryArtists,
                                favoriteArtists: store.favoriteArtists,
                                onSelect: { artist in
                                    searchOverlayRequest = artistSearchRequest(for: artist)
                                },
                                onToggleFavorite: { artist in
                                    store.toggleFavoriteArtist(artist)
                                }
                            )
                            MetadataActionRow(label: "Groups", values: info.primaryGroups) { group in
                                searchOverlayRequest = groupSearchRequest(for: group)
                            }
                            MetadataRow(label: "Series", value: joined(info.primarySeries))
                            MetadataRow(label: "Characters", value: joined(info.primaryCharacters))
                            MetadataRow(label: "Availability", value: isDownloaded ? "Offline" : "Online only")
                        }
                    }

                    if let tags = info.tags, !tags.isEmpty {
                        VStack(alignment: .leading, spacing: 10) {
                            Text("Tags")
                                .font(.headline)

                            LazyVGrid(columns: [GridItem(.adaptive(minimum: 100), spacing: 8)], alignment: .leading, spacing: 8) {
                                ForEach(Array(tags.enumerated()), id: \.offset) { _, tag in
                                    Button(tagLabel(for: tag)) {
                                        searchOverlayRequest = tagSearchRequest(for: tag)
                                    }
                                    .buttonStyle(.bordered)
                                    .controlSize(.small)
                                }
                            }
                        }
                    }

                    if !relatedSummaries.isEmpty {
                        VStack(alignment: .leading, spacing: 10) {
                            Text("Related Galleries")
                                .font(.headline)

                            ForEach(relatedSummaries) { summary in
                                NavigationLink {
                                    GalleryDetailView(store: store, galleryID: summary.id)
                                } label: {
                                    GalleryRowView(summary: summary)
                                }
                                .buttonStyle(.plain)
                            }
                        }
                    }
                } else if isLoading {
                    VStack(spacing: 12) {
                        ProgressView()
                        Text("Loading gallery details...")
                            .foregroundStyle(.secondary)
                    }
                    .frame(maxWidth: .infinity)
                    .padding(.top, 32)
                } else {
                    ContentUnavailableView(
                        "Gallery Not Available",
                        systemImage: "exclamationmark.triangle",
                        description: Text("The gallery could not be loaded.")
                    )
                }
            }
            .padding()
        }
        .navigationTitle(info?.title ?? "Gallery \(galleryID)")
        .navigationBarTitleDisplayMode(.inline)
        .task(id: galleryID) {
            await load()
        }
        .sheet(item: $searchOverlayRequest) { request in
            NavigationStack {
                DetailSearchOverlayView(store: store, request: request)
            }
        }
    }

    @MainActor
    private func load() async {
        isLoading = true
        defer { isLoading = false }

        let fallbackInfo = await downloadLibrary.localGalleryInfo(for: galleryID)
        let fallbackCoverURL = await downloadLibrary.localCoverURL(for: galleryID)

        do {
            let fetchedInfo = try await service.galleryInfo(id: galleryID)
            let related = Array((fetchedInfo.related ?? []).prefix(6))

            info = fetchedInfo
            relatedSummaries = await service.gallerySummaries(ids: related)

            do {
                let fetchedCoverURL = try await service.coverURL(for: fetchedInfo)
                coverURL = fetchedCoverURL ?? fallbackCoverURL
            } catch {
                coverURL = fallbackCoverURL
            }
        } catch {
            if let fallbackInfo {
                info = fallbackInfo
                coverURL = fallbackCoverURL
                relatedSummaries = []
            } else {
                store.errorMessage = error.localizedDescription
            }
        }
    }

    private func joined(_ values: [String]) -> String {
        values.isEmpty ? "N/A" : values.joined(separator: ", ")
    }

    private func tagLabel(for tag: GalleryTag) -> String {
        if tag.female?.isEmpty == false {
            return "female:\(tag.tag)"
        }
        if tag.male?.isEmpty == false {
            return "male:\(tag.tag)"
        }
        return tag.tag
    }

    private func tagQuery(for tag: GalleryTag) -> String {
        let base: String

        if tag.female?.isEmpty == false {
            base = "female:\(tag.tag)"
        } else if tag.male?.isEmpty == false {
            base = "male:\(tag.tag)"
        } else {
            base = "tag:\(tag.tag)"
        }

        return base.replacingOccurrences(of: " ", with: "_")
    }

    private func artistSearchRequest(for artist: String) -> DetailSearchOverlayRequest {
        DetailSearchOverlayRequest(
            title: artist,
            query: "artist:\(artist.replacingOccurrences(of: " ", with: "_"))",
            caption: "Artists"
        )
    }

    private func groupSearchRequest(for group: String) -> DetailSearchOverlayRequest {
        DetailSearchOverlayRequest(
            title: group,
            query: "group:\(group.replacingOccurrences(of: " ", with: "_"))",
            caption: "Groups"
        )
    }

    private func tagSearchRequest(for tag: GalleryTag) -> DetailSearchOverlayRequest {
        DetailSearchOverlayRequest(
            title: tagLabel(for: tag),
            query: tagQuery(for: tag),
            caption: "Tags"
        )
    }

    private var isDownloaded: Bool {
        store.downloadSummaries.contains { $0.id == galleryID }
    }
}

struct MetadataRow: View {
    let label: String
    let value: String

    var body: some View {
        VStack(alignment: .leading, spacing: 2) {
            Text(LocalizedStringKey(label))
                .font(.caption)
                .foregroundStyle(.secondary)

            Text(value)
                .frame(maxWidth: .infinity, alignment: .leading)
        }
    }
}

struct MetadataActionRow: View {
    let label: String
    let values: [String]
    let onSelect: (String) -> Void

    private let columns = [GridItem(.adaptive(minimum: 96), spacing: 8)]

    var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            Text(LocalizedStringKey(label))
                .font(.caption)
                .foregroundStyle(.secondary)

            if values.isEmpty {
                Text("N/A")
                    .frame(maxWidth: .infinity, alignment: .leading)
            } else {
                LazyVGrid(columns: columns, alignment: .leading, spacing: 8) {
                    ForEach(Array(values.enumerated()), id: \.offset) { _, value in
                        Button(value) {
                            onSelect(value)
                        }
                        .buttonStyle(.bordered)
                        .controlSize(.small)
                    }
                }
            }
        }
    }
}

struct ArtistMetadataRow: View {
    let label: String
    let values: [String]
    let favoriteArtists: [FavoriteArtist]
    let onSelect: (String) -> Void
    let onToggleFavorite: (String) -> Void

    private var favoriteArtistIDs: Set<String> {
        Set(favoriteArtists.map(\.id))
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            Text(LocalizedStringKey(label))
                .font(.caption)
                .foregroundStyle(.secondary)

            if values.isEmpty {
                Text("N/A")
                    .frame(maxWidth: .infinity, alignment: .leading)
            } else {
                VStack(spacing: 8) {
                    ForEach(Array(values.enumerated()), id: \.offset) { _, artist in
                        HStack(spacing: 10) {
                            Button(artist) {
                                onSelect(artist)
                            }
                            .buttonStyle(.bordered)

                            Spacer(minLength: 0)

                            Button {
                                onToggleFavorite(artist)
                            } label: {
                                Image(systemName: favoriteArtistIDs.contains(FavoriteArtist(name: artist).id) ? "star.fill" : "star")
                            }
                            .buttonStyle(.bordered)
                            .accessibilityLabel(favoriteArtistIDs.contains(FavoriteArtist(name: artist).id) ? "Unfavorite Artist" : "Favorite Artist")
                        }
                    }
                }
            }
        }
    }
}

struct DetailSearchOverlayRequest: Identifiable, Hashable {
    let title: String
    let query: String
    let caption: String

    var id: String {
        "\(caption):\(query)"
    }
}

struct DetailSearchOverlayView: View {
    @ObservedObject var store: AppStore
    let request: DetailSearchOverlayRequest

    @Environment(\.dismiss) private var dismiss

    @State private var summaries: [GallerySummary] = []
    @State private var isLoading = true

    var body: some View {
        Group {
            if isLoading {
                VStack(spacing: 12) {
                    ProgressView()
                    Text("Loading galleries...")
                        .foregroundStyle(.secondary)
                }
            } else if summaries.isEmpty {
                ContentUnavailableView(
                    "No Result",
                    systemImage: "rectangle.stack.badge.magnifyingglass",
                    description: Text("Adjust the query or sort mode and try again.")
                )
            } else {
                List {
                    VStack(alignment: .leading, spacing: 4) {
                        Text(LocalizedStringKey(request.caption))
                            .font(.caption.weight(.semibold))
                            .foregroundStyle(.secondary)

                        Text(request.query.replacingOccurrences(of: "_", with: " "))
                            .font(.footnote.monospaced())
                            .foregroundStyle(.secondary)
                    }
                    .listRowSeparator(.hidden)

                    ForEach(summaries) { summary in
                        NavigationLink {
                            GalleryDetailView(store: store, galleryID: summary.id)
                        } label: {
                            GalleryRowView(summary: summary)
                        }
                    }
                }
                .listStyle(.plain)
            }
        }
        .navigationTitle(request.title)
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            ToolbarItem(placement: .topBarTrailing) {
                Button("Done") {
                    dismiss()
                }
            }
        }
        .task(id: request.id) {
            await load()
        }
    }

    @MainActor
    private func load() async {
        isLoading = true
        defer { isLoading = false }

        do {
            summaries = try await store.searchPreviewSummaries(for: request.query, limit: 40)
        } catch {
            summaries = []
            store.errorMessage = error.localizedDescription
        }
    }
}

struct ReaderWorkspaceView: View {
    @ObservedObject var store: AppStore

    @State private var isReaderChromeHidden = false

    var body: some View {
        Group {
            if let selectedTab = store.selectedReaderTab {
                VStack(spacing: 0) {
                    if !isReaderChromeHidden {
                        ReaderTabStripView(store: store)
                    }

                    ReaderView(
                        store: store,
                        tabID: selectedTab.id,
                        isReaderChromeHidden: $isReaderChromeHidden
                    )
                    .id(selectedTab.id)
                }
            } else {
                ContentUnavailableView(
                    "No Reader Tabs",
                    systemImage: "square.on.square",
                    description: Text("Open a gallery to start reading.")
                )
            }
        }
        .background(Color.black.opacity(0.95))
        .onChange(of: store.selectedReaderTabID) { _, _ in
            if !store.hasReaderTabs {
                return
            }

            isReaderChromeHidden = false
        }
    }
}

struct ReaderTabStripView: View {
    @ObservedObject var store: AppStore

    var body: some View {
        ScrollView(.horizontal, showsIndicators: false) {
            HStack(spacing: 10) {
                ForEach(store.readerTabs) { tab in
                    ReaderTabChipView(
                        title: tab.displayTitle,
                        isSelected: store.selectedReaderTabID == tab.id || (store.selectedReaderTabID == nil && store.readerTabs.first?.id == tab.id),
                        onSelect: {
                            store.selectReaderTab(tab.id)
                        },
                        onClose: {
                            store.closeReaderTab(tab.id)
                        }
                    )
                }
            }
            .padding(.horizontal)
            .padding(.vertical, 10)
        }
        .background(Color.black.opacity(0.92))
    }
}

struct ReaderTabChipView: View {
    let title: String
    let isSelected: Bool
    let onSelect: () -> Void
    let onClose: () -> Void

    var body: some View {
        HStack(spacing: 8) {
            Button(action: onSelect) {
                Text(title)
                    .font(.subheadline.weight(.medium))
                    .lineLimit(1)
                    .frame(maxWidth: 180, alignment: .leading)
            }
            .buttonStyle(.plain)

            Button(action: onClose) {
                Image(systemName: "xmark")
                    .font(.caption.bold())
            }
            .buttonStyle(.plain)
        }
        .padding(.horizontal, 12)
        .padding(.vertical, 8)
        .foregroundStyle(isSelected ? Color.white : Color.white.opacity(0.80))
        .background(
            RoundedRectangle(cornerRadius: 14)
                .fill(isSelected ? Color.accentColor.opacity(0.9) : Color.white.opacity(0.08))
        )
    }
}

struct ReaderView: View {
    @ObservedObject var store: AppStore
    let tabID: UUID
    @Binding var isReaderChromeHidden: Bool

    @State private var isJumpPresented = false
    @State private var captureStart: Int?
    @State private var singlePage = false
    @State private var prefetchTask: Task<Void, Never>?

    private let service = HitomiService.shared

    var body: some View {
        Group {
            if isLoading {
                VStack(spacing: 12) {
                    ProgressView()
                    Text("Loading reader...")
                        .foregroundStyle(.secondary)
                }
            } else if let errorMessage {
                ContentUnavailableView(
                    "Gallery Not Available",
                    systemImage: "exclamationmark.triangle",
                    description: Text(errorMessage)
                )
            } else if pages.isEmpty {
                ContentUnavailableView(
                    "No Pages",
                    systemImage: "doc.text.image",
                    description: Text("The gallery has no readable pages.")
                )
            } else {
                if store.settings.readerMode == .verticalScroll && !singlePage {
                    GeometryReader { proxy in
                        VerticalReaderView(
                            pages: pages,
                            viewportWidth: proxy.size.width,
                            viewportHeight: proxy.size.height,
                            selectedPage: selectedPageBinding,
                            scrollPosition: scrollPositionBinding,
                            loadOrder: store.readerPrefetchPageOrder(for: tabID),
                            onToggleChrome: toggleReaderChrome
                        )
                    }
                } else {
                    GeometryReader { proxy in
                        PagedReaderView(
                            pages: pages,
                            viewportSize: proxy.size,
                            rightToLeft: singlePage ? store.settings.rightToLeft : usesRightToLeftPageOrder,
                            selectedPage: selectedPageBinding,
                            onToggleChrome: toggleReaderChrome
                        )
                    }
                }
            }
        }
        .safeAreaInset(edge: .bottom) {
            if !isReaderChromeHidden && !pages.isEmpty && store.settings.readerMode == .verticalScroll {
                Button {
                    singlePage.toggle()
                    if !singlePage { store.updateReaderTabScrollPosition(selectedPage, for: tabID) }
                } label: {
                    Label(singlePage ? "Continuous scroll" : "Single page",
                          systemImage: singlePage ? "rectangle.portrait.split.2x1" : "doc")
                        .padding(.horizontal, 10)
                }
                .buttonStyle(.bordered)
                .padding(.vertical, 4)
            }
        }
        .navigationTitle(session?.displayTitle ?? "Reader")
        .navigationBarTitleDisplayMode(.inline)
        .toolbar(.hidden, for: .tabBar)
        .toolbar(isReaderChromeHidden ? .hidden : .visible, for: .navigationBar)
        .toolbar {
            ToolbarItemGroup(placement: .topBarLeading) {
                Button {
                    store.dismissReaderWorkspace()
                } label: {
                    Image(systemName: "chevron.down")
                }

                Button {
                    guard galleryID > 0 else {
                        return
                    }

                    Task {
                        await store.toggleDownload(galleryID, info: info)
                    }
                } label: {
                    if let progress = store.downloadProgress(for: galleryID) {
                        ProgressView(value: progress)
                            .frame(width: 28)
                    } else {
                        Image(systemName: isDownloaded ? "trash" : "arrow.down.circle")
                    }
                }
            }

            ToolbarItemGroup(placement: .topBarTrailing) {
                Button {
                    captureStart = max(0, min(selectedPage, pages.count - 1))
                } label: {
                    Image(systemName: "crop")
                }
                .accessibilityLabel("Reader capture")
                .disabled(pages.isEmpty)
                Button {
                    if !pages.isEmpty {
                        isJumpPresented = true
                    }
                } label: {
                    Text("\(min(selectedPage + 1, max(pages.count, 1)))/\(max(pages.count, 1))")
                        .font(.subheadline.monospacedDigit())
                }
                .disabled(pages.isEmpty)
            }
        }
        .task(id: tabID) {
            await store.ensureReaderTabLoaded(tabID)
        }
        .task(id: prefetchTaskKey) {
            prefetchTask?.cancel()

            guard !pages.isEmpty else {
                return
            }

            guard store.settings.readerMode != .verticalScroll else {
                return
            }

            let urls = store.readerPrefetchURLs(for: tabID)
            prefetchTask = Task(priority: .utility) {
                await service.prefetchImageData(for: urls)
            }
        }
        .onDisappear {
            prefetchTask?.cancel()
        }
        .alert("Resume Reading", isPresented: resumePromptBinding) {
            Button(store.localized("Yes")) {
                store.restoreReaderTabToResumePage(tabID)
            }

            Button(store.localized("No"), role: .cancel) {
                store.restartReaderTabFromBeginning(tabID)
            }
        } message: {
            Text(store.localized("Resume reading from page %lld?", Int64((session?.resumePage ?? 0) + 1)))
        }
        .sheet(isPresented: Binding(get: { captureStart != nil }, set: { if !$0 { captureStart = nil } })) {
            if let captureStart {
                ReaderCaptureView(library: store.bookshelf, pages: pages, start: captureStart)
            }
        }
        .sheet(isPresented: $isJumpPresented) {
            IntegerInputSheet(
                title: "Go to Page",
                message: store.localized("Enter a page between 1 and %lld.", Int64(max(pages.count, 1))),
                placeholder: "\(selectedPage + 1)",
                actionTitle: "Go"
            ) { value in
                let bounded = min(max(value, 1), max(pages.count, 1))
                let targetPage = bounded - 1
                store.updateReaderTabPage(targetPage, for: tabID)

                if store.settings.readerMode == .verticalScroll {
                    store.updateReaderTabScrollPosition(targetPage, for: tabID)
                }
            }
        }
    }

    private var session: ReaderTabSession? {
        store.readerTab(id: tabID)
    }

    private var info: GalleryInfo? {
        session?.info ?? session?.seedInfo
    }

    private var galleryID: Int {
        session?.galleryID ?? 0
    }

    private var pages: [ReaderPage] {
        session?.pages ?? []
    }

    private var selectedPage: Int {
        session?.selectedPage ?? 0
    }

    private var isLoading: Bool {
        session?.isLoading ?? true
    }

    private var errorMessage: String? {
        session?.errorMessage
    }

    private var isDownloaded: Bool {
        store.downloadSummaries.contains { $0.id == galleryID }
    }

    private var usesRightToLeftPageOrder: Bool {
        store.settings.readerMode == .paged && store.settings.rightToLeft
    }

    private var selectedPageBinding: Binding<Int> {
        Binding(
            get: { session?.selectedPage ?? 0 },
            set: { newValue in
                store.updateReaderTabPage(newValue, for: tabID)
            }
        )
    }

    private var scrollPositionBinding: Binding<Int?> {
        Binding(
            get: { session?.scrollPosition },
            set: { newValue in
                store.updateReaderTabScrollPosition(newValue, for: tabID)
            }
        )
    }

    private var resumePromptBinding: Binding<Bool> {
        Binding(
            get: { session?.isResumePromptPresented == true },
            set: { _ in }
        )
    }

    private var prefetchTaskKey: String {
        let orderKey = store.readerPrefetchPageOrder(for: tabID).map(String.init).joined(separator: ",")
        return "\(tabID.uuidString)|\(store.settings.readerMode.rawValue)|\(orderKey)|\(pages.count)"
    }

    private func toggleReaderChrome() {
        withAnimation(.easeInOut(duration: 0.18)) {
            isReaderChromeHidden.toggle()
        }
    }
}

struct PagedReaderView: View {
    let pages: [ReaderPage]
    let viewportSize: CGSize
    let rightToLeft: Bool
    @Binding var selectedPage: Int
    let onToggleChrome: () -> Void

    var body: some View {
        TabView(selection: $selectedPage) {
            ForEach(pages) { page in
                PagedReaderPageView(page: page, viewportSize: viewportSize)
                    .scaleEffect(x: rightToLeft ? -1 : 1, y: 1)
                    .tag(page.id)
            }
        }
        .tabViewStyle(.page(indexDisplayMode: .automatic))
        .background(Color.black.opacity(0.95))
        .scaleEffect(x: rightToLeft ? -1 : 1, y: 1)
        .simultaneousGesture(
            TapGesture().onEnded {
                onToggleChrome()
            }
        )
    }
}

struct PagedReaderPageView: View {
    let page: ReaderPage
    let viewportSize: CGSize

    var body: some View {
        ZoomableReaderImage(
            page: page,
            baseWidth: viewportSize.width,
            containerHeight: viewportSize.height
        )
        .frame(maxWidth: .infinity, minHeight: viewportSize.height)
        .padding(.vertical, 20)
        .background(Color.black.opacity(0.95))
    }
}

struct VerticalReaderView: View {
    let pages: [ReaderPage]
    let viewportWidth: CGFloat
    let viewportHeight: CGFloat
    @Binding var selectedPage: Int
    @Binding var scrollPosition: Int?
    let loadOrder: [Int]
    let onToggleChrome: () -> Void

    var body: some View {
        ZoomableVerticalReaderScrollView(
            pages: pages,
            viewportWidth: viewportWidth,
            selectedPage: $selectedPage,
            scrollPosition: $scrollPosition,
            loadOrder: loadOrder,
            onToggleChrome: onToggleChrome
        )
        .background(Color.black.opacity(0.95))
    }
}

struct ZoomableVerticalReaderScrollView: UIViewRepresentable {
    let pages: [ReaderPage]
    let viewportWidth: CGFloat
    @Binding var selectedPage: Int
    @Binding var scrollPosition: Int?
    let loadOrder: [Int]
    let onToggleChrome: () -> Void

    func makeCoordinator() -> Coordinator {
        Coordinator(
            selectedPage: $selectedPage,
            scrollPosition: $scrollPosition,
            onToggleChrome: onToggleChrome
        )
    }

    func makeUIView(context: Context) -> UIScrollView {
        let scrollView = UIScrollView()
        scrollView.backgroundColor = .clear
        scrollView.showsVerticalScrollIndicator = false
        scrollView.showsHorizontalScrollIndicator = false
        scrollView.alwaysBounceVertical = true
        scrollView.alwaysBounceHorizontal = false
        scrollView.minimumZoomScale = 1
        scrollView.maximumZoomScale = 4
        scrollView.bouncesZoom = true
        scrollView.delegate = context.coordinator

        let contentView = context.coordinator.contentView
        scrollView.addSubview(contentView)

        NSLayoutConstraint.activate([
            contentView.leadingAnchor.constraint(equalTo: scrollView.contentLayoutGuide.leadingAnchor),
            contentView.trailingAnchor.constraint(equalTo: scrollView.contentLayoutGuide.trailingAnchor),
            contentView.topAnchor.constraint(equalTo: scrollView.contentLayoutGuide.topAnchor),
            contentView.bottomAnchor.constraint(equalTo: scrollView.contentLayoutGuide.bottomAnchor),
            contentView.widthAnchor.constraint(equalTo: scrollView.frameLayoutGuide.widthAnchor)
        ])

        let tapGesture = UITapGestureRecognizer(target: context.coordinator, action: #selector(Coordinator.handleSingleTap))
        tapGesture.cancelsTouchesInView = false
        scrollView.addGestureRecognizer(tapGesture)

        return scrollView
    }

    func updateUIView(_ uiView: UIScrollView, context: Context) {
        context.coordinator.selectedPageBinding = $selectedPage
        context.coordinator.scrollPositionBinding = $scrollPosition
        context.coordinator.onToggleChrome = onToggleChrome
        context.coordinator.update(pages: pages, viewportWidth: viewportWidth, scrollView: uiView, loadOrder: loadOrder)
    }

    final class Coordinator: NSObject, UIScrollViewDelegate {
        var selectedPageBinding: Binding<Int>
        var scrollPositionBinding: Binding<Int?>
        var onToggleChrome: () -> Void

        let contentView = UIView()
        private let stackView = UIStackView()
        private let service = HitomiService.shared

        private var imageViews: [UIImageView] = []
        private var pageIDs: [Int] = []
        private var basePageOffsets: [CGFloat] = []
        private var basePageHeights: [CGFloat] = []
        private var lastViewportWidth: CGFloat = 0
        private var lastLoadOrder: [Int] = []
        private var loadTask: Task<Void, Never>?
        private var priorityLoadTask: Task<Void, Never>?
        private var priorityLoadingPageID: Int?

        init(selectedPage: Binding<Int>, scrollPosition: Binding<Int?>, onToggleChrome: @escaping () -> Void) {
            selectedPageBinding = selectedPage
            scrollPositionBinding = scrollPosition
            self.onToggleChrome = onToggleChrome
            super.init()

            contentView.translatesAutoresizingMaskIntoConstraints = false
            contentView.backgroundColor = .clear

            stackView.translatesAutoresizingMaskIntoConstraints = false
            stackView.axis = .vertical
            stackView.spacing = 0
            stackView.alignment = .fill
            stackView.distribution = .fill

            contentView.addSubview(stackView)

            NSLayoutConstraint.activate([
                stackView.leadingAnchor.constraint(equalTo: contentView.leadingAnchor),
                stackView.trailingAnchor.constraint(equalTo: contentView.trailingAnchor),
                stackView.topAnchor.constraint(equalTo: contentView.topAnchor),
                stackView.bottomAnchor.constraint(equalTo: contentView.bottomAnchor)
            ])
        }

        deinit {
            loadTask?.cancel()
            priorityLoadTask?.cancel()
        }

        func update(pages: [ReaderPage], viewportWidth: CGFloat, scrollView: UIScrollView, loadOrder: [Int]) {
            let newIDs = pages.map(\.id)
            let safeWidth = max(viewportWidth, 1)
            let needsRebuild = newIDs != pageIDs || abs(lastViewportWidth - safeWidth) > 0.5
            let priorityPage = scrollPositionBinding.wrappedValue ?? selectedPageBinding.wrappedValue

            if needsRebuild {
                rebuildPages(pages, viewportWidth: safeWidth)
                beginLoadingImages(pages: pages, loadOrder: loadOrder)
                scrollView.setZoomScale(1, animated: false)
                scrollView.layoutIfNeeded()

                DispatchQueue.main.async { [weak self, weak scrollView] in
                    guard let self, let scrollView else {
                        return
                    }

                    if let targetPage = self.scrollPositionBinding.wrappedValue, pages.indices.contains(targetPage) {
                        self.scrollToPage(targetPage, in: scrollView, animated: false)
                        self.scrollPositionBinding.wrappedValue = nil
                    } else {
                        let targetPage = min(max(self.selectedPageBinding.wrappedValue, 0), max(pages.count - 1, 0))
                        self.scrollToPage(targetPage, in: scrollView, animated: false)
                    }

                    self.updateSelectedPage(for: scrollView)
                }
            } else if loadOrder != lastLoadOrder {
                beginLoadingImages(pages: pages, loadOrder: loadOrder)
            } else if let targetPage = scrollPositionBinding.wrappedValue, pages.indices.contains(targetPage) {
                scrollToPage(targetPage, in: scrollView, animated: true)
                DispatchQueue.main.async { [weak self] in
                    self?.scrollPositionBinding.wrappedValue = nil
                }
            } else {
                updateSelectedPage(for: scrollView)
            }

            ensurePriorityPageLoaded(pages: pages, priorityPage: priorityPage)
        }

        @objc
        func handleSingleTap() {
            onToggleChrome()
        }

        func viewForZooming(in scrollView: UIScrollView) -> UIView? {
            contentView
        }

        func scrollViewDidScroll(_ scrollView: UIScrollView) {
            updateSelectedPage(for: scrollView)
        }

        func scrollViewDidZoom(_ scrollView: UIScrollView) {
            updateSelectedPage(for: scrollView)
        }

        private func rebuildPages(_ pages: [ReaderPage], viewportWidth: CGFloat) {
            loadTask?.cancel()

            stackView.arrangedSubviews.forEach { subview in
                stackView.removeArrangedSubview(subview)
                subview.removeFromSuperview()
            }

            imageViews = []
            pageIDs = pages.map(\.id)
            lastViewportWidth = viewportWidth
            basePageHeights = pages.map { viewportWidth / max($0.aspectRatio, 0.01) }
            basePageOffsets = []

            var currentOffset: CGFloat = 0
            for height in basePageHeights {
                basePageOffsets.append(currentOffset)
                currentOffset += height
            }

            for (index, page) in pages.enumerated() {
                let container = UIView()
                container.translatesAutoresizingMaskIntoConstraints = false
                container.backgroundColor = UIColor.black

                let imageView = UIImageView()
                imageView.translatesAutoresizingMaskIntoConstraints = false
                imageView.contentMode = .scaleAspectFit
                imageView.clipsToBounds = true
                if let cachedImage = service.cachedImage(for: page.url) {
                    imageView.image = cachedImage
                    imageView.backgroundColor = .clear
                } else {
                    imageView.backgroundColor = UIColor(white: 0.08, alpha: 1)
                }

                container.addSubview(imageView)
                stackView.addArrangedSubview(container)

                NSLayoutConstraint.activate([
                    imageView.leadingAnchor.constraint(equalTo: container.leadingAnchor),
                    imageView.trailingAnchor.constraint(equalTo: container.trailingAnchor),
                    imageView.topAnchor.constraint(equalTo: container.topAnchor),
                    imageView.bottomAnchor.constraint(equalTo: container.bottomAnchor),
                    container.heightAnchor.constraint(equalTo: container.widthAnchor, multiplier: 1 / max(page.aspectRatio, 0.01))
                ])

                if index < imageViews.count {
                    imageViews[index] = imageView
                } else {
                    imageViews.append(imageView)
                }
            }

        }

        private func beginLoadingImages(pages: [ReaderPage], loadOrder: [Int]) {
            loadTask?.cancel()
            lastLoadOrder = loadOrder

            loadTask = Task { [weak self] in
                guard let self else {
                    return
                }

                for index in loadOrder where pages.indices.contains(index) {
                    guard !Task.isCancelled else {
                        return
                    }

                    if let cachedImage = service.cachedImage(for: pages[index].url) {
                        await MainActor.run {
                            guard index < self.imageViews.count else {
                                return
                            }

                            self.imageViews[index].image = cachedImage
                            self.imageViews[index].backgroundColor = .clear
                        }
                        continue
                    }

                    let alreadyLoaded = await MainActor.run {
                        index < self.imageViews.count && self.imageViews[index].image != nil
                    }
                    if alreadyLoaded {
                        continue
                    }

                    do {
                        let image = try await service.image(for: pages[index].url)
                        await MainActor.run {
                            guard index < self.imageViews.count else {
                                return
                            }

                            self.imageViews[index].image = image
                            self.imageViews[index].backgroundColor = .clear
                        }
                    } catch {
                        await MainActor.run {
                            guard index < self.imageViews.count else {
                                return
                            }

                            self.imageViews[index].backgroundColor = UIColor(white: 0.08, alpha: 1)
                        }
                    }
                }
            }
        }

        private func ensurePriorityPageLoaded(pages: [ReaderPage], priorityPage: Int) {
            guard pages.indices.contains(priorityPage),
                  priorityPage < imageViews.count else {
                priorityLoadTask?.cancel()
                priorityLoadTask = nil
                priorityLoadingPageID = nil
                return
            }

            if imageViews[priorityPage].image != nil {
                priorityLoadingPageID = nil
                return
            }

            let page = pages[priorityPage]
            if priorityLoadingPageID == page.id {
                return
            }

            priorityLoadTask?.cancel()
            priorityLoadingPageID = page.id

            priorityLoadTask = Task { [weak self] in
                guard let self else {
                    return
                }

                var loadedImage: UIImage?
                for attempt in 0..<2 {
                    guard !Task.isCancelled else {
                        return
                    }

                    do {
                        loadedImage = try await service.image(for: page.url)
                        break
                    } catch {
                        if attempt == 0 {
                            try? await Task.sleep(nanoseconds: 300_000_000)
                        }
                    }
                }

                await MainActor.run {
                    guard priorityPage < self.imageViews.count else {
                        self.priorityLoadingPageID = nil
                        return
                    }

                    if let loadedImage {
                        self.imageViews[priorityPage].image = loadedImage
                        self.imageViews[priorityPage].backgroundColor = .clear
                    } else {
                        self.imageViews[priorityPage].backgroundColor = UIColor(white: 0.08, alpha: 1)
                    }

                    self.priorityLoadingPageID = nil
                }
            }
        }

        private func scrollToPage(_ page: Int, in scrollView: UIScrollView, animated: Bool) {
            guard page >= 0, page < basePageOffsets.count else {
                return
            }

            let targetY = basePageOffsets[page] * scrollView.zoomScale
            let maxY = max(scrollView.contentSize.height - scrollView.bounds.height, 0)
            let boundedY = min(max(targetY, 0), maxY)
            scrollView.setContentOffset(CGPoint(x: scrollView.contentOffset.x, y: boundedY), animated: animated)
        }

        private func updateSelectedPage(for scrollView: UIScrollView) {
            guard !basePageOffsets.isEmpty else {
                return
            }

            let baseVisibleY = (scrollView.contentOffset.y + max(scrollView.bounds.height * 0.2, 1)) / max(scrollView.zoomScale, 1)
            var currentPage = 0

            for index in 0..<basePageOffsets.count {
                let nextOffset = index + 1 < basePageOffsets.count ? basePageOffsets[index + 1] : .greatestFiniteMagnitude
                if baseVisibleY >= basePageOffsets[index] && baseVisibleY < nextOffset {
                    currentPage = index
                    break
                }
            }

            if selectedPageBinding.wrappedValue != currentPage {
                DispatchQueue.main.async { [weak self] in
                    self?.selectedPageBinding.wrappedValue = currentPage
                }
            }
        }
    }
}

struct ZoomableReaderImage: View {
    let page: ReaderPage
    let baseWidth: CGFloat
    let containerHeight: CGFloat

    @State private var settledScale: CGFloat = 1
    @State private var settledOffset: CGSize = .zero
    @GestureState private var pinchScale: CGFloat = 1
    @GestureState private var dragOffset: CGSize = .zero

    private var clampedScale: CGFloat {
        min(max(settledScale * pinchScale, 1), 4)
    }

    private var safeBaseWidth: CGFloat {
        max(baseWidth, 1)
    }

    private var safeContainerHeight: CGFloat {
        max(containerHeight, 1)
    }

    private var liveOffset: CGSize {
        let proposed = CGSize(
            width: settledOffset.width + dragOffset.width,
            height: settledOffset.height + dragOffset.height
        )
        return clampedOffset(for: proposed, scale: clampedScale)
    }

    private var isZoomed: Bool {
        clampedScale > 1.01
    }

    var body: some View {
        imageContent
        .simultaneousGesture(magnificationGesture, including: .all)
        .simultaneousGesture(dragGesture, including: isZoomed ? .all : .none)
        .onTapGesture(count: 2) {
            if isZoomed {
                settledScale = 1
                settledOffset = .zero
            } else {
                settledScale = 2
            }
        }
    }

    private var imageContent: some View {
        RemoteImageView(
            url: page.url,
            contentMode: .fit,
            expandsToContainer: false
        )
        .aspectRatio(page.aspectRatio, contentMode: .fit)
        .frame(width: safeBaseWidth, height: safeContainerHeight)
        .scaleEffect(clampedScale)
        .offset(liveOffset)
        .frame(maxWidth: .infinity, minHeight: safeContainerHeight)
        .clipped()
        .contentShape(Rectangle())
        .frame(height: safeContainerHeight)
        .background(Color.black.opacity(0.95))
        .onChange(of: page.id) { _, _ in
            settledScale = 1
            settledOffset = .zero
        }
        .onChange(of: clampedScale) { _, newValue in
            if newValue <= 1.01 {
                settledOffset = .zero
            } else {
                settledOffset = clampedOffset(for: settledOffset, scale: newValue)
            }
        }
    }

    private var magnificationGesture: some Gesture {
        MagnificationGesture()
            .updating($pinchScale) { value, state, _ in
                state = value
            }
            .onEnded { value in
                settledScale = min(max(settledScale * value, 1), 4)
                settledOffset = clampedOffset(for: settledOffset, scale: settledScale)
            }
    }

    private var dragGesture: some Gesture {
        DragGesture()
            .updating($dragOffset) { value, state, _ in
                guard clampedScale > 1.01 else {
                    state = .zero
                    return
                }
                state = value.translation
            }
            .onEnded { value in
                guard clampedScale > 1.01 else {
                    settledOffset = .zero
                    return
                }
                let proposed = CGSize(
                    width: settledOffset.width + value.translation.width,
                    height: settledOffset.height + value.translation.height
                )
                settledOffset = clampedOffset(for: proposed, scale: clampedScale)
            }
    }

    private func clampedOffset(for proposed: CGSize, scale: CGFloat) -> CGSize {
        guard scale > 1.01 else {
            return .zero
        }

        let maxHorizontalOffset = max((safeBaseWidth * scale - safeBaseWidth) / 2, 0)
        let maxVerticalOffset = max((safeContainerHeight * scale - safeContainerHeight) / 2, 0)

        return CGSize(
            width: min(max(proposed.width, -maxHorizontalOffset), maxHorizontalOffset),
            height: min(max(proposed.height, -maxVerticalOffset), maxVerticalOffset)
        )
    }
}

struct SettingsView: View {
    @ObservedObject var store: AppStore
    @Environment(\.dismiss) private var dismiss
    @State private var isExportingFavorites = false
    @State private var isImportingFavorites = false
    @State private var pendingDataResetAction: PendingDataResetAction?

    var body: some View {
        Form {
            Section("App") {
                Picker(
                    "App Language",
                    selection: Binding(
                        get: { store.settings.appLanguage },
                        set: { store.updateAppLanguage($0) }
                    )
                ) {
                    ForEach(AppLanguage.allCases) { language in
                        Text(language.displayName).tag(language)
                    }
                }

                Toggle(
                    "Dark Mode",
                    isOn: Binding(
                        get: { store.settings.darkModeEnabled },
                        set: { store.updateDarkModeEnabled($0) }
                    )
                )
            }

            Section {
                NavigationLink {
                    DriveSyncSettings(store: store)
                } label: {
                    Label("Google Drive 동기화", systemImage: "icloud")
                }
            }

            Section("Search") {
                TextField(
                    "Default query",
                    text: Binding(
                        get: { store.settings.defaultQuery },
                        set: { store.updateDefaultQuery($0) }
                    )
                )

                Stepper(
                    store.localized("Galleries per page: %lld", Int64(store.settings.pageSize)),
                    value: Binding(
                        get: { store.settings.pageSize },
                        set: { newValue in
                            Task {
                                await store.updatePageSize(newValue)
                            }
                        }
                    ),
                    in: 10...100,
                    step: 5
                )

                VStack(alignment: .leading, spacing: 6) {
                    Text("Always Included Tags")
                        .font(.caption.weight(.semibold))

                    TextField(
                        "language:korean, female:ahegao",
                        text: Binding(
                            get: { store.settings.alwaysIncludedTags },
                            set: { store.updateAlwaysIncludedTags($0) }
                        )
                    )
                }

                VStack(alignment: .leading, spacing: 6) {
                    Text("Always Excluded Tags")
                        .font(.caption.weight(.semibold))

                    TextField(
                        "male:yaoi, tag:guro",
                        text: Binding(
                            get: { store.settings.alwaysExcludedTags },
                            set: { store.updateAlwaysExcludedTags($0) }
                        )
                    )
                }

                Text("Included tags and excluded tags both apply to search and algorithm results. Separate tags with commas.")
                    .font(.footnote)
                    .foregroundStyle(.secondary)
            }

            Section("Algorithm") {
                Stepper(
                    store.localized("Recommendations shown: %lld", Int64(store.settings.algorithmResultCount)),
                    value: Binding(
                        get: { store.settings.algorithmResultCount },
                        set: { store.updateAlgorithmResultCount($0) }
                    ),
                    in: 5...100,
                    step: 5
                )
            }

            Section("Favorites Backup") {
                Button("Backup Favorites") {
                    isExportingFavorites = true
                }

                Button("Restore Favorites") {
                    isImportingFavorites = true
                }

                Text("Export your favorites to JSON and restore them later.")
                    .font(.footnote)
                    .foregroundStyle(.secondary)
            }

            Section("Tab Bar Order") {
                Toggle("Show More tab", isOn: Binding(
                    get: { store.settings.showsMoreTab },
                    set: { store.updateShowsMoreTab($0) }
                ))
                if store.settings.showsMoreTab {
                    ForEach(store.settings.barTabs) { tab in
                        HStack {
                            Label(LocalizedStringKey(tab.titleKey), systemImage: tab.systemImage)
                            Spacer()
                            Button {
                                store.placeTab(tab, inMore: true)
                            } label: {
                                Label("Move to More", systemImage: "ellipsis.circle").font(.caption)
                            }
                            .buttonStyle(.borderless)
                            .accessibilityLabel("Move to More")
                            .disabled(store.settings.barTabs.count <= 1)
                        }
                    }
                    .onMove { store.moveTabGroup(from: $0, to: $1, inMore: false) }
                } else {
                    ForEach(store.orderedTabs) { tab in
                        Label(LocalizedStringKey(tab.titleKey), systemImage: tab.systemImage)
                    }
                    .onMove(perform: store.moveTab)
                }
            }

            if store.settings.showsMoreTab {
                Section("In More") {
                    ForEach(store.settings.overflowTabs) { tab in
                        HStack {
                            Label(LocalizedStringKey(tab.titleKey), systemImage: tab.systemImage)
                            Spacer()
                            Button {
                                store.placeTab(tab, inMore: false)
                            } label: {
                                Label("Move to tab bar", systemImage: "arrow.up.circle").font(.caption)
                            }
                            .buttonStyle(.borderless)
                            .accessibilityLabel("Move to tab bar")
                        }
                    }
                    .onMove { store.moveTabGroup(from: $0, to: $1, inMore: true) }
                }
            }

            Section("Reader") {
                Picker(
                    "Reader mode",
                    selection: Binding(
                        get: { store.settings.readerMode },
                        set: { store.updateReaderMode($0) }
                    )
                ) {
                    ForEach(ReaderMode.allCases) { mode in
                        Text(mode.rawValue).tag(mode)
                    }
                }

                Picker(
                    "Resume preload",
                    selection: Binding(
                        get: { store.settings.resumePrefetchMode },
                        set: { store.updateResumePrefetchMode($0) }
                    )
                ) {
                    ForEach(ReaderResumePrefetchMode.allCases) { mode in
                        Text(mode.rawValue).tag(mode)
                    }
                }

                Toggle(
                    "Right-to-left page order",
                    isOn: Binding(
                        get: { store.settings.rightToLeft },
                        set: { store.updateRightToLeft($0) }
                    )
                )
                .disabled(store.settings.readerMode == .verticalScroll)

                if store.settings.readerMode == .verticalScroll {
                    Text("Vertical Scroll mode does not use right-to-left page order.")
                        .font(.footnote)
                        .foregroundStyle(.secondary)
                }
            }

            Section("Privacy") {
                NavigationLink("App lock") { AppLockSettingsView() }

                Toggle(
                    "Hide app in recent apps",
                    isOn: Binding(
                        get: { store.settings.hideAppInSwitcher },
                        set: { store.updateHideAppInSwitcher($0) }
                    )
                )
            }

            Section("Data") {
                Button("Clear Downloads", role: .destructive) {
                    pendingDataResetAction = .downloads
                }

                Button("Clear History", role: .destructive) {
                    pendingDataResetAction = .history
                }

                Button("Clear Recent Searches", role: .destructive) {
                    pendingDataResetAction = .recentSearches
                }
            }
        }
        .navigationTitle("Settings")
        .toolbar {
            ToolbarItem(placement: .topBarLeading) {
                EditButton()
            }

            ToolbarItem(placement: .topBarTrailing) {
                Button("Done") {
                    dismiss()
                }
            }
        }
        .fileExporter(
            isPresented: $isExportingFavorites,
            document: FavoritesBackupDocument(
                favorites: store.favorites,
                favoriteLibrary: FavoriteLibrary(folders: store.favoriteFolders),
                favoriteArtists: store.favoriteArtists
            ),
            contentType: .json,
            defaultFilename: "UmtomiFavorites"
        ) { result in
            if case let .failure(error) = result {
                store.errorMessage = error.localizedDescription
            }
        }
        .fileImporter(isPresented: $isImportingFavorites, allowedContentTypes: [.json]) { result in
            switch result {
            case let .success(url):
                Task {
                    do {
                        let document = try FavoritesBackupDocument.load(from: url)
                        await store.restoreFavorites(
                            document.favorites,
                            library: document.favoriteLibrary,
                            artists: document.favoriteArtists
                        )
                    } catch {
                        await MainActor.run {
                            store.errorMessage = error.localizedDescription
                        }
                    }
                }
            case let .failure(error):
                store.errorMessage = error.localizedDescription
            }
        }
        .alert(
            store.localized(pendingDataResetAction?.titleKey ?? ""),
            isPresented: pendingDataResetAlertBinding,
            presenting: pendingDataResetAction
        ) { action in
            Button(store.localized("Cancel"), role: .cancel) {
                pendingDataResetAction = nil
            }

            Button(store.localized(action.confirmButtonKey), role: .destructive) {
                perform(action)
            }
        } message: { action in
            Text(store.localized(action.messageKey))
        }
    }

    private var pendingDataResetAlertBinding: Binding<Bool> {
        Binding(
            get: { pendingDataResetAction != nil },
            set: { isPresented in
                if !isPresented {
                    pendingDataResetAction = nil
                }
            }
        )
    }

    private func perform(_ action: PendingDataResetAction) {
        pendingDataResetAction = nil

        switch action {
        case .downloads:
            Task {
                await store.clearDownloads()
            }
        case .history:
            Task {
                await store.clearHistory()
            }
        case .recentSearches:
            store.clearRecentSearches()
        }
    }
}

private enum PendingDataResetAction: String, Identifiable {
    case downloads
    case history
    case recentSearches

    var id: String { rawValue }

    var titleKey: String {
        switch self {
        case .downloads:
            return "Delete Downloads?"
        case .history:
            return "Delete History?"
        case .recentSearches:
            return "Delete Recent Searches?"
        }
    }

    var messageKey: String {
        switch self {
        case .downloads:
            return "This will remove all downloaded galleries from this device."
        case .history:
            return "This will clear your reading history and saved resume pages."
        case .recentSearches:
            return "This will remove your recent search suggestions."
        }
    }

    var confirmButtonKey: String {
        switch self {
        case .downloads:
            return "Delete Downloads"
        case .history:
            return "Delete History"
        case .recentSearches:
            return "Delete Recent Searches"
        }
    }
}

struct OpenGallerySheet: View {
    @Environment(\.dismiss) private var dismiss

    let onOpen: (Int) -> Void

    @State private var galleryIDText = ""

    var body: some View {
        NavigationStack {
            Form {
                TextField("Gallery ID", text: $galleryIDText)
                    .keyboardType(.numberPad)
            }
            .navigationTitle("Open by ID")
            .toolbar {
                ToolbarItem(placement: .topBarLeading) {
                    Button("Cancel") {
                        dismiss()
                    }
                }

                ToolbarItem(placement: .topBarTrailing) {
                    Button("Open") {
                        let trimmed = galleryIDText.trimmingCharacters(in: .whitespacesAndNewlines)

                        guard let galleryID = Int(trimmed), galleryID > 0 else {
                            return
                        }

                        onOpen(galleryID)
                        dismiss()
                    }
                }
            }
        }
    }
}

struct IntegerInputSheet: View {
    let title: String
    let message: String
    let placeholder: String
    let actionTitle: String
    let onSubmit: (Int) -> Void

    @Environment(\.dismiss) private var dismiss
    @State private var valueText = ""

    var body: some View {
        NavigationStack {
            Form {
                Section {
                    Text(message)
                        .font(.subheadline)
                        .foregroundStyle(.secondary)

                    TextField(placeholder, text: $valueText)
                        .keyboardType(.numberPad)
                }
            }
            .navigationTitle(title)
            .toolbar {
                ToolbarItem(placement: .topBarLeading) {
                    Button("Cancel") {
                        dismiss()
                    }
                }

                ToolbarItem(placement: .topBarTrailing) {
                    Button(actionTitle) {
                        guard let value = Int(valueText) else {
                            return
                        }

                        dismiss()
                        onSubmit(value)
                    }
                }
            }
        }
    }
}

struct RemoteImageView: View {
    let url: URL?
    let contentMode: ContentMode
    let expandsToContainer: Bool

    @State private var phase: Phase = .loading

    private let service = HitomiService.shared

    enum Phase {
        case loading
        case success(Image)
        case failure
    }

    init(url: URL?, contentMode: ContentMode, expandsToContainer: Bool = true) {
        self.url = url
        self.contentMode = contentMode
        self.expandsToContainer = expandsToContainer

        if let url,
           let cachedImage = HitomiService.shared.cachedImage(for: url) {
            _phase = State(initialValue: .success(Image(uiImage: cachedImage)))
        } else if url == nil {
            _phase = State(initialValue: .failure)
        } else {
            _phase = State(initialValue: .loading)
        }
    }

    var body: some View {
        ZStack {
            switch phase {
            case .loading:
                RoundedRectangle(cornerRadius: 12)
                    .fill(Color.secondary.opacity(0.12))
                    .overlay {
                        ProgressView()
                    }

            case let .success(image):
                if expandsToContainer {
                    image
                        .resizable()
                        .aspectRatio(contentMode: contentMode)
                        .frame(maxWidth: .infinity, maxHeight: .infinity)
                } else {
                    image
                        .resizable()
                        .scaledToFit()
                }

            case .failure:
                RoundedRectangle(cornerRadius: 12)
                    .fill(Color.secondary.opacity(0.12))
                    .overlay {
                        Image(systemName: "photo")
                            .font(.title2)
                            .foregroundStyle(.secondary)
                    }
            }
        }
        .task(id: url) {
            await load()
        }
    }

    @MainActor
    private func load() async {
        guard let url else {
            phase = .failure
            return
        }

        if let cachedImage = service.cachedImage(for: url) {
            phase = .success(Image(uiImage: cachedImage))
            return
        }

        phase = .loading

        do {
            let image = try await service.image(for: url)
            phase = .success(Image(uiImage: image))
        } catch {
            phase = .failure
        }
    }
}
