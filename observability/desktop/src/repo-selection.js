// repo-selection.js — the repo switcher's choice (#577):
// one target repo (`owner/name`) or All repos (`null`), which every view
// but a ticket's own detail reads through main.
//
// The choice persists in a small JSON file under userData. Its first default,
// computed only when there is no file yet, is the only target repo, else All
// repos.
// Everything here but load/save is pure, so test/repo-selection.test.js runs
// it without Electron or an engine.
"use strict";

const fs = require("node:fs");
const path = require("node:path");
const { REPO_FULL } = require("./engine-data");
const { sameRepo } = require("./repo-names");

const FILE_NAME = "repo-selection.json";
const ALL_REPOS = "All repos";

const isRepo = (r) => typeof r === "string" && REPO_FULL.test(r);

/**
 * The first default: the one repo when `repos` (the GET /repos names) lists exactly one,
 * else null, All repos. A failed /repos read is `repos: null`.
 */
function defaultSelection({ repos = null } = {}) {
  const list = Array.isArray(repos) ? repos.filter(isRepo) : [];
  return list.length === 1 ? list[0] : null;
}

/**
 * The persisted choice: `{ repo }` (a repo or null), or null when there is no
 * file or it cannot be read or parsed, which means "use the first default".
 */
function load(file) {
  let raw;
  try { raw = fs.readFileSync(file, "utf8"); } catch { return null; }
  let j;
  try { j = JSON.parse(raw); } catch { return null; }
  if (!j || typeof j !== "object" || !("repo" in j)) return null;
  if (j.repo === null) return { repo: null };
  return isRepo(j.repo) ? { repo: j.repo } : null;
}

function save(file, repo) {
  fs.mkdirSync(path.dirname(file), { recursive: true });
  fs.writeFileSync(file, JSON.stringify({ repo: repo || null }) + "\n");
}

/**
 * What the app starts on: the persisted choice, else the first default. A
 * persisted repo that GET /repos no longer lists becomes All repos, with a
 * `notice` saying so; while /repos cannot be read (`repos: null`) the choice
 * stands. `fresh` is true when there was no persisted choice.
 */
function resolveSelection({ persisted, repos = null } = {}) {
  if (!persisted) return { repo: defaultSelection({ repos }), fresh: true, notice: null };
  const repo = persisted.repo;
  if (repo && Array.isArray(repos)) {
    const found = repos.find((r) => sameRepo(r, repo));
    if (!found) return { repo: null, fresh: false, notice: `${repo} is no longer a target repo; showing All repos` };
    return { repo: found, fresh: false, notice: null };
  }
  return { repo, fresh: false, notice: null };
}

/**
 * A `repo:set` request against the target repos: `{ repo }` (null for All
 * repos, or the repo as /repos spells it) or `{ error }`. A repo /repos does
 * not list is refused, and so is any repo while /repos cannot be read.
 */
function checkChoice(repo, repos) {
  if (repo === null || repo === undefined || repo === "") return { repo: null };
  if (!isRepo(repo)) return { error: `not an owner/name repo: ${JSON.stringify(repo)}` };
  if (!Array.isArray(repos)) return { error: "the target repos could not be read, so no repo can be chosen" };
  const found = repos.find((r) => sameRepo(r, repo));
  return found ? { repo: found } : { error: `${repo} is not a target repo` };
}

/** The window title and the title strip's text: the repo, or All repos. */
const titleFor = (repo) => (repo ? repo : ALL_REPOS);

module.exports = {
  FILE_NAME, ALL_REPOS, isRepo, sameRepo,
  defaultSelection, load, save, resolveSelection, checkChoice, titleFor,
};
