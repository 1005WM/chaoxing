import XCTest
@testable import ChaoxingCore

final class AppUpdateVersionTests: XCTestCase {
    func testSemanticVersionOrdering() throws {
        XCTAssertEqual(try AppUpdateVersion("v1.2.0"), try AppUpdateVersion("1.2.0+build.2"))
        XCTAssertGreaterThan(try AppUpdateVersion("1.10.0"), try AppUpdateVersion("1.9.9"))
        XCTAssertGreaterThan(try AppUpdateVersion("2.0.0"), try AppUpdateVersion("2.0.0-rc.10"))
        XCTAssertGreaterThan(try AppUpdateVersion("2.0.0-rc.10"), try AppUpdateVersion("2.0.0-rc.2"))
        XCTAssertThrowsError(try AppUpdateVersion("1.2.0-01"))
        XCTAssertThrowsError(try AppUpdateVersion("latest"))
    }
}
