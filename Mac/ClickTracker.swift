import CoreGraphics
import Foundation

/// Mirrors the Window Server's multi-click chain for synthetic mouse input.
///
/// The Window Server derives `mouseEventClickState` from real hardware clicks.
/// Events posted with Core Graphics do not get that treatment, so a remote
/// double-tap arrives as two independent single clicks unless we count them
/// ourselves. The same time-and-distance rules as the Apple Pencil path apply:
/// a click extends the chain only when it starts within the system double-click
/// interval and close to the previous click, and it only becomes the new chain
/// anchor when the pointer did not move like a drag.
struct ClickTracker {
    struct Session {
        let downLocation: CGPoint
        let clickState: Int
    }

    struct CompletedClick {
        let upTime: TimeInterval
        let downLocation: CGPoint
        let clickState: Int
    }

    private(set) var session: Session?
    private(set) var lastClick: CompletedClick?

    private let interval: () -> TimeInterval
    private let distance: () -> CGFloat
    private let now: () -> TimeInterval

    init(
        interval: @escaping () -> TimeInterval,
        distance: @escaping () -> CGFloat,
        now: @escaping () -> TimeInterval = { ProcessInfo.processInfo.systemUptime }
    ) {
        self.interval = interval
        self.distance = distance
        self.now = now
    }

    /// Returns the click state for the matching mouse-down.
    mutating func begin(at point: CGPoint) -> Int {
        let clickState: Int
        if let last = lastClick,
           now() - last.upTime <= interval(),
           hypot(point.x - last.downLocation.x, point.y - last.downLocation.y) <= distance() {
            clickState = last.clickState + 1
        } else {
            clickState = 1
        }
        session = Session(downLocation: point, clickState: clickState)
        return clickState
    }

    /// Returns the click state for the matching mouse-up and advances the chain
    /// only when the press did not turn into a drag.
    mutating func finish(at upLocation: CGPoint) -> Int {
        guard let session else { return 1 }
        self.session = nil

        let dx = upLocation.x - session.downLocation.x
        let dy = upLocation.y - session.downLocation.y
        if hypot(dx, dy) <= distance() {
            lastClick = CompletedClick(
                upTime: now(),
                downLocation: session.downLocation,
                clickState: session.clickState
            )
        } else {
            lastClick = nil
        }
        return session.clickState
    }

    /// A cancelled press was not a click, so the next one starts a new chain.
    mutating func cancel() {
        session = nil
        lastClick = nil
    }
}
