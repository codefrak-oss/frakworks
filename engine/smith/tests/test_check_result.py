"""Tests for engine/smith/check-result.py's propose lint (engine/README.md, "Smith results").

The pitch rules are a port of the engine's PitchRules: these mirror PitchRulesTest
(engine/modules/app/src/test/kotlin/com/codefrak/engine/proposal/PitchRulesTest.kt),
running the script with `python3 -I` on a temporary repo, and the page is the pitch
guide's own example, read from engine/smith/pitch-guide.md.
"""

import json
import os
import re
import subprocess
import sys
import tempfile
import unittest

SMITH_DIR = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
CHECK = os.path.join(SMITH_DIR, "check-result.py")
SCHEMAS = os.path.join(SMITH_DIR, "schemas")
GUIDE = "/etc/smith/pitch-guide.md"
PITCH = "docs/proposals/widgets.html"

with open(os.path.join(SMITH_DIR, "pitch-guide.md"), encoding="utf-8") as f:
    EXAMPLE = re.search(r"```html\n(.*?)```", f.read(), re.DOTALL).group(1)

OPTIONS = """<section data-option-id="a"><h2>A</h2><button disabled data-choose="a">Choose this one</button></section>
        <section data-option-id='b'><h2>B</h2><button type="button" data-choose="b" disabled>Choose this one</button></section></main>"""


def example(test, old, new):
    test.assertIn(old, EXAMPLE)
    return EXAMPLE.replace(old, new)


