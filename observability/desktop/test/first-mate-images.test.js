// First mate: pasted images (issue #368). The content array a send writes to
// the child, main's re-check of type, size and count, the strip's paste
// admission, the transcript marker, and first-mate.json keeping the count but
// never the image data. Runs against test/fake-claude-child.js, as
// test/first-mate.test.js does.
"use strict";
const test = require("node:test");
const assert = require("node:assert/strict");
const fs = require("node:fs");
const os = require("node:os");
const path = require("node:path");
const React = require("react");
const { renderToStaticMarkup } = require("react-dom/server");

const {
  IMAGE_TYPES, IMAGE_MAX_BYTES, IMAGE_MAX_COUNT,
  decodedBytes, validateImages, buildContent, admitPaste, imagesLabel,
} = require("../src/first-mate-images");
const { FirstMateManager } = require("../src/first-mate");
const { saveFirstMate, loadFirstMate, firstMatePath, freshSession } = require("../src/first-mate-store");
const { FirstMate, pastedImageFiles } = require("../dist/first-mate.cjs");
const { makeFakeChild } = require("./fake-claude-child");

const tmpdir = () => fs.mkdtempSync(path.join(os.tmpdir(), "first-mate-images-"));
const src = (...p) => fs.readFileSync(path.join(__dirname, "..", "src", ...p), "utf8");

// A 1x1 PNG, and an image of an exact decoded size that starts with its
// type's magic bytes (main checks them, #371).
const PNG = "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNkYAAAAAYAAjCB0C8AAAAASUVORK5CYII=";
const png = (data = PNG) => ({ media_type: "image/png", data });
const MAGIC = {
  "image/png": [0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a],
  "image/jpeg": [0xff, 0xd8, 0xff],
  "image/gif": [...Buffer.from("GIF89a")],
  "image/webp": [...Buffer.from("RIFF"), 0, 0, 0, 0, ...Buffer.from("WEBP")],
};
const ofBytes = (n, media_type = "image/jpeg") => {
  const b = Buffer.alloc(n);
  Buffer.from(MAGIC[media_type] || []).copy(b);
  return { media_type, data: b.toString("base64") };
};

const fm = (config = {}) => {
  const dir = tmpdir();
  const inputLog = path.join(dir, "input.jsonl");
  const { command, launchArgs } = makeFakeChild(path.join(dir, "bin"), { ...config, inputLog });
  const m = new FirstMateManager({
    dir, command, launchArgs,
    resolveTierFn: (t) => (t >= 1 && t <= 10 ? { model: "sonnet", effort: "medium" } : null),
  }).init();
  const written = () => (fs.existsSync(inputLog)
    ? fs.readFileSync(inputLog, "utf8").trim().split("\n").filter(Boolean).map((l) => JSON.parse(l))
    : []);
  return { m, dir, written };
};

// ---------------------------------------------------------------------------
// The content a send builds
// ---------------------------------------------------------------------------

test("buildContent: no images is the plain text, as before; images come first, then the text block", () => {
  assert.equal(buildContent("hello", []), "hello");
  assert.equal(buildContent("hello", undefined), "hello");
  const two = [png(), ofBytes(3, "image/webp")];
  assert.deepEqual(buildContent("look at these", two), [
    { type: "image", source: { type: "base64", media_type: "image/png", data: PNG } },
    { type: "image", source: { type: "base64", media_type: "image/webp", data: two[1].data } },
    { type: "text", text: "look at these" },
  ]);
  // Images with no text: no empty text block.
  assert.deepEqual(buildContent("", [png()]),
    [{ type: "image", source: { type: "base64", media_type: "image/png", data: PNG } }]);
});

test("a send with images writes one user message whose content is image blocks then the text", async () => {
  const { m, written } = fm();
  const r = await m.send("what is this", [png(), ofBytes(10, "image/gif")]);
  assert.equal(r.ok, true);
  assert.equal(r.text, "echo: what is this");
  const msgs = written();
  assert.equal(msgs.length, 1);
  assert.equal(msgs[0].type, "user");
  assert.deepEqual(msgs[0].message, { role: "user", content: [
    { type: "image", source: { type: "base64", media_type: "image/png", data: PNG } },
    { type: "image", source: { type: "base64", media_type: "image/gif", data: ofBytes(10, "image/gif").data } },
    { type: "text", text: "what is this" },
  ] });
  m.stop();
});

test("a send without images still writes content as the plain string", async () => {
  const { m, written } = fm();
  assert.equal((await m.send("just text")).ok, true);
  assert.equal((await m.send("more text", [])).ok, true);
  assert.deepEqual(written().map((w) => w.message.content), ["just text", "more text"]);
  m.stop();
});

