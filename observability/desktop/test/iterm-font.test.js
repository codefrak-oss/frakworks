// The iTerm2 font reader (issue #366): src/iterm-font.js. Pure parsing, with
// `defaults` and the platform injected — nothing here runs a command.
"use strict";
const test = require("node:test");
const assert = require("node:assert/strict");

const { parseFontSpec, parsePlist, pickProfile, fontFromPrefs, readItermFont, FALLBACK, DEFAULT_SIZE } = require("../src/iterm-font");

// What `defaults export com.googlecode.iterm2 -` prints, cut down to the keys
// that matter plus the kinds of value around them.
const plist = ({ guid = "B-GUID", profiles } = {}) => `<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0">
<dict>
	<key>AppleAntiAliasingThreshold</key>
	<integer>1</integer>
	${guid == null ? "" : `<key>Default Bookmark Guid</key>\n\t<string>${guid}</string>`}
	<key>NoSyncFrame_SharedPreferences</key>
	<data>
	YnBsaXN0MDA=
	</data>
	<key>New Bookmarks</key>
	<array>${(profiles || [
    { guid: "A-GUID", name: "Plain &amp; simple", font: "Monaco 12" },
    { guid: "B-GUID", name: "Default", font: "MesloLGS-NF-Regular 13" },
  ]).map((p) => `
		<dict>
			<key>Ansi 0 Color</key>
			<dict>
				<key>Red Component</key>
				<real>0.0784</real>
			</dict>
			<key>Guid</key>
			<string>${p.guid}</string>
			<key>Name</key>
			<string>${p.name}</string>
			${p.font == null ? "" : `<key>Normal Font</key>\n\t\t\t<string>${p.font}</string>`}
			<key>Use Bold Font</key>
			<true/>
			<key>Tags</key>
			<array/>
		</dict>`).join("")}
	</array>
</dict>
</plist>
`;

test("parseFontSpec: a PostScript name with hyphens, and a whole or fractional size", () => {
  assert.deepEqual(parseFontSpec("MesloLGS-NF-Regular 13"), { family: "MesloLGS-NF-Regular", size: 13 });
  assert.deepEqual(parseFontSpec("JetBrainsMonoNF-Regular 12.5"), { family: "JetBrainsMonoNF-Regular", size: 12.5 });
  assert.deepEqual(parseFontSpec("  Menlo-Regular   11 "), { family: "Menlo-Regular", size: 11 });
});

test("parseFontSpec: missing or malformed values do not parse", () => {
  for (const v of [undefined, null, 13, "", "Monaco", "13", "Monaco big", "Monaco 0", "Monaco -3", "Monaco 12pt",
    "Fira Code 12", 'x");} 12', "Monaco 1e2"]) {
    assert.equal(parseFontSpec(v), null, JSON.stringify(v));
  }
});

test("parsePlist reads dicts, arrays, strings, numbers, booleans, data and entities", () => {
  const prefs = parsePlist(plist());
  assert.equal(prefs.AppleAntiAliasingThreshold, 1);
  assert.equal(prefs["Default Bookmark Guid"], "B-GUID");
  assert.equal(prefs["New Bookmarks"].length, 2);
  assert.equal(prefs["New Bookmarks"][0].Name, "Plain & simple");
  assert.equal(prefs["New Bookmarks"][0]["Ansi 0 Color"]["Red Component"], 0.0784);
  assert.equal(prefs["New Bookmarks"][1]["Use Bold Font"], true);
  assert.deepEqual(prefs["New Bookmarks"][1].Tags, []);
  assert.throws(() => parsePlist("<plist><dict><key>a</key>"), /plist/);
  assert.throws(() => parsePlist("not a plist"), /plist/);
});

test("pickProfile: the profile whose Guid is Default Bookmark Guid, not the first", () => {
  assert.equal(pickProfile(parsePlist(plist())).Name, "Default");
  assert.deepEqual(fontFromPrefs(parsePlist(plist())), { family: "MesloLGS-NF-Regular", size: 13, source: "iterm2" });
});

test("pickProfile: no Default Bookmark Guid, or one that matches nothing, falls back to the first profile", () => {
  assert.equal(pickProfile(parsePlist(plist({ guid: null }))).Name, "Plain & simple");
  assert.equal(pickProfile(parsePlist(plist({ guid: "GONE" }))).Name, "Plain & simple");
  assert.deepEqual(fontFromPrefs(parsePlist(plist({ guid: "GONE" }))), { family: "Monaco", size: 12, source: "iterm2" });
  assert.equal(pickProfile({}), null);
  assert.equal(pickProfile({ "New Bookmarks": [] }), null);
});

test("fontFromPrefs: a missing or malformed Normal Font answers the fallback", () => {
  const missing = parsePlist(plist({ profiles: [{ guid: "B-GUID", name: "Default" }] }));
  assert.equal(fontFromPrefs(missing), FALLBACK);
  const bad = parsePlist(plist({ profiles: [{ guid: "B-GUID", name: "Default", font: "Monaco" }] }));
  assert.equal(fontFromPrefs(bad), FALLBACK);
  assert.equal(fontFromPrefs({}), FALLBACK);
  assert.deepEqual(FALLBACK, { family: null, size: DEFAULT_SIZE, source: "fallback" });
});

test("readItermFont: macOS with iTerm2 answers the default profile's font", async () => {
  assert.deepEqual(await readItermFont({ platform: "darwin", exec: async () => plist() }),
    { family: "MesloLGS-NF-Regular", size: 13, source: "iterm2" });
});

test("readItermFont: not macOS, no iTerm2 domain, or garbage answers the fallback and never throws", async () => {
  let ran = false;
  assert.equal(await readItermFont({ platform: "linux", exec: async () => { ran = true; return plist(); } }), FALLBACK);
  assert.equal(ran, false, "defaults is not run off macOS");
  const noDomain = async () => { throw new Error("Domain com.googlecode.iterm2 does not exist"); };
  assert.equal(await readItermFont({ platform: "darwin", exec: noDomain }), FALLBACK);
  assert.equal(await readItermFont({ platform: "darwin", exec: async () => "garbage" }), FALLBACK);
  assert.equal(await readItermFont({ platform: "darwin", exec: async () => "" }), FALLBACK);
});
