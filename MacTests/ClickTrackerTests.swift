import CoreGraphics
import XCTest

final class ClickTrackerTests: XCTestCase {
    private var now: TimeInterval = 100
    private var tracker: ClickTracker!

    override func setUp() {
        tracker = ClickTracker(
            interval: { 0.5 },
            distance: { 4 },
            now: { self.now }
        )
    }

    func testSingleClick() {
        XCTAssertEqual(tracker.begin(at: CGPoint(x: 100, y: 100)), 1)
        XCTAssertEqual(tracker.finish(at: CGPoint(x: 100, y: 100)), 1)
    }

    func testQuickSecondClickIsADoubleClick() {
        tracker.begin(at: CGPoint(x: 100, y: 100))
        tracker.finish(at: CGPoint(x: 100, y: 100))

        now += 0.1
        XCTAssertEqual(tracker.begin(at: CGPoint(x: 101, y: 101)), 2)
        XCTAssertEqual(tracker.finish(at: CGPoint(x: 101, y: 101)), 2)
    }

    func testQuickThirdClickIsATripleClick() {
        tracker.begin(at: CGPoint(x: 100, y: 100))
        tracker.finish(at: CGPoint(x: 100, y: 100))

        now += 0.1
        tracker.begin(at: CGPoint(x: 101, y: 100))
        tracker.finish(at: CGPoint(x: 101, y: 100))

        now += 0.1
        XCTAssertEqual(tracker.begin(at: CGPoint(x: 100, y: 101)), 3)
        XCTAssertEqual(tracker.finish(at: CGPoint(x: 100, y: 101)), 3)
    }

    func testSlowSecondClickStartsOver() {
        tracker.begin(at: CGPoint(x: 100, y: 100))
        tracker.finish(at: CGPoint(x: 100, y: 100))

        now += 0.6
        XCTAssertEqual(tracker.begin(at: CGPoint(x: 100, y: 100)), 1)
    }

    func testFarSecondClickStartsOver() {
        tracker.begin(at: CGPoint(x: 100, y: 100))
        tracker.finish(at: CGPoint(x: 100, y: 100))

        now += 0.1
        XCTAssertEqual(tracker.begin(at: CGPoint(x: 105, y: 100)), 1)
    }

    func testDragDoesNotAnchorTheNextClick() {
        tracker.begin(at: CGPoint(x: 100, y: 100))
        tracker.finish(at: CGPoint(x: 100, y: 100))

        now += 0.1
        XCTAssertEqual(tracker.begin(at: CGPoint(x: 101, y: 100)), 2)
        XCTAssertEqual(tracker.finish(at: CGPoint(x: 200, y: 200)), 2)

        now += 0.1
        XCTAssertEqual(tracker.begin(at: CGPoint(x: 100, y: 100)), 1)
    }

    func testCancelClearsTheChain() {
        tracker.begin(at: CGPoint(x: 100, y: 100))
        tracker.finish(at: CGPoint(x: 100, y: 100))

        now += 0.1
        tracker.begin(at: CGPoint(x: 100, y: 100))
        tracker.cancel()

        now += 0.1
        XCTAssertEqual(tracker.begin(at: CGPoint(x: 100, y: 100)), 1)
    }

    func testUpWithoutDownIsASingleClick() {
        XCTAssertEqual(tracker.finish(at: CGPoint(x: 100, y: 100)), 1)
    }
}
