"""Tests for run-smith.sh's handoff of the desktop's baked node_modules.

The script runs end to end in a scratch SMITH_WORK against a local git repo
standing in for GitHub (with an observability/desktop/ of its own) and a stub
`claude`, with SMITH_DESKTOP_DEPS pointing at a scratch stand-in for the
image's /opt/desktop-deps.
"""

import json
import os
import shutil
import subprocess
import tempfile
import unittest

from test_prompts import CLAUDE_STUB, FRAKWORKS, BRANCH, free_port

SMITH_DIR = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
RUN_SMITH = os.path.join(SMITH_DIR, "run-smith.sh")
LOCK = '{"lockfileVersion": 3}\n'
NEWER = "package-lock.json is newer than the image's"


@unittest.skipUnless(shutil.which("git") and shutil.which("jq") and shutil.which("bash"),
                     "run-smith.sh needs bash, git and jq")
class DesktopDeps(unittest.TestCase):

    def setUp(self):
        self.tmp = tempfile.mkdtemp()
        self.addCleanup(shutil.rmtree, self.tmp, True)
        self.env = {"PATH": os.environ["PATH"], "HOME": self.tmp,
                    "GIT_CONFIG_GLOBAL": os.path.join(self.tmp, "gitconfig"),
                    "GIT_AUTHOR_NAME": "t", "GIT_AUTHOR_EMAIL": "t@t", "GIT_COMMITTER_NAME": "t", "GIT_COMMITTER_EMAIL": "t@t"}
        self.origin = os.path.join(self.tmp, "origin")
        os.makedirs(os.path.join(self.origin, "observability", "desktop"))
        with open(os.path.join(self.origin, "observability", "desktop", "package.json"), "w") as f:
            f.write("{}\n")
        with open(os.path.join(self.origin, "observability", "desktop", ".gitignore"), "w") as f:
            f.write("node_modules/\n")
        with open(self.env["GIT_CONFIG_GLOBAL"], "w") as f:
            f.write(f'[url "file://{self.origin}"]\n\tinsteadOf = https://github.com/{FRAKWORKS}\n')
        # The image's baked deps: one module, and the lock file they were installed from.
        self.deps = os.path.join(self.tmp, "desktop-deps")
        os.makedirs(os.path.join(self.deps, "node_modules", "esbuild"))
        with open(os.path.join(self.deps, "node_modules", "esbuild", "package.json"), "w") as f:
            f.write('{"name": "esbuild"}\n')
        with open(os.path.join(self.deps, "package-lock.json"), "w") as f:
            f.write(LOCK)
        bin_dir = os.path.join(self.tmp, "bin")
        os.makedirs(bin_dir)
        with open(os.path.join(bin_dir, "claude"), "w") as f:
            f.write(CLAUDE_STUB)
        os.chmod(os.path.join(bin_dir, "claude"), 0o755)
        self.env["PATH"] = bin_dir + os.pathsep + self.env["PATH"]

    def commit_lock(self, text):
        with open(os.path.join(self.origin, "observability", "desktop", "package-lock.json"), "w") as f:
            f.write(text)
        for args in (["init", "-q", "-b", "main"], ["add", "-A"], ["commit", "-q", "-m", "base"],
                     ["branch", BRANCH]):
            subprocess.run(["git", *args], cwd=self.origin, env=self.env, check=True, capture_output=True)

    def run_smith(self):
        work = os.path.join(self.tmp, "work")
        os.makedirs(os.path.join(work, "in"))
        with open(os.path.join(work, "in", "brief.json"), "w") as f:
            json.dump({"round": 0, "ticket": {"number": 7, "title": "t", "body": "b", "author": "a"},
                       "pr": None, "findings": []}, f)
        env = {**self.env, "SMITH_WORK": work, "SMITH_REPO": FRAKWORKS, "SMITH_ISSUE": "7", "SMITH_ID": "run-1",
               "SMITH_MODE": "work", "SMITH_RETRO": "0", "GH_TOKEN": "x", "SMITH_GIT_NAME": "Fleet Bot",
               "SMITH_GIT_EMAIL": "7+fleet-bot@users.noreply.github.com", "SMITH_DESKTOP_DEPS": self.deps,
               "METER_PROXY_PORT": str(free_port())}
        run = subprocess.run(["bash", RUN_SMITH], env=env, capture_output=True, text=True, timeout=60)
        self.assertEqual(run.returncode, 0, run.stdout + run.stderr)
        return run.stdout, os.path.join(work, "repo")

    def test_the_clone_gets_the_baked_node_modules_as_ignored_files(self):
        self.commit_lock(LOCK)
        out, repo = self.run_smith()
        modules = os.path.join(repo, "observability", "desktop", "node_modules")
        self.assertTrue(os.path.isfile(os.path.join(modules, "esbuild", "package.json")))
        self.assertFalse(os.path.islink(modules))
        self.assertNotIn(NEWER, out)
        status = subprocess.run(["git", "status", "--porcelain"], cwd=repo, env=self.env,
                                capture_output=True, text=True, check=True).stdout
        self.assertEqual("", status)

    def test_a_newer_lock_file_still_gets_them_and_one_log_line(self):
        self.commit_lock('{"lockfileVersion": 3, "packages": {}}\n')
        out, repo = self.run_smith()
        self.assertTrue(os.path.isdir(os.path.join(repo, "observability", "desktop", "node_modules", "esbuild")))
        self.assertEqual(1, out.count(NEWER), out)


if __name__ == "__main__":
    unittest.main()
