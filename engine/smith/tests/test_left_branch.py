"""A work run with no PR in the brief, on a ticket whose branch was left on origin after its PR merged, pushes fast-forward (#2543)."""

import os
import shutil
import subprocess
import tempfile
import unittest

from test_propose_redraft import FAKE_GIT, RUN_SMITH, sh

# claude commits one change, as a work run would.
FAKE_CLAUDE = """#!/bin/sh
cat > /dev/null
echo new > new.txt && git add new.txt && git commit -qm "round 2"
exit 0
"""


class LeftBranch(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.mkdtemp()
        self.origin = os.path.join(self.tmp, "origin")
        os.mkdir(self.origin)
        sh(self.origin, "init", "-q", "-b", "main")
        sh(self.origin, "config", "user.email", "a@b")
        sh(self.origin, "config", "user.name", "a")
        self.commit("f", "a", "base")
        sh(self.origin, "checkout", "-qb", "smith/issue-7")
        self.commit("f", "b", "round 1")
        sh(self.origin, "checkout", "-q", "main")
        self.commit("f", "b", "squash-merge of the PR")
        self.commit("g", "c", "main moved")
        os.makedirs(os.path.join(self.tmp, "in"))
        os.makedirs(os.path.join(self.tmp, "out"))

    def tearDown(self):
        shutil.rmtree(self.tmp)

    def commit(self, name, text, msg):
        open(os.path.join(self.origin, name), "w").write(text)
        sh(self.origin, "add", name)
        sh(self.origin, "commit", "-qm", msg)

    def test_the_bundle_contains_the_left_branch_and_carries_the_default_branchs_tree(self):
        open(os.path.join(self.tmp, "in", "brief.json"), "w").write('{"pr":null}')
        bin_dir = os.path.join(self.tmp, "bin")
        os.makedirs(bin_dir)
        for name, body in (("git", FAKE_GIT), ("claude", FAKE_CLAUDE)):
            open(os.path.join(bin_dir, name), "w").write(body)
            os.chmod(os.path.join(bin_dir, name), 0o755)
        env = {"PATH": bin_dir + os.pathsep + os.environ["PATH"], "HOME": self.tmp, "SMITH_WORK": self.tmp,
               "ORIGIN": self.origin, "SMITH_REPO": "acme/widgets", "SMITH_ISSUE": "7", "SMITH_ID": "run-1",
               "SMITH_MODE": "work", "GH_TOKEN": "x", "SMITH_GIT_NAME": "a", "SMITH_GIT_EMAIL": "a@b",
               "SMITH_RETRO": "0", "SMITH_DESKTOP_DEPS": os.path.join(self.tmp, "none")}
        p = subprocess.run(["bash", RUN_SMITH], env=env, capture_output=True, text=True, timeout=120)
        bundle = os.path.join(self.tmp, "out", "commits.bundle")
        self.assertTrue(os.path.exists(bundle), p.stdout + p.stderr)
        live = sh(self.origin, "rev-parse", "smith/issue-7")
        sh(self.origin, "fetch", "-q", bundle, "refs/heads/smith/issue-7:refs/heads/pushed")
        # CliGitPush.refuseRewrite: the bundle's ref must contain the live branch head.
        subprocess.run(["git", "merge-base", "--is-ancestor", live, "pushed"], cwd=self.origin, check=True)
        changed = sh(self.origin, "diff", "--name-only", "main", "pushed").splitlines()
        self.assertEqual(["new.txt"], changed)


if __name__ == "__main__":
    unittest.main()
