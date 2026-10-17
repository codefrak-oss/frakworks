"""scripts/check-deployment-values.sh and scripts/install-hooks.sh, against throwaway git repos.

The patterns here are made up for the test; none names a real host or domain.
"""
from __future__ import annotations

import os
import subprocess
import tempfile
import unittest

HERE = os.path.dirname(os.path.abspath(__file__))
CHECK = os.path.join(HERE, "..", "check-deployment-values.sh")
INSTALL = os.path.join(HERE, "..", "install-hooks.sh")
PATTERN = r"zz-secret-[0-9]+\.invalid"
ZERO = "0" * 40


class CheckTest(unittest.TestCase):
    def setUp(self):
        self.dir = tempfile.mkdtemp()
        self.env = {k: v for k, v in os.environ.items() if not k.startswith("FRAKWORKS_DEPLOYMENT")}
        self.env.update(GIT_AUTHOR_NAME="t", GIT_AUTHOR_EMAIL="t@t", GIT_COMMITTER_NAME="t", GIT_COMMITTER_EMAIL="t@t",
                        GIT_CONFIG_GLOBAL="/dev/null")
        self.git("init", "-q", "-b", "main")
        self.commit("README.md", "hi\n")
        self.base = self.git("rev-parse", "HEAD")

    def git(self, *args):
        return subprocess.run(["git", *args], cwd=self.dir, env=self.env, check=True, capture_output=True, text=True).stdout.strip()

    def commit(self, path, text):
        full = os.path.join(self.dir, path)
        os.makedirs(os.path.dirname(full), exist_ok=True)
        with open(full, "w") as f:
            f.write(text)
        self.git("add", path)
        self.git("commit", "-q", "-m", f"add {path}")
        return self.git("rev-parse", "HEAD")

    def check(self, *args, stdin=None, **env):
        return subprocess.run(["bash", CHECK, *args], cwd=self.dir, env={**self.env, **env}, input=stdin,
                              capture_output=True, text=True)

    def test_a_hit_refuses_with_file_and_line_and_never_the_pattern_or_text(self):
        self.commit("conf/hosts.txt", "one\ntwo\nhost zz-secret-42.invalid here\n")
        r = self.check(f"{self.base}..HEAD", FRAKWORKS_DEPLOYMENT_PATTERNS=f"# ours\n\n{PATTERN}\n")
        self.assertEqual(1, r.returncode, r.stderr)
        self.assertIn("conf/hosts.txt:3 (commit ", r.stderr)
        for out in (r.stdout, r.stderr):
            self.assertNotIn("zz-secret", out)
            self.assertNotIn(PATTERN, out)

    def test_patterns_from_a_file(self):
        self.commit("a.txt", "zz-secret-1.invalid\n")
        with open(os.path.join(self.dir, "..", "pats"), "w") as f:
            f.write(PATTERN + "\n")
        r = self.check("--patterns", os.path.join(self.dir, "..", "pats"), f"{self.base}..HEAD")
        self.assertEqual(1, r.returncode)
        self.assertIn("a.txt:1", r.stderr)

    def test_an_allow_line_passes(self):
        self.commit("a.txt", "see zz-secret-1.invalid in the docs\n")
        r = self.check(f"{self.base}..HEAD", FRAKWORKS_DEPLOYMENT_PATTERNS=PATTERN,
                       FRAKWORKS_DEPLOYMENT_ALLOW="see zz-secret-1.invalid in the docs")
        self.assertEqual(0, r.returncode, r.stderr)

    def test_no_patterns_passes_with_a_notice(self):
        self.commit("a.txt", "zz-secret-1.invalid\n")
        r = self.check(f"{self.base}..HEAD")
        self.assertEqual(0, r.returncode)
        self.assertEqual(1, len(r.stdout.strip().splitlines()))
        self.assertIn("no deployment patterns", r.stdout)

    def test_pre_push_of_a_new_branch_checks_its_commits(self):
        head = self.commit("b.txt", "x\nzz-secret-7.invalid\n")
        r = self.check("--pre-push", stdin=f"refs/heads/topic {head} refs/heads/topic {ZERO}\n",
                       FRAKWORKS_DEPLOYMENT_PATTERNS=PATTERN)
        self.assertEqual(1, r.returncode, r.stdout + r.stderr)
        self.assertIn("b.txt:2", r.stderr)

    def test_pre_push_of_an_update_checks_only_new_commits(self):
        self.commit("old.txt", "zz-secret-1.invalid\n")
        pushed = self.git("rev-parse", "HEAD")
        head = self.commit("new.txt", "clean\n")
        r = self.check("--pre-push", stdin=f"refs/heads/main {head} refs/heads/main {pushed}\n",
                       FRAKWORKS_DEPLOYMENT_PATTERNS=PATTERN)
        self.assertEqual(0, r.returncode, r.stderr)

    def test_pre_push_of_a_deletion_passes(self):
        self.commit("a.txt", "zz-secret-1.invalid\n")
        r = self.check("--pre-push", stdin=f"(delete) {ZERO} refs/heads/gone {self.base}\n",
                       FRAKWORKS_DEPLOYMENT_PATTERNS=PATTERN)
        self.assertEqual(0, r.returncode, r.stderr)


