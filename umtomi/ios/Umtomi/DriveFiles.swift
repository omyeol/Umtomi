import Foundation

struct DriveRecord: Decodable, Sendable {
    let id: String
    let name: String
}

enum DriveNamespace: String, CaseIterable {
    case shelf = "omyaru-shelf-v1-"
    case chunk = "omyaru-shelf-chunk-v1-"
    case shelfKey = "omyaru-account-key-v1-"
    case user = "umtomi-userdata-v1-"
    case userKey = "umtomi-account-key-v1-"

    func identity(_ name: String) -> String? {
        guard name.hasPrefix(rawValue), name.hasSuffix(".json") else { return nil }
        let id = String(name.dropFirst(rawValue.count).dropLast(5))
        return SyncJSON.isCompactID(id) ? id : nil
    }
}

struct DriveFiles: Sendable {
    let accessToken: String
    static let scope = "https://www.googleapis.com/auth/drive.appdata"
    private static let limit = 4 * 1024 * 1024
    private static let session: URLSession = {
        let configuration = URLSessionConfiguration.ephemeral
        configuration.httpMaximumConnectionsPerHost = 8
        configuration.timeoutIntervalForRequest = 25
        configuration.timeoutIntervalForResource = 90
        configuration.urlCache = nil
        return URLSession(configuration: configuration, delegate: DriveRedirectBlocker(), delegateQueue: nil)
    }()

    func list(_ namespace: DriveNamespace, limit: Int = 10_000) async throws -> [DriveRecord] {
        struct Page: Decodable {
            let files: [DriveRecord]
            let nextPageToken: String?
        }
        var rows: [DriveRecord] = []
        var page: String?
        var seen = Set<String>()
        repeat {
            var query = [
                URLQueryItem(name: "spaces", value: "appDataFolder"),
                URLQueryItem(name: "fields", value: "nextPageToken,files(id,name)"),
                URLQueryItem(name: "q", value: "trashed = false and name contains '\(namespace.rawValue)'"),
                URLQueryItem(name: "pageSize", value: "1000")
            ]
            if let page { query.append(URLQueryItem(name: "pageToken", value: page)) }
            let result = try await request(url(path: "/drive/v3/files", query: query))
            let response = try JSONDecoder().decode(Page.self, from: result)
            rows += response.files.filter { namespace.identity($0.name) != nil }
            guard rows.count <= limit else { throw DriveSyncFailure.invalid }
            page = response.nextPageToken
            if let page, !seen.insert(page).inserted { throw DriveSyncFailure.invalid }
        } while page != nil
        return rows
    }

    func read(_ file: DriveRecord, in namespace: DriveNamespace) async throws -> Data {
        guard namespace.identity(file.name) != nil,
              file.id.range(of: "^[A-Za-z0-9_-]{1,512}$", options: .regularExpression) != nil else {
            throw DriveSyncFailure.invalid
        }
        return try await request(url(path: "/drive/v3/files/\(file.id)", query: [.init(name: "alt", value: "media")]))
    }

    func append(_ payload: Data, id: String, in namespace: DriveNamespace) async throws -> DriveRecord {
        guard SyncJSON.isCompactID(id), payload.count <= Self.limit else { throw DriveSyncFailure.invalid }
        let name = namespace.rawValue + id + ".json"
        let boundary = UUID().uuidString
        var body = Data("--\(boundary)\r\nContent-Type: application/json; charset=UTF-8\r\n\r\n".utf8)
        body += try SyncJSON.data(["name": name, "parents": ["appDataFolder"], "mimeType": "application/json"])
        body += Data("\r\n--\(boundary)\r\nContent-Type: application/json; charset=UTF-8\r\n\r\n".utf8)
        body += payload
        body += Data("\r\n--\(boundary)--\r\n".utf8)
        let response = try await request(url(path: "/upload/drive/v3/files", query: [
            .init(name: "uploadType", value: "multipart"), .init(name: "fields", value: "id")
        ]), body: body, contentType: "multipart/related; boundary=\(boundary)")
        guard let id = try SyncJSON.read(response)["id"] as? String else { throw DriveSyncFailure.invalid }
        return DriveRecord(id: id, name: name)
    }

    func keys(for namespace: DriveNamespace,
              progress: (@MainActor (Int, Int) -> Void)? = nil) async throws -> DriveKeyRing {
        let shelf = namespace == .shelfKey
        var keys = DriveKeyRing(format: shelf ? "privacy-browser-sync" : "umtomi-sync")
        let rows = try await list(namespace, limit: 128)
        for (index, row) in rows.enumerated() {
            await progress?(index, rows.count)
            guard let id = namespace.identity(row.name) else { throw DriveSyncFailure.invalid }
            try keys.add(SyncJSON.read(try await read(row, in: namespace)), id: id,
                         recordFormat: shelf ? "omyaru-account-key" : "umtomi-account-key")
            await progress?(index + 1, rows.count)
        }
        guard !keys.isEmpty else { throw DriveSyncFailure.key(shelf ? "공통 책장" : "Umtomi") }
        return keys
    }

    private func url(path: String, query: [URLQueryItem]) throws -> URL {
        var components = URLComponents()
        components.scheme = "https"
        components.host = "www.googleapis.com"
        components.path = path
        components.queryItems = query
        guard let url = components.url else { throw DriveSyncFailure.invalid }
        return url
    }

    private func request(_ url: URL, body: Data? = nil, contentType: String? = nil) async throws -> Data {
        try Task.checkCancellation()
        var request = URLRequest(url: url, timeoutInterval: 25)
        request.setValue("Bearer \(accessToken)", forHTTPHeaderField: "Authorization")
        if let body {
            request.httpMethod = "POST"
            request.setValue(contentType, forHTTPHeaderField: "Content-Type")
            request.httpBody = body
        }
        let (data, response) = try await Self.session.data(for: request)
        guard let http = response as? HTTPURLResponse else { throw DriveSyncFailure.invalid }
        guard (200..<300).contains(http.statusCode) else { throw DriveSyncFailure.http(http.statusCode) }
        guard data.count <= Self.limit else { throw DriveSyncFailure.invalid }
        try Task.checkCancellation()
        return data
    }
}

private final class DriveRedirectBlocker: NSObject, URLSessionTaskDelegate {
    func urlSession(_ session: URLSession, task: URLSessionTask, willPerformHTTPRedirection response: HTTPURLResponse,
                    newRequest request: URLRequest, completionHandler: @escaping (URLRequest?) -> Void) {
        completionHandler(nil)
    }
}
