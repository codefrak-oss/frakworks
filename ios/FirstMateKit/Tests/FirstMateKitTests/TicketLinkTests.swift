import XCTest
@testable import FirstMateKit

final class TicketLinkTests: XCTestCase {
    func testValid() {
        let l = TicketLink(string: "frakworks://ticket/codefrak-oss/frakworks/872")
        XCTAssertEqual(l, TicketLink(owner: "codefrak-oss", name: "frakworks", number: 872))
        XCTAssertEqual(l?.apiPath, "/ticket/codefrak-oss/frakworks/872")
        XCTAssertEqual(l?.url.absoluteString, "frakworks://ticket/codefrak-oss/frakworks/872")
        XCTAssertEqual(l?.repo, "codefrak-oss/frakworks")
    }

    func testFromURL() {
        XCTAssertNotNil(TicketLink(url: URL(string: "frakworks://ticket/a/b.c_d/1")!))
    }

    func testInvalidForms() {
        for s in [
            "frakworks://ticket/codefrak-oss/frakworks",          // no number
            "frakworks://ticket/codefrak-oss/frakworks/",         // empty number
            "frakworks://ticket/codefrak-oss/frakworks/872/x",    // extra path
            "frakworks://ticket/codefrak-oss/frakworks/872/",     // trailing slash
            "frakworks://ticket/codefrak-oss/frakworks/87a",      // not a number
            "frakworks://ticket/codefrak-oss/frakworks/0",        // not positive
            "frakworks://ticket//frakworks/1",                    // empty owner
            "frakworks://issue/codefrak-oss/frakworks/1",         // other host
            "https://ticket/codefrak-oss/frakworks/1",            // other scheme
            "https://github.com/codefrak-oss/frakworks/issues/1",
        ] { XCTAssertNil(TicketLink(string: s), s) }
    }

    func testAllInMarkdown() {
        let md = "See [#872](frakworks://ticket/codefrak-oss/frakworks/872) and frakworks://ticket/o/r/5, not frakworks://ticket/o/r/5/x."
        XCTAssertEqual(TicketLink.all(in: md).map(\.number), [872, 5])
    }
}
