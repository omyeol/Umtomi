import Foundation
import XCTest
@testable import Umtomi

final class BookshelfCompatibilityTests: XCTestCase {
    func testExistingCatalogWithoutParentKeepsShelfAndPhotoIdentity() throws {
        let data = Data("""
        {"selected":"11111111-1111-1111-1111-111111111111","shelves":[
          {"id":"11111111-1111-1111-1111-111111111111","name":"My shelf","note":"Note","columns":2,
           "photos":[{"id":"22222222-2222-2222-2222-222222222222","filename":"image19.png","width":40,"height":90}]}
        ]}
        """.utf8)
        let catalog = try JSONDecoder().decode(ShelfCatalog.self, from: data)
        let shelf = try XCTUnwrap(catalog.shelves.first)
        XCTAssertEqual(shelf.id, catalog.selected)
        XCTAssertNil(shelf.parentID)
        XCTAssertEqual(shelf.photos.map(\.id), [UUID(uuidString: "22222222-2222-2222-2222-222222222222")!])
        XCTAssertEqual(shelf.photos.map(\.filename), ["image19.png"])
    }
}
