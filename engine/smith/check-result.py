#!/usr/bin/env python3
"""Checks a Smith's result.json against its mode's schema, in the sandbox.

    check-result <mode> [file] [repo]   file defaults to /work/out/result.json,
                                        repo (propose only) to $SMITH_REPO or /work/repo

The schemas are engine/smith/schemas/<mode>.schema.json (refine, work, review, security,
reproduce, verify, design, replan, parent, accept, propose, research, fix, diagnose, doctor, job, check), the same files the
engine validates with (ReadResultDelegate), copied to /etc/smith/schemas.
Prints every failing field and exits 1, so a Smith can fix its own file
before it exits; prints "ok" and exits 0 when the engine will accept the types.

For propose, a drafted result's pitch page (its `pitch`, relative to the repo) is
also linted against the result's `options`, by a port of the engine's PitchRules
(engine/modules/smith/.../proposal/PitchRules.kt) with the same messages: the
engine refuses the result for any of them, so the Smith runs this after writing
the page. Keep the two in step.

For design, replan and parent, each child's `dependsOn` must name earlier children only, as
ChildDraft.problems says it with the same message; a replan or parent numbers its children after
those its split filed already (the brief's `split.filed`, from $SMITH_WORK/in/brief.json).
The page is read from the repo clone, $SMITH_WORK/repo (SMITH_WORK defaults to
/work, as run-smith.sh sets it), or a third argument; $SMITH_REPO is the repo's
owner/name and is not used here.
"""
import json
import os
import re
import sys

import jsonschema

SCHEMAS = os.environ.get("SMITH_SCHEMAS", "/etc/smith/schemas")
# The port of PitchRules: Kotlin's IGNORE_CASE and DOT_MATCHES_ALL, comments stripped first.
GUIDE = "/etc/smith/pitch-guide.md"
_OPTS = re.IGNORECASE | re.DOTALL | re.ASCII  # Java's \s and \w are ASCII
_COMMENTS = re.compile(r"<!--.*?-->|/\*.*?\*/", _OPTS)
_REMOTE = r"""\s*['"]?\s*(?:https?:|//)"""
_FORBIDDEN = [
    (re.compile(r"<script\b[^>]*\ssrc\s*=", _OPTS), "no <script src>: write no script, or inline it"),
    (re.compile(r"<link\b", _OPTS), "no <link> (a stylesheet or anything else): put the CSS in a <style> block"),
    (re.compile(r"@import\b", _OPTS), "no @import: put the CSS in the page"),
    (re.compile(r"url\(" + _REMOTE, _OPTS), "no remote url() (a web font, an image): use data: URLs or inline SVG"),
    (re.compile(r"\s(?:src|srcset)\s*=" + _REMOTE, _OPTS), "no remote src or srcset: inline the image as SVG or a data: URL"),
    (re.compile(r"<(?!a\b)[a-z][\w:-]*\b[^>]*\s(?:xlink:)?href\s*=" + _REMOTE, _OPTS), "no remote href except a plain <a href> link"),
]
_REQUIRED = [
    (re.compile(r"<svg\b", _OPTS), "no inline <svg>: draw at least one diagram of the mechanism"),
    (re.compile(r"prefers-color-scheme\s*:\s*dark", _OPTS), "no @media (prefers-color-scheme: dark) rule"),
    (re.compile(r"""<meta\b[^>]*\bname\s*=\s*['"]?viewport""", _OPTS), 'no <meta name="viewport"> tag'),
]
_OPTION_ID = re.compile(r"""\sdata-option-id\s*=\s*['"]?([^'"\s>]*)""", _OPTS)
_CHOOSE = re.compile(r"""<button\b[^>]*\sdata-choose\s*=\s*['"]?([^'"\s>]*)[^>]*>""", _OPTS)
_DISABLED = re.compile(r"\sdisabled\b", _OPTS)


def pitch_problems(path, html, options=()):
    """PitchRules.problems: what is wrong with the page `html` at `path`, in its order; empty when fine."""
    text = _COMMENTS.sub(" ", html)
    blocks = [m.group(1) for m in _OPTION_ID.finditer(text)]
    buttons = [(m.group(1), m.group(0)) for m in _CHOOSE.finditer(text)]
    out = [f"{path}: {what} ({GUIDE})" for rule, what in _FORBIDDEN if rule.search(text)]
    out += [f"{path}: {what} ({GUIDE})" for rule, what in _REQUIRED if not rule.search(text)]
    out += [f'{path}: option \'{o}\' has no data-option-id="{o}" block ({GUIDE})' for o in options if o not in blocks]
    out += [f'{path}: option \'{o}\' has no <button disabled data-choose="{o}">Choose this one</button> ({GUIDE})'
            for o in options if not any(b == o for b, _ in buttons)]
    enabled = []
    for b, tag in buttons:
        if b in options and not _DISABLED.search(tag) and b not in enabled:
            enabled.append(b)
    out += [f"{path}: the choose button of option '{o}' is not disabled: the page's button is a placeholder ({GUIDE})" for o in enabled]
    stray = sorted({*blocks, *(b for b, _ in buttons)} - set(options))
    out += [f"{path}: option '{o}' is in the page but not among the result's options ({GUIDE})" for o in stray]
    return out


