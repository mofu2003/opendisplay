import XCTest

final class ReverseConnectionRequestTests: XCTestCase {
    func testDecodesConnectRequest() {
        let request = ReverseConnectionRequest(jsonData: Data("""
        {"type":"connectRequest","pv":1,"id":"ABC-123","name":"Pixel Pad","device":"Android tablet"}
        """.utf8))

        XCTAssertEqual(request, ReverseConnectionRequest(
            receiverID: "ABC-123", receiverName: "Pixel Pad", receiverKind: "Android tablet"))
    }

    func testEncodesConnectRequest() throws {
        let data = ReverseConnectionRequest(
            receiverID: "ABC-123", receiverName: "Pixel Pad", receiverKind: "Android tablet"
        ).jsonData
        let object = try XCTUnwrap(
            JSONSerialization.jsonObject(with: data) as? [String: Any])

        XCTAssertEqual(object["type"] as? String, "connectRequest")
        XCTAssertEqual(object["pv"] as? Int, ReverseConnectionRequest.serviceVersion)
        XCTAssertEqual(object["id"] as? String, "ABC-123")
        XCTAssertEqual(object["name"] as? String, "Pixel Pad")
        XCTAssertEqual(object["device"] as? String, "Android tablet")
    }

    func testRejectsMalformedOrIncompleteRequests() {
        XCTAssertNil(ReverseConnectionRequest(jsonData: Data("not json".utf8)))
        XCTAssertNil(ReverseConnectionRequest(jsonData: Data("""
        {"type":"somethingElse","pv":1,"id":"ABC-123"}
        """.utf8)))
        XCTAssertNil(ReverseConnectionRequest(jsonData: Data("""
        {"type":"connectRequest","pv":1}
        """.utf8)))
        XCTAssertNil(ReverseConnectionRequest(jsonData: Data("""
        {"type":"connectRequest","pv":1,"id":""}
        """.utf8)))
    }

    func testRejectsUnsupportedBootstrapVersion() {
        XCTAssertNil(ReverseConnectionRequest(jsonData: Data("""
        {"type":"connectRequest","pv":2,"id":"ABC-123"}
        """.utf8)))
    }

    func testEncodesReplies() throws {
        let accepted = try XCTUnwrap(
            JSONSerialization.jsonObject(with: ReverseConnectionReply.accepted.jsonData)
                as? [String: Any])
        XCTAssertEqual(accepted["type"] as? String, "accepted")
        XCTAssertEqual(accepted["pv"] as? Int, ReverseConnectionRequest.serviceVersion)

        let rejected = try XCTUnwrap(
            JSONSerialization.jsonObject(
                with: ReverseConnectionReply.rejected(.unknownDevice).jsonData)
                as? [String: Any])
        XCTAssertEqual(rejected["type"] as? String, "rejected")
        XCTAssertEqual(rejected["reason"] as? String, "unknownDevice")
    }
}
