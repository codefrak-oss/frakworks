import XCTest
@testable import FirstMateKit

final class SpeechTextTests: XCTestCase {
    func testTicketLinkIsSpokenAsTicketNumber() {
        XCTAssertEqual(SpeechText.prepare("See [#872](frakworks://ticket/codefrak-oss/frakworks/872) now"),
                       ["See ticket 872 now."])
    }

    func testBareTicketReferenceAndBareTicketURL() {
        XCTAssertEqual(SpeechText.prepare("Filed #2433, frakworks://ticket/codefrak-oss/frakworks/12 too."),
                       ["Filed ticket 2433, ticket 12 too."])
    }

    func testOtherLinkIsSpokenAsItsText() {
        XCTAssertEqual(SpeechText.prepare("Read [the proposal](https://example.com/p.md)."), ["Read the proposal."])
    }

    func testBareURLIsSpokenAsLink() {
        XCTAssertEqual(SpeechText.prepare("Open https://example.com/a/b now"), ["Open link now."])
    }

    func testEmphasisAndInlineCodeAreStripped() {
        XCTAssertEqual(SpeechText.prepare("This is **bold**, _italic_ and `code`."), ["This is bold, italic and code."])
    }

    func testCodeBlockIsOmitted() {
        XCTAssertEqual(SpeechText.prepare("Run this:\n```bash\nls -la\n```\nDone."), ["Run this: Code omitted.", "Done."])
    }

    func testHeadingsAndBulletsBecomeSentences() {
        XCTAssertEqual(SpeechText.prepare("# Title\n- one\n- two\n1. three"), ["Title.", "one.", "two.", "three."])
    }

    func testSentencesKeepTheirOrder() {
        XCTAssertEqual(SpeechText.prepare("One. Two! Three?"), ["One.", "Two!", "Three?"])
    }

    func testLongSentenceIsSplitAtSpaces() {
        XCTAssertEqual(SpeechText.prepare("aaa bbb ccc", maxChunk: 7), ["aaa bbb", "ccc."])
    }

    func testEmptyAndWhitespaceOnlyInputSpeakNothing() {
        XCTAssertEqual(SpeechText.prepare(""), [])
        XCTAssertEqual(SpeechText.prepare("  \n\n "), [])
    }
}