test("a message with images and empty text may be sent; empty text with no images is still refused", async () => {
  const { m, written } = fm();
  const r = await m.send("", [png()]);
  assert.equal(r.ok, true);
  assert.deepEqual(written()[0].message.content,
    [{ type: "image", source: { type: "base64", media_type: "image/png", data: PNG } }]);
  const none = await m.send("   ", []);
  assert.equal(none.ok, false);
  assert.equal(none.error, "nothing to send");
  m.stop();
});

// ---------------------------------------------------------------------------
// Main's re-check
// ---------------------------------------------------------------------------

test("validateImages accepts the four types up to the limits and reduces each to plain data", () => {
  const imgs = IMAGE_TYPES.map((t) => ({ ...ofBytes(16, t), extra: "dropped" }));
  const r = validateImages(imgs);
  assert.equal(r.ok, true);
  assert.deepEqual(r.images, IMAGE_TYPES.map((t) => ({ media_type: t, data: ofBytes(16, t).data })));
  assert.equal(validateImages(undefined).ok, true);
  assert.equal(validateImages([ofBytes(IMAGE_MAX_BYTES)]).ok, true, "exactly 5 MB is allowed");
  assert.equal(decodedBytes(ofBytes(IMAGE_MAX_BYTES).data), IMAGE_MAX_BYTES);
});

test("validateImages refuses a disallowed type, an oversize image, too many images, and non-data", () => {
  assert.match(validateImages([{ media_type: "image/svg+xml", data: "AAAA" }]).error, /image\/svg\+xml.*only PNG, JPEG, GIF and WebP/);
  assert.match(validateImages([ofBytes(IMAGE_MAX_BYTES + 1)]).error, /the limit is 5\.0 MB/);
  assert.match(validateImages(Array.from({ length: IMAGE_MAX_COUNT + 1 }, () => png())).error, /at most 5/);
  assert.match(validateImages([{ media_type: "image/png", data: "not base64!" }]).error, /not base64/);
  assert.match(validateImages([{ media_type: "image/png", data: { x: 1 } }]).error, /not base64/);
  // A declared type its bytes do not bear out (#371).
  assert.match(validateImages([{ ...ofBytes(16, "image/gif"), media_type: "image/png" }]).error, /image 1 is not a PNG file/);
  assert.match(validateImages([ofBytes(16, "image/webp"), { media_type: "image/jpeg", data: Buffer.alloc(16).toString("base64") }]).error, /image 2 is not a JPEG file/);
  assert.match(validateImages("image").error, /must be a list/);
});

for (const [name, images, pattern] of [
  ["a disallowed type", [{ media_type: "image/bmp", data: "AAAA" }], /image\/bmp/],
  ["an oversize image", [ofBytes(IMAGE_MAX_BYTES + 3)], /limit is 5\.0 MB/],
  ["too many images", Array.from({ length: IMAGE_MAX_COUNT + 1 }, () => png()), /at most 5/],
]) {
  test(`the manager refuses ${name} with an error, writes nothing and records nothing`, async () => {
    const { m, written } = fm();
    const r = await m.send("see attached", images);
    assert.equal(r.ok, false);
    assert.equal(r.refused, true);
    assert.match(r.error, pattern);
    assert.deepEqual(written(), []);
    assert.deepEqual(m.snapshot().history, []);
    assert.equal(m.snapshot().running, false, "no child was even started");
    m.stop();
  });
}

test("a busy refusal says refused, so the strip hands back the draft and the images", async () => {
  const { m } = fm({ hang: true });
  m.send("first", [png()]);
  await new Promise((r) => setTimeout(r, 60));
  const r = await m.send("second", [png()]);
  assert.equal(r.ok, false);
  assert.equal(r.busy, true);
  assert.equal(r.refused, true);
  m.cancel();
  m.stop();
});

