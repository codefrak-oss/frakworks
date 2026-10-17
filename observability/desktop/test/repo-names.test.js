// displayRepoName (#2429): how Config > frakworks > Repo names draws a repo.
"use strict";
const test = require("node:test");
const assert = require("node:assert/strict");
const { displayRepoName, shortName } = require("../src/repo-names.js");

const repos = ["codefrak-oss/frakworks", "codefrak-oss/site", "example-org/Site"];

test("long by default: owner/name with no mode or long", () => {
  assert.equal(displayRepoName("codefrak-oss/frakworks", undefined, repos), "codefrak-oss/frakworks");
  assert.equal(displayRepoName("codefrak-oss/frakworks", "long", repos), "codefrak-oss/frakworks");
});

test("short when chosen: the name alone", () => {
  assert.equal(displayRepoName("codefrak-oss/frakworks", "short", repos), "frakworks");
  assert.equal(shortName("a/b"), "b");
});

test("short, two repos sharing a name (case-insensitively) stay long", () => {
  assert.equal(displayRepoName("codefrak-oss/site", "short", repos), "codefrak-oss/site");
  assert.equal(displayRepoName("example-org/Site", "short", repos), "example-org/Site");
  assert.equal(displayRepoName("codefrak-oss/site", "short", ["codefrak-oss/site"]), "site");
  assert.equal(displayRepoName(null, "short", repos), null);
});
