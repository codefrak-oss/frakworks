"""Tests for the retro turn of engine/smith/run-smith.sh (engine/README.md, "The retro").

Each test runs the script end to end in a scratch SMITH_WORK, against a local
git repo standing in for GitHub and a fake `claude` that logs every call's
arguments. The main call commits and writes a result; the retro call (the one
with --resume) answers by FAKE_RETRO and, to prove it cannot, also tries to
change the result and add a commit.
"""

import json
import os
import shutil
import socket
import subprocess
import tempfile
import unittest

SMITH_DIR = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
RUN_SMITH = os.path.join(SMITH_DIR, "run-smith.sh")
REPO = "acme/widgets"
BRANCH = "smith/issue-7"
RESULT = '{"outcome":"done","title":"t","summary":"s"}\n'

FAKE_CLAUDE = r"""#!/usr/bin/env python3
import json, os, subprocess, sys
args = sys.argv[1:]
if "--resume" not in args:
    args.append(sys.stdin.read())
work = os.environ["SMITH_WORK"]
with open(os.path.join(work, "calls.jsonl"), "a") as f:
    f.write(json.dumps(args) + "\n")
out = os.path.join(work, "out")
def commit(name):
    open(name, "w").write(name)
    subprocess.run(["git", "add", name], check=True)
    subprocess.run(["git", "commit", "-q", "-m", name], check=True)
if "--resume" not in args:
    if os.environ.get("SMITH_MODE", "work") == "work":
        commit("main.txt")
    if os.environ["SMITH_MODE"] != "refine":
        open(os.path.join(out, "result.json"), "w").write(os.environ["FAKE_RESULT"])
    sys.exit(int(os.environ.get("FAKE_MAIN_EXIT", "0")))
open(os.path.join(out, "result.json"), "w").write('{"outcome":"ask","question":"retro"}')
if os.environ.get("SMITH_MODE") == "work":
    commit("retro.txt")
scenario = os.environ.get("FAKE_RETRO", "notes")
if scenario == "fail":
    sys.exit(1)
if scenario == "garbled":
    print("not json")
    sys.exit(0)
notes = [] if scenario == "empty" else [{"kind": "docs", "summary": "the README was stale", "severity": "low"}]
print(json.dumps({"type": "result", "structured_output": {"notes": notes}}))
"""


def free_port():
    s = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    s.bind(("127.0.0.1", 0))
    port = s.getsockname()[1]
    s.close()
    return port


@unittest.skipUnless(shutil.which("git") and shutil.which("jq") and shutil.which("bash") and shutil.which("timeout"),
                     "run-smith.sh needs bash, git, jq and timeout")
