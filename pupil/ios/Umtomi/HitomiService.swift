import CryptoKit
import Foundation
import UIKit

private struct SearchArgs {
    let area: String?
    let tag: String
    let language: String

    static func fromQuery(_ query: String) -> SearchArgs? {
        guard let separatorIndex = query.firstIndex(of: ":") else {
            return nil
        }

        let left = String(query[..<separatorIndex])
        let right = String(query[query.index(after: separatorIndex)...])

        switch left {
        case "male", "female":
            return SearchArgs(area: "tag", tag: query, language: "all")
        case "language":
            return SearchArgs(area: "all", tag: "index", language: right)
        default:
            return SearchArgs(area: left, tag: right, language: "all")
        }
    }
}

private struct Node {
    let keys: [[UInt8]]
    let datas: [(offset: Int64, length: Int32)]
    let subNodeAddresses: [Int64]
}

private struct BigEndianDataReader {
    private let data: Data
    private(set) var offset: Int = 0

    init(data: Data) {
        self.data = data
    }

    mutating func readInt32() throws -> Int32 {
        guard offset + 4 <= data.count else {
            throw HitomiServiceError.invalidBinaryData
        }

        let bytes = data[offset..<(offset + 4)]
        let value = bytes.reduce(UInt32(0)) { partial, byte in
            (partial << 8) | UInt32(byte)
        }
        offset += 4
        return Int32(bitPattern: value)
    }

    mutating func readInt64() throws -> Int64 {
        guard offset + 8 <= data.count else {
            throw HitomiServiceError.invalidBinaryData
        }

        let bytes = data[offset..<(offset + 8)]
        let value = bytes.reduce(UInt64(0)) { partial, byte in
            (partial << 8) | UInt64(byte)
        }
        offset += 8
        return Int64(bitPattern: value)
    }

    mutating func readBytes(count: Int) throws -> [UInt8] {
        guard offset + count <= data.count else {
            throw HitomiServiceError.invalidBinaryData
        }

        let bytes = [UInt8](data[offset..<(offset + count)])
        offset += count
        return bytes
    }
}

enum HitomiServiceError: LocalizedError {
    case invalidResponse
    case invalidGalleryInfo
    case invalidBinaryData
    case invalidImageData

    var errorDescription: String? {
        switch self {
        case .invalidResponse:
            return "The server returned an unexpected response."
        case .invalidGalleryInfo:
            return "The gallery metadata could not be decoded."
        case .invalidBinaryData:
            return "The Hitomi index payload was malformed."
        case .invalidImageData:
            return "The image payload could not be decoded."
        }
    }
}