class InstallTest(unittest.TestCase):
    def setUp(self):
        self.home = tempfile.mkdtemp()
        self.env = {**os.environ, "HOME": self.home, "XDG_CONFIG_HOME": os.path.join(self.home, ".config")}
        self.env.pop("GIT_CONFIG_GLOBAL", None)

    def run_install(self):
        return subprocess.run(["bash", INSTALL], env=self.env, capture_output=True, text=True)

    def hooks_path(self):
        return subprocess.run(["git", "config", "--global", "--get", "core.hooksPath"], env=self.env,
                              capture_output=True, text=True).stdout.strip()

    def test_installs_idempotently(self):
        first = self.run_install()
        self.assertEqual(0, first.returncode, first.stderr)
        hooks = os.path.join(self.home, ".config", "frakworks", "git-hooks")
        self.assertEqual(hooks, self.hooks_path())
        self.assertIn("wrote", first.stdout)
        self.assertTrue(os.access(os.path.join(hooks, "pre-push"), os.X_OK))
        second = self.run_install()
        self.assertEqual(0, second.returncode)
        self.assertIn("up to date", second.stdout)
        self.assertNotIn("wrote", second.stdout)

    def test_a_different_hooks_path_stops_it(self):
        subprocess.run(["git", "config", "--global", "core.hooksPath", "/elsewhere"], env=self.env, check=True)
        r = self.run_install()
        self.assertEqual(1, r.returncode)
        self.assertIn("/elsewhere", r.stderr)
        self.assertEqual("/elsewhere", self.hooks_path())

    def test_the_hook_refuses_a_push_with_a_hit(self):
        self.assertEqual(0, self.run_install().returncode)
        cfg = os.path.join(self.home, ".config", "frakworks")
        with open(os.path.join(cfg, "deployment-patterns"), "w") as f:
            f.write(PATTERN + "\n")
        remote, work = tempfile.mkdtemp(), tempfile.mkdtemp()
        env = {**self.env, "GIT_AUTHOR_NAME": "t", "GIT_AUTHOR_EMAIL": "t@t", "GIT_COMMITTER_NAME": "t", "GIT_COMMITTER_EMAIL": "t@t"}
        g = lambda *a, cwd=work: subprocess.run(["git", *a], cwd=cwd, env=env, capture_output=True, text=True)
        g("init", "-q", "--bare", cwd=remote); g("init", "-q", "-b", "main")
        with open(os.path.join(work, "a.txt"), "w") as f:
            f.write("zz-secret-3.invalid\n")
        g("add", "a.txt"); g("commit", "-q", "-m", "a")
        pushed = g("push", remote, "main")
        self.assertNotEqual(0, pushed.returncode)
        self.assertIn("a.txt:1", pushed.stderr)
        self.assertNotIn("zz-secret", pushed.stderr)


if __name__ == "__main__":
    unittest.main()