test("the firstMate:send bridge and handler carry the images as plain media type + base64 data only", () => {
  const preload = src("preload.js");
  const body = preload.slice(preload.indexOf('exposeInMainWorld("firstMate"'), preload.indexOf('exposeInMainWorld("app"'));
  assert.match(body, /send: \(text, images\) => ipcRenderer\.invoke\("firstMate:send", String\(text\),/);
  assert.match(body, /\(\{ media_type: String\(im && im\.media_type\), data: String\(im && im\.data\) \}\)/);
  const main = src("main.js");
  assert.match(main, /ipcMain\.handle\("firstMate:send", async \(_e, text, images\) => \{\s*const r = await firstMate\.send\(String\(text == null \? "" : text\), images == null \? \[\] : images\)/);
});

// ---------------------------------------------------------------------------
// Persistence: the count, never the data
// ---------------------------------------------------------------------------

test("first-mate.json keeps the sent message's text and image count, not the image data", async () => {
  const { m, dir } = fm();
  await m.send("two screenshots", [png(), png()]);
  await m.send("no pictures");
  m.stop();
  const raw = fs.readFileSync(firstMatePath(dir), "utf8");
  assert.ok(!raw.includes(PNG), "no base64 in the file");
  assert.ok(!raw.includes("media_type"), "no image blocks in the file");
  const { session } = loadFirstMate(dir);
  const users = session.history.filter((h) => h.role === "user");
  assert.equal(users[0].text, "two screenshots");
  assert.equal(users[0].images, 2);
  assert.equal("images" in users[1], false, "a message without images carries no marker");
  // The live snapshot's row has the marker too.
  assert.equal(m.snapshot().history.find((h) => h.role === "user").images, 2);
});

test("the store drops any image data a message object carries, keeps the count, and archives it", () => {
  const dir = tmpdir();
  const s = freshSession();
  s.history = [{ role: "user", text: "hi", at: 1, images: 3, data: PNG, blocks: [png()] }];
  s.conversations = [{ id: 1, started_at: 0, messages: [{ role: "user", text: "", at: 0, images: 1, image: png() }] }];
  saveFirstMate(dir, s);
  const raw = fs.readFileSync(firstMatePath(dir), "utf8");
  assert.ok(!raw.includes(PNG));
  const { session } = loadFirstMate(dir);
  assert.deepEqual(session.history, [{ role: "user", text: "hi", at: 1, images: 3 }]);
  assert.deepEqual(session.conversations[0].messages, [{ role: "user", text: "", at: 0, images: 1 }]);
});

// ---------------------------------------------------------------------------
// The strip
// ---------------------------------------------------------------------------

test("admitPaste takes the four types under 5 MB up to five in all, and names each refusal", () => {
  const ok = { type: "image/png", size: 100 };
  assert.deepEqual(admitPaste(0, [ok]), { accept: [ok], notice: null });
  const big = { type: "image/jpeg", size: IMAGE_MAX_BYTES + 1 };
  const bmp = { type: "image/bmp", size: 10 };
  const r = admitPaste(0, [big, bmp]);
  assert.deepEqual(r.accept, []);
  assert.match(r.notice, /over the 5\.0 MB limit/);
  assert.match(r.notice, /image\/bmp is not accepted/);
  const full = admitPaste(4, [ok, ok, ok]);
  assert.equal(full.accept.length, 1);
  assert.match(full.notice, /at most 5 images/);
  assert.equal(imagesLabel(1), "1 image");
  assert.equal(imagesLabel(3), "3 images");
});

test("pastedImageFiles picks image files out of a paste; a text paste yields none", () => {
  const file = { name: "shot.png", type: "image/png" };
  const items = [
    { kind: "string", type: "text/plain", getAsFile: () => null },
    { kind: "file", type: "image/png", getAsFile: () => file },
    { kind: "file", type: "application/pdf", getAsFile: () => ({}) },
  ];
  assert.deepEqual(pastedImageFiles({ items }), [file]);
  assert.deepEqual(pastedImageFiles({ items: [items[0]] }), []);
  assert.deepEqual(pastedImageFiles(null), []);
});

const snap = (over = {}) => ({ history: [], filed: [], busy: false, ...over });
const draw = (props) => renderToStaticMarkup(React.createElement(FirstMate, {
  snapshot: snap(), draft: "", onDraft() {}, onSend() {}, ...props,
}));

test("attached images show as thumbnails by the input, each with a remove control, and allow Send with no text", () => {
  const html = draw({ attachments: [png(), ofBytes(3, "image/jpeg")] });
  assert.match(html, /class="fmAttachments"/);
  assert.equal((html.match(/<img class="fmThumb"/g) || []).length, 2);
  assert.ok(html.includes(`src="data:image/png;base64,${PNG}"`));
  assert.equal((html.match(/class="fmAttachmentRemove"/g) || []).length, 2);
  assert.match(html, /<button class="fmSend">Send<\/button>/, "Send is enabled with images and no text");
  assert.match(draw({}), /<button class="fmSend" disabled="">Send<\/button>/, "and disabled with neither");
  assert.doesNotMatch(draw({}), /fmAttachments/);
});

test("a refused paste's notice shows on the strip", () => {
  const html = draw({ attachNotice: "Not attached: at most 5 images go with one message." });
  assert.match(html, /class="fmNotice fmAttachNotice">Not attached: at most 5 images/);
});

test("a sent message's row says how many images it carried, live and in History", () => {
  const html = draw({ snapshot: snap({ history: [
    { role: "user", text: "see this", at: 1, images: 2 },
    { role: "assistant", text: "I see it", at: 2 },
    { role: "user", text: "", at: 3, images: 1 },
  ] }) });
  assert.match(html, /<div class="fmImagesMarker">2 images<\/div><div class="fmText">see this<\/div>/);
  assert.match(html, /<div class="fmImagesMarker">1 image<\/div>/);
  const hist = draw({ viewingConversation: { id: 1, messages: [{ role: "user", text: "old", at: 1, images: 4 }] } });
  assert.match(hist, /<div class="fmImagesMarker">4 images<\/div>/);
});