class CheckResultTest(unittest.TestCase):
    def check(self, html, options=None, outcome="drafted", mode="propose", write_page=True):
        """Runs check-result on a result naming PITCH; answers (exit code, output lines)."""
        with tempfile.TemporaryDirectory() as tmp:
            repo = os.path.join(tmp, "repo")
            os.makedirs(os.path.join(repo, "docs", "proposals"))
            if write_page:
                with open(os.path.join(repo, PITCH), "w", encoding="utf-8") as f:
                    f.write(html)
            if outcome == "drafted":
                result = {"outcome": "drafted", "path": "docs/proposals/widgets.md", "pitch": PITCH,
                          "summary": "Widgets.", "questions": []}
                if options is not None:
                    result["options"] = [{"id": o, "label": o.upper()} for o in options]
            else:
                result = {"outcome": "ask", "question": "Which widgets?"}
            path = os.path.join(tmp, "result.json")
            with open(path, "w") as f:
                json.dump(result, f)
            env = dict(os.environ, SMITH_SCHEMAS=SCHEMAS)
            run = subprocess.run([sys.executable, "-I", CHECK, mode, path] + ([repo] if mode == "propose" else []), capture_output=True, text=True, env=env)
            return run.returncode, run.stdout.splitlines()

    def refused(self, html, what):
        code, lines = self.check(html)
        self.assertEqual(1, code, lines)
        self.assertTrue(any(what in l and GUIDE in l and l.startswith(f"{PITCH}: ") for l in lines), f"{what}: {lines}")

    def passes(self, html, options=None):
        self.assertEqual((0, ["ok"]), self.check(html, options))

    def test_the_guides_own_example_page_passes(self):
        self.passes(EXAMPLE)

    def test_the_committed_pitches_pass_with_their_own_markers(self):
        proposals = os.path.join(os.path.dirname(os.path.dirname(SMITH_DIR)), "docs", "proposals")
        for name in sorted(os.listdir(proposals)) if os.path.isdir(proposals) else []:
            if not name.endswith(".html"):
                continue
            with open(os.path.join(proposals, name), encoding="utf-8") as f:
                html = f.read()
            ids = sorted(set(re.findall(r"""\sdata-option-id\s*=\s*['"]?([^'"\s>]*)""", html, re.I)))
            code, lines = self.check(html, ids)
            # Only the option rules are this test's: an older pitch may predate a page rule.
            self.assertFalse([l for l in lines if "option '" in l], f"{name}: {lines}")

    def test_links_data_urls_fragments_and_inline_script_pass(self):
        self.passes(example(self, "</main>", """<p><a href="https://github.com/acme/widgets/pull/7">the PR</a> <a
            href='//example.com/x'>x</a></p>
            <div style="background: url(data:image/png;base64,AAAA)"></div><div style="fill: url( '#arrow' )"></div>
            <img src="data:image/svg+xml;base64,AAAA" alt=""><script>document.title = 'x'</script>
            </main>"""))

    def test_a_script_src_is_refused(self):
        self.refused(example(self, "</body>", '<script src="https://cdn.example.com/x.js"></script></body>'), "no <script src>")
        self.refused(example(self, "</body>", "<SCRIPT type=module SRC=app.js></SCRIPT></body>"), "no <script src>")

    def test_a_remote_stylesheet_is_refused(self):
        self.refused(example(self, "<style>", '<link rel="stylesheet" href="https://cdn.example.com/x.css">\n<style>'), "no <link>")
        self.refused(example(self, "<style>", "<link rel=stylesheet href=local.css>\n<style>"), "no <link>")

    def test_an_import_is_refused(self):
        self.refused(example(self, "<style>", "<style>\n  @import 'theme.css';"), "no @import")

    def test_a_remote_font_url_is_refused(self):
        self.refused(example(self, "<style>", "<style>\n  @font-face { font-family: X; src: url(\"https://fonts.example.com/x.woff2\") format('woff2'); }"),
                     "no remote url()")
        self.refused(example(self, "<style>", "<style>\n  @font-face { font-family: X; src: url(//fonts.example.com/x.woff2); }"), "no remote url()")

    def test_a_remote_image_or_embedded_href_is_refused(self):
        self.refused(example(self, "</main>", '<img src="https://example.com/x.png" alt=""></main>'), "no remote src")
        self.refused(example(self, "</g>\n</svg>", '</g><image href="https://example.com/x.png"/>\n</svg>'), "no remote href")

    def test_a_page_with_no_svg_is_refused(self):
        start = EXAMPLE.index("<svg"); end = EXAMPLE.index("</svg>") + len("</svg>")
        self.refused(EXAMPLE[:start] + EXAMPLE[end:], "no inline <svg>")

    def test_a_page_with_no_dark_scheme_rule_is_refused(self):
        self.refused(example(self, "prefers-color-scheme: dark", "prefers-color-scheme: light"), "no @media (prefers-color-scheme: dark)")
        # Commented out is not there.
        self.refused(example(self, "@media (prefers-color-scheme: dark) {", "/* @media (prefers-color-scheme: dark) */ @media print {"),
                     "no @media (prefers-color-scheme: dark)")

    def test_a_page_with_no_viewport_meta_is_refused(self):
        self.refused(example(self, '<meta name="viewport" content="width=device-width, initial-scale=1">', ""), 'no <meta name="viewport">')

    def test_declared_options_with_their_blocks_and_disabled_placeholders_pass(self):
        self.passes(example(self, "</main>", OPTIONS), ["a", "b"])

    def test_an_option_with_no_block_or_no_choose_button_is_refused(self):
        code, lines = self.check(example(self, "</main>", OPTIONS), ["a", "b", "c"])
        self.assertEqual(1, code)
        self.assertIn(f"{PITCH}: option 'c' has no data-option-id=\"c\" block ({GUIDE})", lines)
        self.assertIn(f"{PITCH}: option 'c' has no <button disabled data-choose=\"c\">Choose this one</button> ({GUIDE})", lines)

    def test_an_option_in_the_page_but_not_declared_or_a_live_choose_button_is_refused(self):
        self.refused(example(self, "</main>", OPTIONS), "option 'a' is in the page but not among the result's options")
        live = example(self, "</main>", OPTIONS.replace('<button disabled data-choose="a">', '<button data-choose="a">'))
        code, lines = self.check(live, ["a", "b"])
        self.assertEqual(1, code)
        self.assertIn(f"{PITCH}: the choose button of option 'a' is not disabled: the page's button is a placeholder ({GUIDE})", lines)

    def test_the_1738_case_page_marks_a_b_c_and_the_result_lists_no_options(self):
        marked = "".join(f'<section data-option-id="{o}"><button disabled data-choose="{o}">Choose this one</button></section>' for o in "abc")
        code, lines = self.check(example(self, "</main>", marked + "</main>"))
        self.assertEqual(1, code)
        self.assertEqual([f"{PITCH}: option '{o}' is in the page but not among the result's options ({GUIDE})" for o in "abc"],
                         [l for l in lines if "is in the page but not among the result's options" in l])

    def test_a_missing_pitch_is_a_problem_not_a_crash(self):
        code, lines = self.check("", write_page=False)
        self.assertEqual(1, code)
        self.assertTrue(lines[0].startswith(f"{PITCH}: no such file"), lines)

    def test_an_ask_is_not_linted(self):
        self.assertEqual((0, ["ok"]), self.check("", outcome="ask", write_page=False))


