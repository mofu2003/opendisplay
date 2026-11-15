import Foundation

/// User-selectable stream frame-rate cap shared by the Mac sender and receiver.
/// Automatic follows the peer's advertised maximum; an explicit value is
/// still capped by the other side's display and decoder limits.
enum FrameRateOption: Int, CaseIterable, Identifiable {
    case automatic = 0
    case fps30 = 30
    case fps60 = 60
    case fps90 = 90
    case fps120 = 120

    /// The fastest stream either direction supports today.
    static let absoluteMaximum = 120
    /// Receivers that predate `displayMaxFrameRate` assume a 60 FPS ceiling.
    static let legacyMaximum = 60

    var id: Int { rawValue }

    var label: String {
        self == .automatic ? String(localized: "Automatic") : "\(rawValue) fps"
    }

    var explanation: String {
        switch self {
        case .automatic:
            return String(localized: "Follow the receiver's screen and decoder limits.")
        case .fps30:
            return String(localized: "Reduce system load and make the stream more stable.")
        case .fps60:
            return String(localized: "A smooth default for most devices.")
        case .fps90, .fps120:
            return String(localized: "Smoother motion when both devices support it; otherwise the stream is capped automatically.")
        }
    }

    /// A concrete user request before the peer's advertised limit is applied.
    var requestedFramesPerSecond: Int {
        self == .automatic ? Self.absoluteMaximum : min(rawValue, Self.absoluteMaximum)
    }

    /// Resolve this choice against the receiver's presented refresh rate.
    /// `nil` is the legacy protocol contract: those receivers default to 60.
    func request(displayMaximum: Int?, sourceMaximum: Int? = nil) -> Int {
        let display = displayMaximum.flatMap { $0 > 0 ? $0 : nil }
            ?? Self.legacyMaximum
        let source = sourceMaximum.flatMap { $0 > 0 ? $0 : nil }
            ?? Self.absoluteMaximum
        return min(display, source, requestedFramesPerSecond)
    }

    /// Resolve Automatic against a physical display. Explicit choices are
    /// capped too: a panel cannot present more frames than it refreshes.
    func resolved(displayRefreshRate: Double) -> Int {
        let physical = displayRefreshRate > 0
            ? Int(displayRefreshRate.rounded())
            : Self.legacyMaximum
        return request(displayMaximum: physical)
    }

    static func clamped(_ value: Int) -> Int {
        min(Self.absoluteMaximum, max(1, value))
    }
}
