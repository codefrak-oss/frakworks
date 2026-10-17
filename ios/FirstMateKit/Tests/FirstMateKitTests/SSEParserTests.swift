import XCTest
@testable import FirstMateKit

final class SSEParserTests: XCTestCase {
    func testOneEventWithName() {
        var p = SSEParser()
        XCTAssertEqual(p.feed("event: first-mate\ndata: {\"a\":1}\n\n"), [SSEEvent(event: "first-mate", data: "{\"a\":1}")])
    }

    func testDefaultsToMessage() {
        var p = SSEParser()
        XCTAssertEqual(p.feed("data: x\n\n").first?.event, "message")
    }

    func testChunkBoundariesAnywhere() {
        let text = "event: first-mate\ndata: hello\n\nevent: job\ndata: two\n\n"
        for cut in 0...text.count {
            var p = SSEParser()
            let i = text.index(text.startIndex, offsetBy: cut)
            let events = p.feed(String(text[..<i])) + p.feed(String(text[i...]))
            XCTAssertEqual(events, [SSEEvent(event: "first-mate", data: "hello"), SSEEvent(event: "job", data: "two")], "cut at \(cut)")
        }
    }

    func testMultiLineData() {
        var p = SSEParser()
        XCTAssertEqual(p.feed("data: a\ndata: b\ndata\n\n").first?.data, "a\nb\n")
    }

    func testCommentsAndPingsAreSkipped() {
        var p = SSEParser()
        XCTAssertEqual(p.feed(": ping\n\n: ping\n"), [])
        XCTAssertEqual(p.feed("data: x\n\n"), [SSEEvent(event: "message", data: "x")])
    }

    func testCRLFAndCRLineEnds() {
        var p = SSEParser()
        XCTAssertEqual(p.feed("event: e\r\ndata: 1\r\n\r\n"), [SSEEvent(event: "e", data: "1")])
        XCTAssertEqual(p.feed("data: 2\r"), [])
        XCTAssertEqual(p.feed("\n\r\n"), [SSEEvent(event: "message", data: "2")])
        XCTAssertEqual(p.feed("data: 3\r\r"), [SSEEvent(event: "message", data: "3")])
    }

    func testIdIsKeptAndUnknownFieldsIgnored() {
        var p = SSEParser()
        XCTAssertEqual(p.feed("id: 7\nretry: 100\nfoo: bar\ndata: x\n\n"), [SSEEvent(event: "message", data: "x", id: "7")])
        XCTAssertEqual(p.feed("data: y\n\n").first?.id, "7")
    }

    func testBlockWithoutDataIsDropped() {
        var p = SSEParser()
        XCTAssertEqual(p.feed("event: hello\n\n"), [])
        XCTAssertEqual(p.feed("data: z\n\n").first?.event, "message", "the event name does not leak into the next block")
    }

    func testOnlyOneLeadingSpaceIsStripped() {
        var p = SSEParser()
        XCTAssertEqual(p.feed("data:  two\ndata:none\n\n").first?.data, " two\nnone")
    }

    func testFirstMateFrameDecoding() {
        let frame = SSEEvent(event: "first-mate", data: #"{"repo":"o/r","sessionId":"s1","seq":4,"line":{"type":"assistant","message":{"content":[{"type":"text","text":"Hi "},{"type":"tool_use"},{"type":"text","text":"there"}]}},"at":"2026-10-11T00:00:00Z"}"#)
        let f = FrameDecoder.decode(frame, sessionId: "s1")
        XCTAssertEqual(f?.seq, 4)
        XCTAssertEqual(f?.assistantText, "Hi there")
        XCTAssertFalse(f?.isResuming ?? true)
        XCTAssertNil(FrameDecoder.decode(frame, sessionId: "other"))
        XCTAssertNil(FrameDecoder.decode(SSEEvent(event: "job", data: frame.data)))
    }

    func testResumingFrame() {
        let e = SSEEvent(event: "first-mate", data: #"{"repo":"o/r","sessionId":"s1","seq":0,"line":{"type":"resuming"}}"#)
        XCTAssertTrue(FrameDecoder.decode(e)?.isResuming ?? false)
    }

    func testRawTextLine() {
        let e = SSEEvent(event: "first-mate", data: #"{"repo":"o/r","sessionId":"s1","seq":2,"line":"not json"}"#)
        let f = FrameDecoder.decode(e)
        XCTAssertEqual(f?.line, .string("not json"))
        XCTAssertNil(f?.assistantText)
    }
}
