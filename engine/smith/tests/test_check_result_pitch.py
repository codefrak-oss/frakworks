"""Tests for check-result.py's pitch lint on a propose result, the Python twin of PitchRules.

Each test runs the script with -I against a scratch result and a scratch clone holding
the page; the page is the minimal compliant page of engine/smith/pitch-guide.md, read
from the guide itself, which PitchRulesTest also lints verbatim.
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
PITCH = "docs/proposals/widgets.html"
OPTION = '<section data-option-id="a"><h2>Blue</h2><button disabled data-choose="a">Choose this one</button></section>\n'


def guide_page():
    with open(os.path.join(SMITH_DIR, "pitch-guide.md"), encoding="utf-8") as f:
        guide = f.read()
    section = guide.split("## A minimal compliant page", 1)[1]
    return re.search(r"```html\n(.*?)```", section, re.DOTALL).group(1)


class PitchLintTest(unittest.TestCase):
    def run_check(self, page, smith_repo=None, **extra):
        with tempfile.TemporaryDirectory() as tmp:
            repo = os.path.join(tmp, "repo")
            if page is not None:
                os.makedirs(os.path.join(repo, os.path.dirname(PITCH)))
                with open(os.path.join(repo, PITCH), "w", encoding="utf-8") as f:
                    f.write(page)
            result = {"outcome": "drafted", "path": "docs/proposals/widgets.md", "pitch": PITCH,
                      "summary": "Widgets.", "questions": [], **extra}
            path = os.path.join(tmp, "result.json")
            with open(path, "w") as f:
                json.dump(result, f)
            env = dict(os.environ, SMITH_SCHEMAS=SCHEMAS, SMITH_WORK=tmp)
            env.pop("SMITH_REPO", None)
            if smith_repo is not None:
                env["SMITH_REPO"] = smith_repo
            p = subprocess.run([sys.executable, "-I", CHECK, "propose", path],
                               env=env, capture_output=True, text=True)
            return p.returncode, p.stdout

    def with_option(self):
        return guide_page().replace("</main>", OPTION + "</main>", 1)

    def test_the_guide_page_without_options_passes(self):
        code, out = self.run_check(guide_page())
        self.assertEqual((0, "ok\n"), (code, out))

    def test_an_option_in_the_page_but_not_in_the_result_fails(self):
        code, out = self.run_check(self.with_option())
        self.assertEqual(1, code)
        self.assertIn("option 'a' is in the page but not among the result's options", out)

    def test_an_option_in_both_passes(self):
        code, out = self.run_check(self.with_option(), options=[{"id": "a", "label": "Blue"}])
        self.assertEqual((0, "ok\n"), (code, out))

    def test_a_link_tag_fails(self):
        code, out = self.run_check(guide_page().replace("</head>", '<link rel="icon" href="x.png">\n</head>', 1))
        self.assertEqual(1, code)
        self.assertIn("no <link>", out)

    def test_an_owner_name_smith_repo_does_not_move_the_page(self):
        code, out = self.run_check(guide_page(), smith_repo="codefrak-oss/frakworks")
        self.assertEqual((0, "ok\n"), (code, out))

    def test_a_missing_page_fails_naming_it(self):
        code, out = self.run_check(None)
        self.assertEqual(1, code)
        self.assertIn(PITCH, out)


if __name__ == "__main__":
    unittest.main()