class Retro(unittest.TestCase):

    @classmethod
    def setUpClass(cls):
        cls.tmp = tempfile.mkdtemp()
        env = {"PATH": os.environ["PATH"], "HOME": cls.tmp,
               "GIT_CONFIG_GLOBAL": os.path.join(cls.tmp, "gitconfig"),
               "GIT_AUTHOR_NAME": "t", "GIT_AUTHOR_EMAIL": "t@t", "GIT_COMMITTER_NAME": "t", "GIT_COMMITTER_EMAIL": "t@t"}
        origin = os.path.join(cls.tmp, "origin")
        os.makedirs(origin)
        for args in (["init", "-q", "-b", "main"], ["commit", "-q", "--allow-empty", "-m", "base"],
                     ["checkout", "-q", "-b", BRANCH], ["commit", "-q", "--allow-empty", "-m", "work"],
                     ["bundle", "create", "-q", os.path.join(cls.tmp, "commits.bundle"), BRANCH],
                     ["checkout", "-q", "main"]):
            subprocess.run(["git", *args], cwd=origin, env=env, check=True, capture_output=True)
        with open(env["GIT_CONFIG_GLOBAL"], "w") as f:
            f.write(f'[url "file://{origin}"]\n\tinsteadOf = https://github.com/{REPO}\n')
        bin_dir = os.path.join(cls.tmp, "bin")
        os.makedirs(bin_dir)
        with open(os.path.join(bin_dir, "claude"), "w") as f:
            f.write(FAKE_CLAUDE)
        os.chmod(os.path.join(bin_dir, "claude"), 0o755)
        cls.env = {**env, "PATH": bin_dir + os.pathsep + os.environ["PATH"]}
        cls.runs = 0

    @classmethod
    def tearDownClass(cls):
        shutil.rmtree(cls.tmp, ignore_errors=True)

    def run_smith(self, mode="work", **extra):
        """Runs run-smith.sh in `mode`: its exit status, stdout, claude's calls and the work root."""
        Retro.runs += 1
        work = os.path.join(self.tmp, f"work{self.runs}")
        os.makedirs(os.path.join(work, "in"))
        with open(os.path.join(work, "in", "brief.json"), "w") as f:
            json.dump({"round": 1, "ticket": {"number": 7, "title": "t", "body": "b", "author": "a"},
                       "pr": {"number": 8, "url": "u", "branch": BRANCH}, "findings": []}, f)
        if mode in ("review", "security"):
            shutil.copy(os.path.join(self.tmp, "commits.bundle"), os.path.join(work, "in", "commits.bundle"))
        env = {**self.env, "SMITH_WORK": work, "SMITH_REPO": REPO, "SMITH_ISSUE": "7", "SMITH_ID": "run-1",
               "SMITH_MODE": mode, "GH_TOKEN": "x", "SMITH_GIT_NAME": "Fleet Bot", "SMITH_GIT_EMAIL": "bot@t",
               "METER_PROXY_PORT": str(free_port()), "FAKE_RESULT": RESULT, **extra}
        run = subprocess.run(["bash", RUN_SMITH], env=env, capture_output=True, text=True, timeout=120)
        with open(os.path.join(work, "calls.jsonl")) as f:
            calls = [json.loads(line) for line in f]
        return run, calls, work

    @staticmethod
    def flag(call, name):
        return call[call.index(name) + 1]

    def read(self, path):
        with open(path) as f:
            return f.read()

    def test_a_work_run_resumes_its_own_session_once_and_keeps_its_notes(self):
        run, calls, work = self.run_smith()
        self.assertEqual(run.returncode, 0, run.stdout + run.stderr)
        self.assertEqual(len(calls), 2, calls)
        main, retro = calls
        self.assertNotIn("--resume", main)
        self.assertEqual(self.flag(retro, "--resume"), self.flag(main, "--session-id"))
        self.assertEqual(self.flag(retro, "--max-turns"), "3")
        self.assertEqual(self.flag(retro, "--max-budget-usd"), "1.00")
        self.assertEqual(self.flag(retro, "--tools"), "")
        self.assertIn("--strict-mcp-config", retro)
        self.assertEqual(json.loads(self.flag(retro, "--json-schema"))["required"], ["notes"])
        self.assertEqual(json.loads(self.read(os.path.join(work, "out", "retro.json"))),
                         [{"kind": "docs", "summary": "the README was stale", "severity": "low"}])
        self.assertIn("smith: retro: 1 note(s)", run.stdout)

    def test_the_retro_changes_neither_the_result_nor_the_bundle_nor_the_exit_status(self):
        run, calls, work = self.run_smith(FAKE_MAIN_EXIT="3")
        self.assertEqual(run.returncode, 3, run.stdout + run.stderr)
        self.assertEqual(len(calls), 2)
        self.assertEqual(self.read(os.path.join(work, "out", "result.json")), RESULT)
        listed = subprocess.run(["git", "bundle", "list-heads", os.path.join(work, "out", "commits.bundle")],
                                capture_output=True, text=True, check=True).stdout
        head = listed.split()[0]
        subjects = subprocess.run(["git", "log", "--format=%s", "-n", "2", head], cwd=os.path.join(work, "repo"),
                                  capture_output=True, text=True, check=True).stdout.split()
        self.assertEqual(subjects[0], "main.txt", "the bundle ends at the main session's commit")

    def test_a_failed_or_garbled_retro_drops_the_notes_and_nothing_else(self):
        for scenario, line in (("fail", "smith: retro dropped: claude exited 1"),
                               ("garbled", "smith: retro dropped: no notes in its answer")):
            with self.subTest(scenario=scenario):
                run, calls, work = self.run_smith(FAKE_RETRO=scenario)
                self.assertEqual(run.returncode, 0, run.stdout + run.stderr)
                self.assertIn(line, run.stdout)
                self.assertFalse(os.path.exists(os.path.join(work, "out", "retro.json")))
                self.assertEqual(self.read(os.path.join(work, "out", "result.json")), RESULT)

    def test_an_empty_retro_leaves_an_empty_list(self):
        run, _, work = self.run_smith(FAKE_RETRO="empty")
        self.assertEqual(run.returncode, 0, run.stdout + run.stderr)
        self.assertEqual(json.loads(self.read(os.path.join(work, "out", "retro.json"))), [])
        self.assertIn("smith: retro: 0 note(s)", run.stdout)

    def test_review_and_security_runs_get_a_retro(self):
        for mode in ("review", "security"):
            with self.subTest(mode=mode):
                run, calls, _ = self.run_smith(mode)
                self.assertEqual(run.returncode, 0, run.stdout + run.stderr)
                self.assertEqual(["--resume" in c for c in calls], [False, True])

    def test_other_modes_and_smith_retro_0_get_none(self):
        for mode, extra in (("refine", {}), ("design", {}), ("work", {"SMITH_RETRO": "0"})):
            with self.subTest(mode=mode, **extra):
                run, calls, work = self.run_smith(mode, **extra)
                self.assertEqual(run.returncode, 0, run.stdout + run.stderr)
                self.assertEqual(len(calls), 1, calls)
                self.assertNotIn("retro", run.stdout)
                self.assertFalse(os.path.exists(os.path.join(work, "out", "retro.json")))


if __name__ == "__main__":
    unittest.main()
