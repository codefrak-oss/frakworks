// screenshot.test.js — "Let First mate screenshot this app" (#100105): the
// setting's read/write, the pane-to-rect choice, the encoding, and the desktop
// channel's answer to a capture frame.
"use strict";

const test = require("node:test");
const assert = require("node:assert/strict");
const fs = require("node:fs");
const os = require("node:os");
const path = require("node:path");
const screenshot = require("../src/screenshot");
const { DesktopChannel } = require("../src/desktop-channel");

test("the setting is off on a fresh install and persists on and off", () => {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), "shot-"));
  const file = path.join(dir, screenshot.FILE_NAME);
  assert.equal(screenshot.load(file), false);
  assert.equal(screenshot.save(file, true), true);
  assert.equal(screenshot.load(file), true);
  screenshot.save(file, false);
  assert.equal(screenshot.load(file), false);
  fs.writeFileSync(file, "not json");
  assert.equal(screenshot.load(file), false);
  fs.writeFileSync(file, JSON.stringify({ allowed: "yes" }));
  assert.equal(screenshot.load(file), false, "only true is on");
});

test("a pane is its bounds in whole pixels, clipped to the window; no pane is the whole window", () => {
  const bounds = { left: { x: 0, y: 28.4, width: 260.5, height: 900 }, middle: { x: 266, y: 28, width: 500, height: 900 }, right: { x: 770, y: 28, width: 400, height: 900 } };
  const view = { width: 1100, height: 920 };
  assert.deepEqual(screenshot.paneRect(null, bounds, view), { rect: null });
  assert.deepEqual(screenshot.paneRect("left", bounds, view), { rect: { x: 0, y: 28, width: 261, height: 892 } });
  assert.deepEqual(screenshot.paneRect("middle", bounds, view), { rect: { x: 266, y: 28, width: 500, height: 892 } });
  assert.deepEqual(screenshot.paneRect("right", bounds, view), { rect: { x: 770, y: 28, width: 330, height: 892 } });
  assert.match(screenshot.paneRect("top", bounds, view).error, /left, middle or right/);
  assert.match(screenshot.paneRect("right", { left: bounds.left }, view).error, /not on screen/);
  assert.deepEqual(screenshot.PANE_SELECTORS, { left: "nav.explorer", middle: "main.work", right: "aside.firstMate" });
});

test("the capture is a PNG within 5 MiB, else a JPEG", () => {
  assert.deepEqual(screenshot.encode({ png: () => Buffer.alloc(10), jpeg: () => { throw new Error("unused"); } }), { data: Buffer.alloc(10), mimeType: "image/png" });
  const big = Buffer.alloc(6 * 1024 * 1024);
  assert.equal(screenshot.encode({ png: () => big, jpeg: (q) => (q < 90 ? Buffer.alloc(5) : big) }).mimeType, "image/jpeg");
  assert.match(screenshot.encode({ png: () => big, jpeg: () => big }).error, /over 5 MiB/);
});

const posts = () => {
  const calls = [];
  const fetch = async (url, init) => { calls.push({ url, init, body: JSON.parse(init.body) }); return { ok: true, status: 200 }; };
  return { calls, fetch };
};

test("a capture frame is answered with the image, and refused with no capture while the setting is off", async () => {
  const { calls, fetch } = posts();
  let allowed = false;
  const captured = [];
  const ch = new DesktopChannel({ fetch, engineUrl: () => "http://eng", headers: () => ({ authorization: "Bearer t" }), allowed: () => allowed,
    capture: async (pane) => { captured.push(pane); return { data: Buffer.from("png"), mimeType: "image/png" }; }, log: () => {} });
  await ch.answer("r1", "left");
  assert.deepEqual(captured, []);
  assert.equal(calls[0].url, "http://eng/desktop/capture/r1");
  assert.match(calls[0].body.error, /Let First mate screenshot this app/);
  allowed = true;
  await ch.answer("r2", "left");
  assert.deepEqual(captured, ["left"]);
  assert.deepEqual(calls[1].body, { image: Buffer.from("png").toString("base64"), mimeType: "image/png" });
  assert.equal(calls[1].init.headers.authorization, "Bearer t");
});

test("the state is reported only once the engine said hello", async () => {
  const { calls, fetch } = posts();
  const ch = new DesktopChannel({ fetch, engineUrl: () => "http://eng", log: () => {} });
  assert.equal(await ch.report({ allowed: true }), null);
  assert.equal(calls.length, 0);
  ch._onFrame({ type: "hello", id: "d1" }, {});
  await ch.report({ allowed: true });
  assert.equal(calls[0].url, "http://eng/desktop/channel/d1/state");
  assert.deepEqual(calls[0].body, { allowed: true });
});
