"""A propose run's stored draft with no PR branch is replayed onto a default branch that moved since the draft was cut (#2378)."""

import os
import shutil
import subprocess
import tempfile
import unittest

SMITH_DIR = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
RUN_SMITH = os.path.join(SMITH_DIR, "run-smith.sh")
REAL_GIT = shutil.which("git")

# The clone of github.com goes to the local origin instead; everything else is the real git.
FAKE_GIT = """#!/bin/sh
if [ "$1" = clone ]; then
  shift; args=""
  for a in "$@"; do case "$a" in https://github.com/*) a="file://$ORIGIN" ;; esac; args="$args '$a'"; done
  eval exec "{git}" clone $args
fi
exec "{git}" "$@"
""".replace("{git}", REAL_GIT)

# claude records the branch it was started on.
FAKE_CLAUDE = """#!/bin/sh
cat > /dev/null
git log --format=%s > "$SMITH_WORK/started.log"
exit 0
"""


def sh(cwd, *args):
    return subprocess.run(["git", *args], cwd=cwd, check=True, capture_output=True, text=True).stdout.strip()


class ProposeRedraft(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.mkdtemp()
        self.origin = os.path.join(self.tmp, "origin")
        os.mkdir(self.origin)
        sh(self.origin, "init", "-q", "-b", "main")
        sh(self.origin, "config", "user.email", "a@b")
        sh(self.origin, "config", "user.name", "a")
        self.commit("f", "a", "base")
        sh(self.origin, "checkout", "-qb", "smith/issue-7")
        self.commit("draft.md", "d", "draft")
        os.makedirs(os.path.join(self.tmp, "in"))
        sh(self.origin, "bundle", "create", "-q", os.path.join(self.tmp, "in", "commits.bundle"), "main..smith/issue-7")
        sh(self.origin, "checkout", "-q", "main")

    def tearDown(self):
        shutil.rmtree(self.tmp)

    def commit(self, name, text, msg):
        open(os.path.join(self.origin, name), "w").write(text)
        sh(self.origin, "add", name)
        sh(self.origin, "commit", "-qm", msg)

    def run_smith(self, brief="{}"):
        open(os.path.join(self.tmp, "in", "brief.json"), "w").write(brief)
        bin_dir = os.path.join(self.tmp, "bin")
        os.makedirs(bin_dir, exist_ok=True)
        for name, body in (("git", FAKE_GIT), ("claude", FAKE_CLAUDE)):
            open(os.path.join(bin_dir, name), "w").write(body)
            os.chmod(os.path.join(bin_dir, name), 0o755)
        env = {"PATH": bin_dir + os.pathsep + os.environ["PATH"], "HOME": self.tmp, "SMITH_WORK": self.tmp,
               "ORIGIN": self.origin, "SMITH_REPO": "acme/widgets", "SMITH_ISSUE": "7", "SMITH_ID": "run-1",
               "SMITH_MODE": "propose", "GH_TOKEN": "x", "SMITH_GIT_NAME": "a", "SMITH_GIT_EMAIL": "a@b",
               "SMITH_RETRO": "0", "SMITH_DESKTOP_DEPS": os.path.join(self.tmp, "none")}
        return subprocess.run(["bash", RUN_SMITH], env=env, capture_output=True, text=True, timeout=120)

    def started(self):
        return open(os.path.join(self.tmp, "started.log")).read().splitlines()

    def test_a_moved_default_branch_gets_the_draft_replayed_on_top(self):
        self.commit("g", "b", "main moved")
        p = self.run_smith()
        self.assertEqual(0, p.returncode, p.stderr)
        self.assertEqual(["draft", "main moved", "base"], self.started())

    def test_an_unmoved_default_branch_takes_the_draft_as_it_is(self):
        p = self.run_smith()
        self.assertEqual(0, p.returncode, p.stderr)
        self.assertEqual(["draft", "base"], self.started())

    def test_a_draft_cut_past_the_shallow_clone_still_applies(self):
        for i in range(55):
            self.commit("g", str(i), "main moved")
        p = self.run_smith()
        self.assertEqual(0, p.returncode, p.stderr)
        self.assertEqual("draft", self.started()[0])

    def test_a_conflict_stops_the_run_naming_it(self):
        self.commit("draft.md", "other", "main moved")
        p = self.run_smith()
        self.assertEqual(78, p.returncode, p.stderr)
        self.assertIn("conflicts with the default branch", p.stderr)
        self.assertFalse(os.path.exists(os.path.join(self.tmp, "started.log")))

    def test_a_moved_pr_branch_is_still_refused(self):
        sh(self.origin, "checkout", "-qb", "pr-branch", "smith/issue-7")
        self.commit("h", "x", "pushed elsewhere")
        sh(self.origin, "checkout", "-q", "main")
        p = self.run_smith('{"pr": {"branch": "pr-branch"}}')
        self.assertEqual(78, p.returncode, p.stderr)
        self.assertIn("the branch moved under it", p.stderr)


if __name__ == "__main__":
    unittest.main()
