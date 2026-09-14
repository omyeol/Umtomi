import XCTest
@testable import Umtomi

final class DeepLinkParserTests: XCTestCase {
    func testHashaInReaderLinkParsesGalleryID() {
        let url = URL(string: "https://a.hasha.in/reader/123456")!

        XCTAssertEqual(DeepLinkParser.extractGalleryID(from: url), 123456)
    }

    func testHitomiGalleryLinkParsesGalleryID() {
        let url = URL(string: "https://hitomi.la/galleries/123456.html")!

        XCTAssertEqual(DeepLinkParser.extractGalleryID(from: url), 123456)
    }

    func testEHentaiLinkParsesGalleryID() {
        let url = URL(string: "https://e-hentai.org/g/987654/abcdef")!

        XCTAssertEqual(DeepLinkParser.extractGalleryID(from: url), 987654)
    }

    func testUnsupportedLinkReturnsNil() {
        let url = URL(string: "https://example.com/gallery/123")!

        XCTAssertNil(DeepLinkParser.extractGalleryID(from: url))
        XCTAssertNil(DeepLinkParser.galleryRoute(from: url))
    }

    func testSuggestionQueryFormatting() {
        let global = SearchSuggestion(term: "full color", count: 10, namespace: "global")
        let female = SearchSuggestion(term: "big breasts", count: 5, namespace: "female")
        let language = SearchSuggestion(term: "korean", count: 3, namespace: "language")

        XCTAssertEqual(global.query, "full_color")
        XCTAssertEqual(female.query, "female:big_breasts")
        XCTAssertEqual(language.query, "language:korean")
    }

    func testSortModeMappingsMatchAndroidLogic() {
        XCTAssertEqual(SortMode.dateAdded.orderBy, "date")
        XCTAssertEqual(SortMode.datePublished.orderByKey, "published")
        XCTAssertEqual(SortMode.popularWeek.orderBy, "popular")
        XCTAssertEqual(SortMode.popularYear.orderByKey, "year")
        XCTAssertEqual(SortMode.random.orderByKey, "added")
    }
}
