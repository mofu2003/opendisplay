import XCTest

final class FrameRateOptionTests: XCTestCase {
    func testAutomaticUsesTheReceiversAdvertisedCeiling() {
        XCTAssertEqual(FrameRateOption.automatic.request(displayMaximum: 120), 120)
        XCTAssertEqual(FrameRateOption.automatic.request(displayMaximum: 90), 90)
    }

    func testAutomaticKeepsTheLegacySixtyFramesPerSecondContract() {
        XCTAssertEqual(FrameRateOption.automatic.request(displayMaximum: nil), 60)
        XCTAssertEqual(FrameRateOption.automatic.request(displayMaximum: 0), 60)
    }

    func testExplicitChoiceCanLowerButNotExceedTheReceiverCeiling() {
        XCTAssertEqual(FrameRateOption.fps30.request(displayMaximum: 120), 30)
        XCTAssertEqual(FrameRateOption.fps60.request(displayMaximum: 120), 60)
        XCTAssertEqual(FrameRateOption.fps120.request(displayMaximum: 60), 60)
        XCTAssertEqual(FrameRateOption.fps120.request(displayMaximum: nil), 60)
    }

    func testSourceAndReceiverCapsBothApply() {
        XCTAssertEqual(FrameRateOption.fps120.request(
            displayMaximum: 120, sourceMaximum: 60), 60)
        XCTAssertEqual(FrameRateOption.automatic.request(
            displayMaximum: 120, sourceMaximum: 60), 60)
        XCTAssertEqual(FrameRateOption.fps30.request(
            displayMaximum: 120, sourceMaximum: 60), 30)
    }

    func testResolvedCapsAtThePhysicalRefreshRate() {
        XCTAssertEqual(FrameRateOption.automatic.resolved(displayRefreshRate: 59.94), 60)
        XCTAssertEqual(FrameRateOption.fps120.resolved(displayRefreshRate: 60), 60)
        XCTAssertEqual(FrameRateOption.fps90.resolved(displayRefreshRate: 120), 90)
    }
}
