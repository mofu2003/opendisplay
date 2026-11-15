import Foundation

/// One request from a receiver asking a Mac sender to dial it back. This is a
/// bootstrap protocol; after it succeeds the normal receiver-listens /
/// sender-connects video session takes over unchanged.
struct ReverseConnectionRequest: Equatable {
    static let serviceType = "_od-sender._tcp"
    static let serviceVersion = 1

    let receiverID: String
    let receiverName: String?
    let receiverKind: String?

    init(receiverID: String, receiverName: String?, receiverKind: String?) {
        self.receiverID = receiverID
        self.receiverName = receiverName
        self.receiverKind = receiverKind
    }

    init?(jsonData: Data) {
        guard let object = try? JSONSerialization.jsonObject(with: jsonData) as? [String: Any],
              object["type"] as? String == "connectRequest",
              (object["pv"] as? Int).map({ $0 >= 1 && $0 <= Self.serviceVersion }) ?? true,
              let receiverID = object["id"] as? String,
              !receiverID.isEmpty, receiverID.utf8.count <= 200 else {
            return nil
        }
        self.receiverID = receiverID
        receiverName = (object["name"] as? String).flatMap {
            $0.isEmpty || $0.utf8.count > 255 ? nil : $0
        }
        receiverKind = (object["device"] as? String).flatMap {
            $0.isEmpty || $0.utf8.count > 80 ? nil : $0
        }
    }

    var jsonData: Data {
        var object: [String: Any] = [
            "type": "connectRequest",
            "pv": Self.serviceVersion,
            "id": receiverID,
        ]
        object["name"] = receiverName
        object["device"] = receiverKind
        return (try? JSONSerialization.data(withJSONObject: object)) ?? Data()
    }
}

enum ReverseConnectionReply: Equatable {
    case accepted
    case rejected(Reason)

    enum Reason: String {
        case disabled
        case unknownDevice
        case receiverNotVisible
        case unavailable
    }

    var jsonData: Data {
        var object: [String: Any] = [
            "type": "reply",
            "pv": ReverseConnectionRequest.serviceVersion,
        ]
        switch self {
        case .accepted:
            object["type"] = "accepted"
        case .rejected(let reason):
            object["type"] = "rejected"
            object["reason"] = reason.rawValue
        }
        return (try? JSONSerialization.data(withJSONObject: object)) ?? Data()
    }
}
