"""run-smith.sh exits 69 (EX_UNAVAILABLE) with a line naming the step when its git clone fails (#1370)."""

import os
import subprocess
import tempfile
import unittest

SMITH_DIR = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
RUN_SMITH = os.path.join(SMITH_DIR, "run-smith.sh")

FAKE_GIT = """#!/bin/sh
[ "$1" = clone ] && { echo "fatal: unable to access remote" >&2; exit 128; }
exit 0
"""


class CloneFails(unittest.TestCase):
    def test_a_failed_clone_exits_69_naming_the_step(self):
        with tempfile.TemporaryDirectory() as work:
            bin_dir = os.path.join(work, "bin")
            os.mkdir(bin_dir)
            git = os.path.join(bin_dir, "git")
            open(git, "w").write(FAKE_GIT)
            os.chmod(git, 0o755)
            env = {"PATH": bin_dir + os.pathsep + os.environ["PATH"], "HOME": work, "SMITH_WORK": work, "WORK": work,
                   "SMITH_REPO": "acme/widgets", "SMITH_ISSUE": "7", "SMITH_ID": "run-1", "GH_TOKEN": "x",
                   "SMITH_GIT_NAME": "a", "SMITH_GIT_EMAIL": "a@b"}
            p = subprocess.run(["bash", RUN_SMITH], env=env, capture_output=True, text=True, timeout=60)
            self.assertEqual(69, p.returncode, p.stderr)
            self.assertIn("smith: git clone of acme/widgets failed (128)", p.stderr)


if __name__ == "__main__":
    unittest.main()
