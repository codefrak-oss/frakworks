import XCTest
@testable import FirstMateKit

final class PairingTests: XCTestCase {
    func testDecodesQRPayload() throws {
        let p = try Pairing.decode(#"{"url":"https://engine.example.com/","token":"fm_abc"}"#)
        XCTAssertEqual(p.url.absoluteString, "https://engine.example.com")
        XCTAssertEqual(p.token, "fm_abc")
    }

    func testRefusals() {
        XCTAssertThrowsError(try Pairing.decode("fm_abc")) { XCTAssertEqual($0 as? Pairing.Failure, .notJSON) }
        XCTAssertThrowsError(try Pairing.decode(#"{"url":"https://e"}"#)) { XCTAssertEqual($0 as? Pairing.Failure, .missingField("token")) }
        XCTAssertThrowsError(try Pairing.decode(#"{"token":"t"}"#)) { XCTAssertEqual($0 as? Pairing.Failure, .missingField("url")) }
        XCTAssertThrowsError(try Pairing.decode(#"{"url":"ftp://e","token":"t"}"#)) { XCTAssertEqual($0 as? Pairing.Failure, .badURL("ftp://e")) }
    }
}
