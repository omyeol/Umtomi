import Foundation
import XCTest
@testable import Umtomi

final class TabBarSettingsTests: XCTestCase {
    func testOldSettingsKeepOrderAndShowFavoritesAndShelfDirectly() throws {
        let data = Data(#"{"tabOrder":["search","history","bookshelf","favorites","downloads","algorithm"]}"#.utf8)
        let settings = try JSONDecoder().decode(UmtomiSettings.self, from: data)
        XCTAssertFalse(settings.showsMoreTab)
        XCTAssertEqual(settings.barTabs, [.search, .history, .bookshelf, .favorites, .downloads, .algorithm])
        XCTAssertTrue(settings.overflowTabs.isEmpty)
    }

    func testItemsAssignedToMoreStayInConfiguredOrder() throws {
        var settings = UmtomiSettings()
        settings.tabOrder = [.favorites, .algorithm, .bookshelf, .search, .downloads, .history]
        settings.showsMoreTab = true
        settings.moreTabs = [.downloads, .algorithm]
        let decoded = try JSONDecoder().decode(UmtomiSettings.self, from: JSONEncoder().encode(settings))
        XCTAssertEqual(decoded.barTabs, [.favorites, .bookshelf, .search, .history])
        XCTAssertEqual(decoded.overflowTabs, [.algorithm, .downloads])
    }
}