class ChildOrderTest(unittest.TestCase):
    """ChildDraft.problems' order rule: a child's dependsOn names earlier children only (#2412)."""

    def check(self, deps, mode="design", filed=None):
        children = [{"title": t, "body": t, "kind": "chore", "dependsOn": d, "points": 1} for t, d in zip("abcd", deps)]
        result = {"outcome": "split" if mode == "design" else "revise", "children": children}
        with tempfile.TemporaryDirectory() as tmp:
            path = os.path.join(tmp, "result.json")
            with open(path, "w") as f:
                json.dump(result, f)
            if filed is not None:
                os.makedirs(os.path.join(tmp, "in"))
                with open(os.path.join(tmp, "in", "brief.json"), "w") as f:
                    json.dump({"split": {"filed": [{"index": i} for i in range(filed)]}}, f)
            env = dict(os.environ, SMITH_SCHEMAS=SCHEMAS, SMITH_WORK=tmp)
            run = subprocess.run([sys.executable, "-I", CHECK, mode, path], capture_output=True, text=True, env=env)
            return run.returncode, run.stdout.splitlines()

    def test_a_forward_reference_is_refused(self):
        code, lines = self.check([[], [0], [1, 3], []])
        self.assertEqual(1, code, lines)
        self.assertIn("children[2].dependsOn [1, 3] must name earlier children only (0 to 1)", lines)

    def test_a_self_reference_is_refused(self):
        code, lines = self.check([[], [1]])
        self.assertEqual(1, code, lines)
        self.assertIn("children[1].dependsOn [1] must name earlier children only (0 to 0)", lines)

    def test_a_valid_ordering_passes(self):
        self.assertEqual((0, ["ok"]), self.check([[], [0], [0, 1]]))

    def test_a_replan_numbers_after_the_filed_children(self):
        self.assertEqual((0, ["ok"]), self.check([[0, 1], [2]], mode="replan", filed=2))
        code, lines = self.check([[0, 2]], mode="replan", filed=2)
        self.assertEqual(1, code, lines)
        self.assertIn("children[2].dependsOn [0, 2] must name earlier children only (0 to 1)", lines)

    def test_a_replan_revise_over_the_child_cap_is_refused_as_the_engine_refuses_it(self):
        # #2475: 9 filed plus 2 revised passed here and ReplanResult.problems then refused it.
        # #2479: the cap is 16; 9 filed plus 2 revised now passes.
        self.assertEqual((0, ["ok"]), self.check([[], []], mode="replan", filed=9))
        code, lines = self.check([[], []], mode="replan", filed=15)
        self.assertEqual(1, code, lines)
        self.assertIn("a split has at most 16 parts over its life: 15 filed plus 2 revised is 17", lines)
        self.assertEqual((0, ["ok"]), self.check([[], [0]], mode="replan", filed=14))


if __name__ == "__main__":
    unittest.main()