def propose_problems(result, repo):
    """A drafted propose result's pitch page, linted against its options; a missing page is a problem."""
    if result.get("outcome") != "drafted" or not isinstance(result.get("pitch"), str):
        return []
    pitch = result["pitch"]
    options = [o["id"] for o in result.get("options") or [] if isinstance(o, dict) and isinstance(o.get("id"), str)]
    try:
        with open(os.path.join(repo, pitch), encoding="utf-8") as f:
            html = f.read()
    except (FileNotFoundError, IsADirectoryError):
        return [f"{pitch}: no such file in {repo}: write the pitch page the result names"]
    return pitch_problems(pitch, html, options)


MAX_CHILDREN = 16  # ChildDraft.MAX, when the brief has no `split.max`: filed plus revised, as ReplanResult.problems counts them


def children_problems(result, offset=0):
    """ChildDraft.problems' order rule: each child's dependsOn names earlier children only, numbering from `offset`."""
    out = []
    for i, c in enumerate(result.get("children") or []):
        n = offset + i
        deps = c.get("dependsOn") or [] if isinstance(c, dict) else []
        if any(d < 0 or d >= n for d in deps):
            out.append(f"children[{n}].dependsOn [{', '.join(str(d) for d in deps)}] must name earlier children only (0 to {n - 1})")
    return out


def read_split():
    """The brief's `split` object; None without a brief."""
    brief = os.path.join(os.environ.get("SMITH_WORK", "/work"), "in", "brief.json")
    try:
        with open(brief) as f:
            return json.load(f).get("split") or {}
    except (OSError, ValueError, AttributeError):
        return None


def filed_count():
    """A replan's offset: the children its split filed already, from the brief's `split.filed`; None without a brief."""
    split = read_split()
    return None if split is None else len(split.get("filed") or [])


def max_children():
    """The split's cap, the brief's `split.max` (ChildDraft.MAX); MAX_CHILDREN without it."""
    m = (read_split() or {}).get("max")
    return m if isinstance(m, int) and m > 0 else MAX_CHILDREN


def steer_problems(result):
    """A replan's `steer` entries whose child is not a filed child still open (ReplanResult.problems); none without a brief."""
    split = read_split()
    if split is None:
        return []
    filed = {c.get("number"): c.get("state", "open") for c in split.get("filed") or [] if isinstance(c, dict)}
    if split.get("closedChild") in filed:  # the child this replan follows has closed, whatever "filed" still says
        filed[split["closedChild"]] = "split" if split.get("closedChildSplit") else "merged" if split.get("closedChildPr") else "satisfied"
    out = []
    for i, s in enumerate(result.get("steer") or []):
        child = s.get("child") if isinstance(s, dict) else None
        if child not in filed:
            out.append(f"steer[{i}]: #{child} is not a filed child of this split")
        elif filed[child] != "open":
            out.append(f"steer[{i}]: #{child} is {filed[child]}, not open: only an open child can be steered")
    return out


def main(argv):
    if len(argv) not in (2, 3, 4) or (len(argv) == 4 and argv[1] != "propose"):
        print(__doc__.strip(), file=sys.stderr)
        return 2
    mode = argv[1]
    path = argv[2] if len(argv) >= 3 else "/work/out/result.json"
    schema_path = os.path.join(SCHEMAS, f"{mode}.schema.json")
    if not os.path.isfile(schema_path):
        print(f"check-result: no schema for mode '{mode}' ({schema_path})", file=sys.stderr)
        return 2
    with open(schema_path) as f:
        schema = json.load(f)
    try:
        with open(path) as f:
            result = json.load(f)
    except FileNotFoundError:
        print(f"{path}: no such file")
        return 1
    except json.JSONDecodeError as e:
        print(f"{path}: not JSON: {e}")
        return 1
    validator = jsonschema.Draft202012Validator(schema)
    errors = sorted(validator.iter_errors(result), key=lambda e: list(e.absolute_path))
    for e in errors:
        field = "/" + "/".join(str(p) for p in e.absolute_path)
        print(f"{field}: {e.message}")
        for sub in e.context or []:
            print(f"    {sub.message}")
    if errors:
        print(f"{path} fails {mode}.schema.json: fix the fields above and run this again")
        return 1
    if mode == "propose":
        repo = argv[3] if len(argv) == 4 else os.path.join(os.environ.get("SMITH_WORK", "/work"), "repo")
        problems = propose_problems(result, repo)
        for p in problems:
            print(p)
        if problems:
            print(f"{path}: the engine refuses this result for the pitch problems above: fix the page or the options and run this again")
            return 1
    if mode in ("design", "replan", "parent"):
        offset = 0 if mode == "design" else filed_count()
        if offset is None:  # no brief: only a negative index is sure to be wrong
            problems = [f"children[{i}].dependsOn names a negative index" for i, c in enumerate(result.get("children") or [])
                        if isinstance(c, dict) and any(d < 0 for d in c.get("dependsOn") or [])]
        else:
            problems = children_problems(result, offset)
            n = len(result.get("children") or [])
            if mode in ("replan", "parent") and result.get("outcome") == "revise" and offset + n > (cap := max_children()):
                problems.insert(0, f"a split has at most {cap} parts over its life: {offset} filed plus {n} revised is {offset + n}")
        if mode in ("replan", "parent"):
            problems += steer_problems(result)
        for p in problems:
            print(p)
        if problems:
            print(f"{path}: the engine refuses this result for the children or steers above: fix them and run this again")
            return 1
    print("ok")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