private actor GGState {
    private var lastRefresh: Date?
    private var defaultValue: Int = 0
    private var mappedValues: [Int: Int] = [:]
    private var bValue: String = ""

    func m(for key: Int, fetcher: @escaping () async throws -> String) async throws -> Int {
        try await refreshIfNeeded(fetcher: fetcher)
        return mappedValues[key] ?? defaultValue
    }

    func b(fetcher: @escaping () async throws -> String) async throws -> String {
        try await refreshIfNeeded(fetcher: fetcher)
        return bValue
    }

    private func refreshIfNeeded(fetcher: @escaping () async throws -> String) async throws {
        if let lastRefresh, Date().timeIntervalSince(lastRefresh) < 60 {
            return
        }

        let source = try await fetcher()

        guard let defaultMatch = HitomiService.firstCapture(pattern: #"var o = (\d)"#, in: source),
              let defaultValue = Int(defaultMatch),
              let switchMatch = HitomiService.firstCapture(pattern: #"o = (\d); break;"#, in: source),
              let mappedDefault = Int(switchMatch),
              let bMatch = HitomiService.firstCapture(pattern: #"b: '(.+)'"#, in: source) else {
            throw HitomiServiceError.invalidBinaryData
        }

        self.defaultValue = defaultValue
        self.mappedValues = [:]
        self.bValue = bMatch

        let regex = try NSRegularExpression(pattern: #"case (\d+):"#)
        let range = NSRange(source.startIndex..., in: source)

        regex.matches(in: source, range: range).forEach { match in
            guard let groupRange = Range(match.range(at: 1), in: source),
                  let number = Int(source[groupRange]) else {
                return
            }

            mappedValues[number] = mappedDefault
        }

        self.lastRefresh = Date()
    }
}

private actor HitomiMetadataCache {
    private var tagIndexVersion: String?
    private var galleriesIndexVersion: String?
    private var galleryInfoCache: [Int: GalleryInfo] = [:]

    func cachedTagIndexVersion() -> String? { tagIndexVersion }
    func cachedGalleriesIndexVersion() -> String? { galleriesIndexVersion }
    func storeTagIndexVersion(_ version: String) { tagIndexVersion = version }
    func storeGalleriesIndexVersion(_ version: String) { galleriesIndexVersion = version }
    func galleryInfo(for id: Int) -> GalleryInfo? { galleryInfoCache[id] }
    func storeGalleryInfo(_ info: GalleryInfo, for id: Int) { galleryInfoCache[id] = info }
}

private actor HitomiImageTaskCache {
    private var dataTasks: [URL: Task<Data, Error>] = [:]

    func data(for url: URL, loader: @escaping @Sendable () async throws -> Data) async throws -> Data {
        if let existingTask = dataTasks[url] {
            return try await existingTask.value
        }

        let task = Task.detached(priority: .utility) {
            try await loader()
        }
        dataTasks[url] = task

        do {
            let data = try await task.value
            dataTasks[url] = nil
            return data
        } catch {
            dataTasks[url] = nil
            throw error
        }
    }
}

final class HitomiService: @unchecked Sendable {
    static let shared = HitomiService()

    private let session: URLSession
    private let decoder = JSONDecoder()
    private let metadataCache = HitomiMetadataCache()
    private let imageTaskCache = HitomiImageTaskCache()
    private let ggState = GGState()
    private let imageCache = NSCache<NSURL, UIImage>()
    private let imageDataCache = NSCache<NSURL, NSData>()
    private let retainedImageLock = NSLock()
    private var retainedImageCache: [URL: UIImage] = [:]

    private let protocolPrefix = "https:"
    private let domain = "ltn.gold-usergeneratedcontent.net"
    private let galleriesIndexDir = "galleriesindex"
    private let tagIndexDomain = "tagindex.hitomi.la"
    private let compressedNozomiPrefix = "n"
    private let maxNodeSize = 464
    private let treeWidth = 16
    private let userAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/94.0.4606.81 Safari/537.36"
    private let maxMetadataBatchSize = 12

    private init() {
        let configuration = URLSessionConfiguration.default
        configuration.timeoutIntervalForRequest = 60
        configuration.timeoutIntervalForResource = 120
        session = URLSession(configuration: configuration)
        imageCache.totalCostLimit = 256 * 1024 * 1024
        imageDataCache.totalCostLimit = 256 * 1024 * 1024
    }

    func search(query: String, sortMode: SortMode) async throws -> [Int] {
        let terms = query
            .trimmingCharacters(in: .whitespacesAndNewlines)
            .replacingOccurrences(of: #"^\?"#, with: "", options: .regularExpression)
            .lowercased()
            .split(whereSeparator: \.isWhitespace)
            .map { $0.replacingOccurrences(of: "_", with: " ") }

        let positiveTerms = terms.filter { !$0.hasPrefix("-") && !$0.isEmpty }
        let negativeTerms = terms.filter { $0.hasPrefix("-") }.map { String($0.dropFirst()) }

        let results: [Int]

        if let firstPositive = positiveTerms.first {
            var positiveResults = try await getGalleryIDsForQuery(firstPositive, sortMode: sortMode)

            for term in positiveTerms.dropFirst() {
                let newResults = try await getGalleryIDsForQuery(term, sortMode: sortMode)
                positiveResults = filterPositive(results: positiveResults, with: newResults)
            }

            results = positiveResults
        } else {
            results = try await getGalleryIDsFromNozomi(
                args: SearchArgs(area: "all", tag: "index", language: "all"),
                sortMode: sortMode
            )
        }

        var filteredResults = results

        for term in negativeTerms {
            let excludedResults = try await getGalleryIDsForQuery(term, sortMode: sortMode)
            filteredResults = filterNegative(results: filteredResults, with: excludedResults)
        }

        return sortMode == .random ? filteredResults.shuffled() : filteredResults
    }

    func suggestions(for query: String) async throws -> [SearchSuggestion] {
        let normalized = query.replacingOccurrences(of: "_", with: " ")
        guard !normalized.isEmpty else {
            return []
        }

        var field = "global"
        var term = normalized

        if let separatorIndex = normalized.firstIndex(of: ":") {
            field = String(normalized[..<separatorIndex])
            term = String(normalized[normalized.index(after: separatorIndex)...])
        }

        let components = term.map(Self.encodeSearchCharacter(_:))
        let suffix = components.isEmpty ? "" : "/" + components.joined(separator: "/")
        let url = try urlFromString("https://\(tagIndexDomain)/\(field)\(suffix).json")

        let data = try await requestData(url: url)
        let payload = try JSONSerialization.jsonObject(with: data)
        let rows = payload as? [[Any]] ?? []

        return rows.compactMap { row in
            guard row.count >= 3 else {
                return nil
            }

            let term = row[0] as? String ?? ""
            let count = row[1] as? Int ?? (row[1] as? NSNumber)?.intValue ?? 0
            let namespace = row[2] as? String ?? ""

            return SearchSuggestion(term: term, count: count, namespace: namespace)
        }
    }

    func galleryInfo(id: Int) async throws -> GalleryInfo {
        if let cached = await metadataCache.galleryInfo(for: id) {
            return cached
        }

        let url = try urlFromString("\(protocolPrefix)//\(domain)/galleries/\(id).js")
        let source = try await requestText(url: url)
        let jsonSource = source.replacingOccurrences(of: "var galleryinfo = ", with: "")

        guard let data = jsonSource.data(using: .utf8) else {
            throw HitomiServiceError.invalidGalleryInfo
        }

        let info = try decoder.decode(GalleryInfo.self, from: data)
        await metadataCache.storeGalleryInfo(info, for: id)
        return info
    }

    func gallerySummary(id: Int) async throws -> GallerySummary {
        let info = try await galleryInfo(id: id)
        let resolvedCoverURL: URL?

        do {
            resolvedCoverURL = try await coverURL(for: info)
        } catch {
            resolvedCoverURL = nil
        }

        return GallerySummary(
            id: id,
            title: info.title,
            coverURL: resolvedCoverURL,
            artists: info.primaryArtists,
            groups: info.primaryGroups,
            series: info.primarySeries,
            type: info.type,
            language: info.displayLanguage,
            pageCount: info.pageCount
        )
    }

    func gallerySummaries(ids: [Int]) async -> [GallerySummary] {
        await fetchOrderedMetadata(ids: ids) { [self] id in
            do {
                return try await gallerySummary(id: id)
            } catch {
                return nil
            }
        }
    }

    func galleryInfos(ids: [Int]) async -> [GalleryInfo] {
        await fetchOrderedMetadata(ids: ids) { [self] id in
            do {
                return try await galleryInfo(id: id)
            } catch {
                return nil
            }
        }
    }

    private func fetchOrderedMetadata<Value>(
        ids: [Int],
        operation: @escaping @Sendable (Int) async -> Value?
    ) async -> [Value] {
        guard !ids.isEmpty else {
            return []
        }

        var ordered = Array<Value?>(repeating: nil, count: ids.count)
        var startIndex = 0

        while startIndex < ids.count {
            let baseIndex = startIndex
            let endIndex = min(baseIndex + maxMetadataBatchSize, ids.count)
            let batch = Array(ids[baseIndex..<endIndex])

            await withTaskGroup(of: (Int, Value?).self) { group in
                for (offset, id) in batch.enumerated() {
                    let resultIndex = baseIndex + offset
                    group.addTask {
                        (resultIndex, await operation(id))
                    }
                }

                for await (resultIndex, value) in group {
                    ordered[resultIndex] = value
                }
            }

            startIndex = endIndex
        }

        return ordered.compactMap { $0 }
    }

    func coverURL(for info: GalleryInfo) async throws -> URL? {
        guard let firstFile = info.files.first else {
            return nil
        }

        return try await urlFromUrlFromHash(
            galleryID: info.numericID,
            image: firstFile,
            dir: "webpbigtn",
            ext: "webp",
            base: "tn"
        )
    }

    func readerPages(for info: GalleryInfo) async throws -> [ReaderPage] {
        var pages: [ReaderPage] = []
        pages.reserveCapacity(info.files.count)

        for (index, file) in info.files.enumerated() {
            let url = try await imageURL(
                galleryID: info.numericID,
                image: file,
                preferOriginal: false
            )
            pages.append(
                ReaderPage(
                    id: index,
                    url: url,
                    width: file.width,
                    height: file.height
                )
            )
        }

        return pages
    }

    func image(for url: URL) async throws -> UIImage {
        if let cached = cachedImage(for: url) {
            return cached
        }

        let data = try await imageData(for: url)
        let image = try Self.decodedImage(from: data)

        storeImage(image, for: url, cost: data.count)
        return image
    }

    func cachedImage(for url: URL) -> UIImage? {
        if let retained = retainedImage(for: url) {
            return retained
        }

        if let cached = imageCache.object(forKey: url as NSURL) {
            storeRetainedImage(cached, for: url)
            return cached
        }

        return nil
    }

    func imageData(for url: URL) async throws -> Data {
        if url.isFileURL {
            return try Data(contentsOf: url)
        }

        if let cachedData = imageDataCache.object(forKey: url as NSURL) {
            return Data(referencing: cachedData)
        }

        let data = try await imageTaskCache.data(for: url) { [self] in
            try await requestData(url: url)
        }
        imageDataCache.setObject(data as NSData, forKey: url as NSURL, cost: data.count)
        return data
    }

    func clearCachedImages(for urls: [URL]) {
        retainedImageLock.lock()
        urls.forEach { retainedImageCache.removeValue(forKey: $0) }
        retainedImageLock.unlock()

        for url in urls {
            imageCache.removeObject(forKey: url as NSURL)
            imageDataCache.removeObject(forKey: url as NSURL)
        }
    }

    func prefetchImageData(for urls: [URL]) async {
        var seen = Set<URL>()
        let uniqueURLs = urls.filter { seen.insert($0).inserted }
        guard !uniqueURLs.isEmpty else {
            return
        }

        for url in uniqueURLs {
            if Task.isCancelled {
                return
            }

            _ = try? await image(for: url)
        }
    }

    private func retainedImage(for url: URL) -> UIImage? {
        retainedImageLock.lock()
        defer { retainedImageLock.unlock() }
        return retainedImageCache[url]
    }

    private func storeRetainedImage(_ image: UIImage, for url: URL) {
        retainedImageLock.lock()
        retainedImageCache[url] = image
        retainedImageLock.unlock()
    }

    private func storeImage(_ image: UIImage, for url: URL, cost: Int) {
        storeRetainedImage(image, for: url)
        imageCache.setObject(image, forKey: url as NSURL, cost: cost)
    }

    private static func decodedImage(from data: Data) throws -> UIImage {
        guard let image = UIImage(data: data) else {
            throw HitomiServiceError.invalidImageData
        }

        let size = image.size
        guard size.width > 0, size.height > 0 else {
            return image
        }

        let format = UIGraphicsImageRendererFormat()
        format.scale = image.scale
        format.opaque = false

        let renderer = UIGraphicsImageRenderer(size: size, format: format)
        return renderer.image { _ in
            image.draw(in: CGRect(origin: .zero, size: size))
        }
    }

    private func getGalleryIDsForQuery(_ query: String, sortMode: SortMode) async throws -> [Int] {
        let sanitizedQuery = query.replacingOccurrences(of: "_", with: " ")

        if let args = SearchArgs.fromQuery(sanitizedQuery) {
            return try await getGalleryIDsFromNozomi(args: args, sortMode: sortMode)
        }

        let key = hashTerm(sanitizedQuery)
        let node = try await getNodeAtAddress(field: "galleries", address: 0)

        guard let data = try await bSearch(field: "galleries", key: key, node: node) else {
            return []
        }

        return try await getGalleryIDsFromData(data)
    }

    private func getGalleryIDsFromNozomi(args: SearchArgs, sortMode: SortMode) async throws -> [Int] {
        let nozomiAddress = nozomiAddress(from: args, sortMode: sortMode)
        let url = try urlFromString(nozomiAddress)
        let bytes = try await requestData(url: url)

        var reader = BigEndianDataReader(data: bytes)
        var results: [Int] = []

        while reader.offset < bytes.count {
            results.append(Int(try reader.readInt32()))
        }

        return orderedUnique(results)
    }

    private func getGalleryIDsFromData(_ data: (offset: Int64, length: Int32)) async throws -> [Int] {
        let version = try await galleriesIndexVersion()
        let url = try urlFromString("\(protocolPrefix)//\(domain)/galleriesindex/galleries.\(version).data")
        let payload = try await requestData(url: url, range: data.offset...(data.offset + Int64(data.length) - 1))

        var reader = BigEndianDataReader(data: payload)
        let numberOfGalleryIDs = Int(try reader.readInt32())

        guard numberOfGalleryIDs > 0,
              numberOfGalleryIDs <= 10_000_000,
              payload.count == numberOfGalleryIDs * 4 + 4 else {
            throw HitomiServiceError.invalidBinaryData
        }

        var results: [Int] = []
        results.reserveCapacity(numberOfGalleryIDs)

        for _ in 0..<numberOfGalleryIDs {
            results.append(Int(try reader.readInt32()))
        }

        return orderedUnique(results)
    }

    private func tagIndexVersion() async throws -> String {
        if let cached = await metadataCache.cachedTagIndexVersion() {
            return cached
        }

        let url = try urlFromString("\(protocolPrefix)//\(domain)/tagindex/version?_=\(Date().timeIntervalSince1970)")
        let version = try await requestText(url: url)
        await metadataCache.storeTagIndexVersion(version)
        return version
    }

    private func galleriesIndexVersion() async throws -> String {
        if let cached = await metadataCache.cachedGalleriesIndexVersion() {
            return cached
        }

        let url = try urlFromString("\(protocolPrefix)//\(domain)/galleriesindex/version?_=\(Date().timeIntervalSince1970)")
        let version = try await requestText(url: url)
        await metadataCache.storeGalleriesIndexVersion(version)
        return version
    }

    private func getNodeAtAddress(field: String, address: Int64) async throws -> Node {
        let version: String
        let path: String

        if field == "galleries" || field == "languages" || field == "nozomiurl" {
            version = try await galleriesIndexVersion()
            path = "galleriesindex/\(field).\(version).index"
        } else {
            version = try await tagIndexVersion()
            path = "tagindex/\(field).\(version).index"
        }

        let url = try urlFromString("\(protocolPrefix)//\(domain)/\(path)")
        let payload = try await requestData(url: url, range: address...(address + Int64(maxNodeSize) - 1))
        return try decodeNode(from: payload)
    }

    private func decodeNode(from data: Data) throws -> Node {
        var reader = BigEndianDataReader(data: data)
        let numberOfKeys = Int(try reader.readInt32())
        var keys: [[UInt8]] = []
        keys.reserveCapacity(numberOfKeys)

        for _ in 0..<numberOfKeys {
            let keySize = Int(try reader.readInt32())
            guard keySize > 0, keySize <= 32 else {
                throw HitomiServiceError.invalidBinaryData
            }

            keys.append(try reader.readBytes(count: keySize))
        }

        let numberOfDatas = Int(try reader.readInt32())
        var datas: [(offset: Int64, length: Int32)] = []
        datas.reserveCapacity(numberOfDatas)

        for _ in 0..<numberOfDatas {
            let offset = try reader.readInt64()
            let length = try reader.readInt32()
            datas.append((offset: offset, length: length))
        }

        var subNodeAddresses: [Int64] = []
        subNodeAddresses.reserveCapacity(treeWidth + 1)

        for _ in 0...treeWidth {
            subNodeAddresses.append(try reader.readInt64())
        }

        return Node(keys: keys, datas: datas, subNodeAddresses: subNodeAddresses)
    }

    private func bSearch(field: String, key: [UInt8], node: Node) async throws -> (offset: Int64, length: Int32)? {
        guard !node.keys.isEmpty else {
            return nil
        }

        let located = locateKey(key: key, in: node)

        if located.found {
            return node.datas[located.index]
        }

        let isLeaf = node.subNodeAddresses.allSatisfy { $0 == 0 }
        if isLeaf {
            return nil
        }

        let nextNode = try await getNodeAtAddress(field: field, address: node.subNodeAddresses[located.index])
        return try await bSearch(field: field, key: key, node: nextNode)
    }

    private func locateKey(key: [UInt8], in node: Node) -> (found: Bool, index: Int) {
        for (index, nodeKey) in node.keys.enumerated() {
            let comparison = compare(lhs: key, rhs: nodeKey)

            if comparison <= 0 {
                return (comparison == 0, index)
            }
        }

        return (false, node.keys.count)
    }

    private func compare(lhs: [UInt8], rhs: [UInt8]) -> Int {
        let count = min(lhs.count, rhs.count)

        for index in 0..<count {
            if lhs[index] < rhs[index] { return -1 }
            if lhs[index] > rhs[index] { return 1 }
        }

        return 0
    }

    private func hashTerm(_ term: String) -> [UInt8] {
        let digest = SHA256.hash(data: Data(term.utf8))
        return Array(digest.prefix(4))
    }

    private func filterPositive(results: [Int], with newResults: [Int]) -> [Int] {
        let membership = Set(newResults)
        return results.filter { membership.contains($0) }
    }

    private func filterNegative(results: [Int], with excludedResults: [Int]) -> [Int] {
        guard !results.isEmpty, !excludedResults.isEmpty else {
            return results
        }

        let membership = Set(excludedResults)
        return results.filter { !membership.contains($0) }
    }

    private func orderedUnique(_ values: [Int]) -> [Int] {
        var seen = Set<Int>()
        var ordered: [Int] = []
        ordered.reserveCapacity(values.count)

        for value in values where seen.insert(value).inserted {
            ordered.append(value)
        }

        return ordered
    }

    private func nozomiAddress(from args: SearchArgs, sortMode: SortMode) -> String {
        if sortMode != .dateAdded && sortMode != .random {
            if args.area == "all" {
                return "\(protocolPrefix)//\(domain)/\(compressedNozomiPrefix)/\(sortMode.orderBy)/\(sortMode.orderByKey)-\(args.language).nozomi"
            }

            return "\(protocolPrefix)//\(domain)/\(compressedNozomiPrefix)/\(args.area ?? "all")/\(sortMode.orderBy)/\(sortMode.orderByKey)/\(args.tag)-\(args.language).nozomi"
        }

        if args.area == "all" {
            return "\(protocolPrefix)//\(domain)/\(compressedNozomiPrefix)/\(args.tag)-\(args.language).nozomi"
        }

        return "\(protocolPrefix)//\(domain)/\(compressedNozomiPrefix)/\(args.area ?? "all")/\(args.tag)-\(args.language).nozomi"
    }

    private func imageURL(galleryID: Int, image: GalleryFile, preferOriginal: Bool) async throws -> URL {
        try await urlFromUrlFromHash(
            galleryID: galleryID,
            image: image,
            dir: preferOriginal ? nil : "webp",
            ext: nil,
            base: nil
        )
    }

    private func urlFromUrlFromHash(
        galleryID: Int,
        image: GalleryFile,
        dir: String?,
        ext: String?,
        base: String?
    ) async throws -> URL {
        let sourceURL: URL

        if base == "tn" {
            let extensionValue = ext ?? (image.name as NSString).pathExtension
            sourceURL = try urlFromString("https://a.gold-usergeneratedcontent.net/\(dir ?? "")/\(realFullPath(from: image.hash)).\(extensionValue)")
        } else {
            sourceURL = try await urlFromHash(galleryID: galleryID, image: image, dir: dir, ext: ext)
        }

        return try await urlFromURL(sourceURL.absoluteString, base: base, dir: dir)
    }

    private func urlFromHash(galleryID: Int, image: GalleryFile, dir: String?, ext: String?) async throws -> URL {
        let resolvedExtension = ext ?? dir ?? (image.name as NSString).pathExtension
        let fullPath = try await fullPath(from: image.hash)

        var urlString = "https://a.gold-usergeneratedcontent.net/"
        if let dir, dir != "webp", dir != "avif" {
            urlString += "\(dir)/"
        }
        urlString += "\(fullPath).\(resolvedExtension)"

        return try urlFromString(urlString)
    }

    private func urlFromURL(_ urlString: String, base: String?, dir: String?) async throws -> URL {
        let subdomain = try await subdomain(from: urlString, base: base, dir: dir)
        let replaced = urlString.replacingOccurrences(
            of: #"//..?\.(?:gold-usergeneratedcontent\.net|hitomi\.la)/"#,
            with: "//\(subdomain).gold-usergeneratedcontent.net/",
            options: .regularExpression
        )
        return try urlFromString(replaced)
    }

    private func subdomain(from urlString: String, base: String?, dir: String?) async throws -> String {
        var result = ""

        if base == nil {
            if dir == "webp" {
                result = "w"
            } else if dir == "avif" {
                result = "a"
            }
        }

        let pattern = #"/[0-9a-f]{61}([0-9a-f]{2})([0-9a-f])"#
        guard let regex = try? NSRegularExpression(pattern: pattern),
              let match = regex.firstMatch(in: urlString, range: NSRange(urlString.startIndex..., in: urlString)),
              let firstRange = Range(match.range(at: 1), in: urlString),
              let secondRange = Range(match.range(at: 2), in: urlString) else {
            return result
        }

        let hex = "\(urlString[secondRange])\(urlString[firstRange])"
        guard let key = Int(hex, radix: 16) else {
            return result
        }

        let mapped = try await ggState.m(for: key) { [self] in
            let url = try urlFromString("https://ltn.gold-usergeneratedcontent.net/gg.js")
            return try await requestText(url: url)
        }

        if let base, !base.isEmpty {
            if let scalar = UnicodeScalar(97 + mapped) {
                result = "\(Character(scalar))\(base)"
            } else {
                result = "a\(base)"
            }
        } else {
            result += String(1 + mapped)
        }

        return result
    }

    private func fullPath(from hash: String) async throws -> String {
        let b = try await ggState.b { [self] in
            let url = try urlFromString("https://ltn.gold-usergeneratedcontent.net/gg.js")
            return try await requestText(url: url)
        }
        return "\(b)\(sValue(from: hash))/\(hash)"
    }

    private func realFullPath(from hash: String) -> String {
        let suffix = String(hash.suffix(3))
        guard suffix.count == 3 else {
            return hash
        }

        let last = String(suffix.suffix(1))
        let leading = String(suffix.prefix(2))
        return "\(last)/\(leading)/\(hash)"
    }

    private func sValue(from hash: String) -> String {
        let last = String(hash.suffix(1))
        let leading = String(hash.dropLast().suffix(2))
        let hex = last + leading
        return String(Int(hex, radix: 16) ?? 0)
    }

    private func requestData(url: URL, range: ClosedRange<Int64>? = nil) async throws -> Data {
        var request = URLRequest(url: url)
        request.setValue(userAgent, forHTTPHeaderField: "User-Agent")
        request.setValue("https://hitomi.la/", forHTTPHeaderField: "Referer")

        if let range {
            request.setValue("bytes=\(range.lowerBound)-\(range.upperBound)", forHTTPHeaderField: "Range")
        }

        let (data, response) = try await session.data(for: request)

        guard let httpResponse = response as? HTTPURLResponse,
              (200...299).contains(httpResponse.statusCode) else {
            throw HitomiServiceError.invalidResponse
        }

        return data
    }

    private func requestText(url: URL, range: ClosedRange<Int64>? = nil) async throws -> String {
        let data = try await requestData(url: url, range: range)
        return String(decoding: data, as: UTF8.self)
    }

    private func urlFromString(_ string: String) throws -> URL {
        guard let url = URL(string: string) else {
            throw HitomiServiceError.invalidResponse
        }

        return url
    }

    private static func encodeSearchCharacter(_ character: Character) -> String {
        switch character {
        case " ":
            return "_"
        case "/":
            return "slash"
        case ".":
            return "dot"
        default:
            return String(character)
        }
    }

    static func firstCapture(pattern: String, in text: String) -> String? {
        guard let regex = try? NSRegularExpression(pattern: pattern),
              let match = regex.firstMatch(in: text, range: NSRange(text.startIndex..., in: text)),
              let range = Range(match.range(at: 1), in: text) else {
            return nil
        }

        return String(text[range])
    }
}
