"""Tests for the prompts engine/smith/run-smith.sh hands claude, rendered for real.

Each test runs the script end to end in a scratch SMITH_WORK, against a local
git repo standing in for GitHub and a stub `claude` that saves the prompt, for
codefrak-oss/frakworks and for another repo. They check the rules of
engine/README.md, "Framework docs come from the image, not the clone": every prompt reads the framework docs from
/etc/smith/framework/, never from the clone; every prompt says how the repo's
CLAUDE.md/AGENTS.md and the prompt split the say; and the Gradle/engine/ rule
and the incident prompt's clone of frakworks depend on the repo.
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
FRAKWORKS = "codefrak-oss/frakworks"
OTHER = "acme/widgets"
BRANCH = "smith/issue-7"
MODES = ["refine", "work", "review", "security", "reproduce", "verify", "design",
         "replan", "accept", "propose", "research", "fix", "diagnose"]

# Saves the prompt, which claude reads on stdin, where the test reads it. The
# retro's call (--resume, after a work, review or security run) leaves it alone.
CLAUDE_STUB = """#!/bin/sh
for a; do [ "$a" = "--resume" ] && exit 0; done
cat > "$SMITH_WORK/prompt.txt"
printf '%s\\n' "$@" > "$SMITH_WORK/args.txt"
env > "$SMITH_WORK/env.txt"
"""


def free_port():
    s = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    s.bind(("127.0.0.1", 0))
    port = s.getsockname()[1]
    s.close()
    return port


class NoIssueReads(unittest.TestCase):

    def test_run_smith_never_tells_a_smith_to_read_a_github_issue(self):
        """A Smith reads its work item from the brief and the engine MCP, never with `gh issue`."""
        with open(RUN_SMITH) as f:
            self.assertNotIn("gh issue", f.read())


def git(*args, cwd):
    subprocess.run(["git", *args], cwd=cwd, check=True, capture_output=True)


@unittest.skipUnless(shutil.which("git") and shutil.which("jq") and shutil.which("bash"),
                     "run-smith.sh needs bash, git and jq")
class RenderedPrompts(unittest.TestCase):

    @classmethod
    def setUpClass(cls):
        cls.tmp = tempfile.mkdtemp()
        env = {"PATH": os.environ["PATH"], "HOME": cls.tmp,
               "GIT_CONFIG_GLOBAL": os.path.join(cls.tmp, "gitconfig"),
               "GIT_AUTHOR_NAME": "t", "GIT_AUTHOR_EMAIL": "t@t", "GIT_COMMITTER_NAME": "t", "GIT_COMMITTER_EMAIL": "t@t"}
        # The one origin both slugs clone from: main, and a PR branch with one commit on it.
        origin = os.path.join(cls.tmp, "origin")
        os.makedirs(origin)
        for args in (["init", "-q", "-b", "main"], ["commit", "-q", "--allow-empty", "-m", "base"],
                     ["checkout", "-q", "-b", BRANCH], ["commit", "-q", "--allow-empty", "-m", "work"],
                     ["bundle", "create", "-q", os.path.join(cls.tmp, "commits.bundle"), BRANCH],
                     # The previous round's unpushed draft: one commit on top of the PR branch, bundled since it.
                     ["commit", "-q", "--allow-empty", "-m", "unpushed draft"],
                     ["bundle", "create", "-q", os.path.join(cls.tmp, "draft.bundle"), f"{BRANCH}~1..{BRANCH}"],
                     ["reset", "-q", "--hard", "HEAD~1"],
                     ["checkout", "-q", "main"]):
            subprocess.run(["git", *args], cwd=origin, env=env, check=True, capture_output=True)
        with open(env["GIT_CONFIG_GLOBAL"], "w") as f:
            for slug in (FRAKWORKS, OTHER):
                f.write(f'[url "file://{origin}"]\n\tinsteadOf = https://github.com/{slug}\n')
        bin_dir = os.path.join(cls.tmp, "bin")
        os.makedirs(bin_dir)
        with open(os.path.join(bin_dir, "claude"), "w") as f:
            f.write(CLAUDE_STUB)
        os.chmod(os.path.join(bin_dir, "claude"), 0o755)
        # With ENGINE_URL the script writes the MCP config with it; the prompt tests need no real one.
        with open(os.path.join(bin_dir, "frakworks-mcp-config"), "w") as f:
            f.write("#!/bin/sh\necho '{}'\n")
        os.chmod(os.path.join(bin_dir, "frakworks-mcp-config"), 0o755)
        cls.env = {**env, "PATH": bin_dir + os.pathsep + os.environ["PATH"]}
        cls.runs = 0

    @classmethod
    def tearDownClass(cls):
        shutil.rmtree(cls.tmp, ignore_errors=True)

    def prompt(self, repo, mode, tiers=None, reproduction=None, draft=False, **extra):
        """The prompt run-smith.sh gives claude for `repo` in `mode`; `tiers` is the brief's tier map, when given."""
        RenderedPrompts.runs += 1
        work = os.path.join(self.tmp, f"work{self.runs}")
        os.makedirs(os.path.join(work, "in"))
        brief = {"round": 1, "ticket": {"number": 7, "title": "t", "body": "b", "author": "a"},
                 "pr": {"number": 8, "url": "u", "branch": BRANCH}, "findings": []}
        if tiers is not None:
            brief["tiers"] = tiers
        if reproduction is not None:
            brief["reproduction"] = reproduction
        brief.update(extra.pop("brief", {}))
        with open(os.path.join(work, "in", "brief.json"), "w") as f:
            json.dump(brief, f)
        if mode in ("review", "security"):
            shutil.copy(os.path.join(self.tmp, "commits.bundle"), os.path.join(work, "in", "commits.bundle"))
        if draft:
            shutil.copy(os.path.join(self.tmp, "draft.bundle"), os.path.join(work, "in", "commits.bundle"))
        env = {**self.env, "SMITH_WORK": work, "SMITH_REPO": repo, "SMITH_ISSUE": "7", "SMITH_ID": "run-1",
               "SMITH_MODE": mode, "GH_TOKEN": "x", "SMITH_GIT_NAME": "Fleet Bot", "SMITH_GIT_EMAIL": "7+fleet-bot@users.noreply.github.com",
               "METER_PROXY_PORT": str(free_port()), **extra}
        run = subprocess.run(["bash", RUN_SMITH], env=env, capture_output=True, text=True, timeout=60)
        self.assertEqual(run.returncode, 0, run.stdout + run.stderr)
        with open(os.path.join(work, "prompt.txt"), encoding="utf-8") as f:
            return f.read(), work

    def job_prompt(self, repo, prompt_text="Count the files under docs/ and say how many.", dry_run=False):
        """A mode job run (SMITH_JOB, no SMITH_ISSUE): the prompt rendered, or for a dry run the script's output; and its work dir."""
        RenderedPrompts.runs += 1
        work = os.path.join(self.tmp, f"work{self.runs}")
        os.makedirs(os.path.join(work, "in"))
        with open(os.path.join(work, "in", "brief.json"), "w") as f:
            json.dump({"round": 0, "prompt": prompt_text}, f)
        env = {**self.env, "SMITH_WORK": work, "SMITH_REPO": repo, "SMITH_JOB": "job-9", "SMITH_ID": "run-1",
               "SMITH_MODE": "job", "GH_TOKEN": "x", "SMITH_GIT_NAME": "Fleet Bot", "SMITH_GIT_EMAIL": "7+fleet-bot@users.noreply.github.com",
               "METER_PROXY_PORT": str(free_port()), "ENGINE_URL": "http://engine:8080"}
        if dry_run:
            env["SMITH_DRY_RUN"] = "1"
        run = subprocess.run(["bash", RUN_SMITH], env=env, capture_output=True, text=True, timeout=60)
        self.assertEqual(run.returncode, 0, run.stdout + run.stderr)
        if dry_run:
            return run.stdout, work
        with open(os.path.join(work, "prompt.txt"), encoding="utf-8") as f:
            return f.read(), work

    def dry_run(self, repo, **extra):
        """A work dry run's output for `repo`, with `extra` in its env."""
        RenderedPrompts.runs += 1
        work = os.path.join(self.tmp, f"work{self.runs}")
        os.makedirs(os.path.join(work, "in"))
        env = {**self.env, "SMITH_WORK": work, "SMITH_REPO": repo, "SMITH_ISSUE": "7", "SMITH_ID": "run-1", "SMITH_MODE": "work",
               "SMITH_DRY_RUN": "1", "METER_PROXY_PORT": str(free_port()), "ENGINE_URL": "http://engine:8080", **extra}
        run = subprocess.run(["bash", RUN_SMITH], env=env, capture_output=True, text=True, timeout=60)
        self.assertEqual(run.returncode, 0, run.stdout + run.stderr)
        return run.stdout

    # The repo's own MCP servers (#2449): SMITH_MCP_SERVERS as the run's Secret carries it, one http server with a secret header.
    REPO_SERVERS = json.dumps({"ops-server": {"type": "http", "url": "http://ops-server.ops.svc.cluster.local:8737/mcp",
                                                "headers": {"Authorization": "Bearer s3cr3t-token", "X-Frakworks-Run": "run-1"}}})

    def test_a_run_with_a_repo_mcp_server_allows_its_tools_per_run_and_keeps_strict_mcp_config(self):
        out = self.dry_run(OTHER, SMITH_MCP_SERVERS=self.REPO_SERVERS)
        self.assertIn("--mcp-config", out)
        self.assertIn("--strict-mcp-config --allowedTools=mcp__ops-server", out)
        self.assertIn("smith: repo MCP servers: ops-server", out)
        self.assertNotIn("s3cr3t-token", out, "no header value in the log")
        _, work = self.prompt(OTHER, "work", ENGINE_URL="http://engine:8080", SMITH_MCP_SERVERS=self.REPO_SERVERS)
        with open(os.path.join(work, "args.txt"), encoding="utf-8") as f:
            args = f.read().split("\n")
        self.assertIn("--strict-mcp-config", args)
        self.assertEqual([a for a in args if a.startswith("--allowedTools")], ["--allowedTools=mcp__ops-server"])
        self.assert_prompt_is_last(work, args)

    def assert_prompt_is_last(self, work, args):
        """The prompt reaches claude on stdin, never as an argument a variadic flag could swallow (#2460)."""
        with open(os.path.join(work, "prompt.txt"), encoding="utf-8") as f:
            prompt = f.read()
        self.assertTrue(prompt.strip())
        self.assertNotIn(prompt.strip(), "\n".join(args))
        self.assertNotIn("--allowedTools", args, "no bare variadic --allowedTools before the prompt")

    def test_a_brief_above_the_argument_limit_reaches_claude_whole_on_stdin(self):
        """A prompt over Linux's 128 KiB per-argument limit no longer fails execve with exit 126."""
        body = "x" * (256 * 1024)
        prompt, work = self.prompt(OTHER, "work", brief={"ticket": {"number": 7, "title": "t", "body": body, "author": "a"}})
        self.assertGreaterEqual(len(prompt), 256 * 1024)
        self.assertIn(body, prompt)
        self.assertEqual(os.stat(os.path.join(work, "prompt.md")).st_mode & 0o777, 0o600)

    def test_a_run_with_two_repo_mcp_servers_allows_both_in_one_argument_and_keeps_the_prompt(self):
        servers = json.dumps({"ops-server": {"type": "http", "url": "http://a/mcp"}, "other-ops": {"type": "http", "url": "http://b/mcp"},
                              "frakworks": {"type": "http", "url": "http://evil/mcp"}})
        _, work = self.prompt(OTHER, "work", ENGINE_URL="http://engine:8080", SMITH_MCP_SERVERS=servers)
        with open(os.path.join(work, "args.txt"), encoding="utf-8") as f:
            args = f.read().split("\n")
        self.assertEqual([a for a in args if a.startswith("--allowedTools")], ["--allowedTools=mcp__ops-server,mcp__other-ops"])
        self.assert_prompt_is_last(work, args)
        with open(os.path.join(work, "env.txt"), encoding="utf-8") as f:
            self.assertNotIn("SMITH_MCP_SERVERS", f.read(), "the value is unset before claude starts")

    def test_a_run_with_no_repo_mcp_server_starts_exactly_as_before(self):
        out = self.dry_run(OTHER)
        self.assertIn("--settings /etc/smith/settings.json --mcp-config", out)
        self.assertIn("--strict-mcp-config\n", out)
        self.assertNotIn("--allowedTools", out)
        self.assertNotIn("repo MCP servers", out)
        _, work = self.prompt(OTHER, "work", ENGINE_URL="http://engine:8080")
        with open(os.path.join(work, "args.txt"), encoding="utf-8") as f:
            args = f.read().split("\n")
        self.assertIn("--strict-mcp-config", args)
        self.assertNotIn("--allowedTools", args)
        # An engine name in the object is never allowed a second time.
        out = self.dry_run(OTHER, SMITH_MCP_SERVERS=json.dumps({"frakworks": {"type": "http", "url": "http://evil/mcp"}}))
        self.assertNotIn("--allowedTools", out)

    def test_a_job_dry_run_names_its_job_branch_and_writes_a_valid_result(self):
        out, work = self.job_prompt(OTHER, dry_run=True)
        self.assertIn("job=job-9", out)
        self.assertIn("checkout job/job-9", out)
        self.assertNotIn("issue=#", out)
        with open(os.path.join(work, "out", "result.json")) as f:
            result = json.load(f)
        self.assertEqual("failed", result["outcome"])
        self.assertTrue(result["summary"])
        check = subprocess.run(["python3", "-I", os.path.join(SMITH_DIR, "check-result.py"), "job", os.path.join(work, "out", "result.json")],
                               env={**self.env, "SMITH_SCHEMAS": os.path.join(SMITH_DIR, "schemas")}, capture_output=True, text=True)
        if check.returncode != 0 and "jsonschema" in (check.stdout + check.stderr):
            self.skipTest("python3-jsonschema is not installed here")
        self.assertEqual(0, check.returncode, check.stdout + check.stderr)

    def test_the_job_prompt_is_the_briefs_prompt_verbatim_plus_the_common_rules_and_reads_no_ticket(self):
        text = "Count the files under docs/ and say how many.\nThen stop."
        for repo in (FRAKWORKS, OTHER):
            with self.subTest(repo=repo):
                prompt, work = self.job_prompt(repo, text)
                self.assertTrue(prompt.startswith(text + "\n"), prompt[:200])
                self.assertIn("Read /etc/smith/framework/smith.md", prompt)
                self.assertIn("You write NOTHING to GitHub", prompt)
                self.assertIn("on branch job/job-9", prompt)
                self.assertIn("check-result job", prompt)
                self.assertIn('/etc/smith/framework/smith.md, "Friction notes"', prompt)
                self.assertNotIn("gh issue view", prompt)
                self.assertNotIn("SMITH_ISSUE", prompt)
                self.assertNotIn("issue #", prompt)
                self.assertNotIn("brief", prompt.lower())
                self.assertEqual(("--tests <Class>" in prompt), repo == FRAKWORKS)
                head = subprocess.run(["git", "rev-parse", "--abbrev-ref", "HEAD"], cwd=os.path.join(work, "repo"), capture_output=True, text=True)
                self.assertEqual("job/job-9", head.stdout.strip())

    def test_every_prompt_reads_the_framework_docs_from_the_image(self):
        for repo in (FRAKWORKS, OTHER):
            for mode in MODES:
                with self.subTest(repo=repo, mode=mode):
                    prompt, _ = self.prompt(repo, mode)
                    self.assertIn("Read /etc/smith/framework/smith.md", prompt)
                    for gone in ("If bots/smith.md exists", "bots/smith.md", "bots/model-choice.md", "bots/tiers.toml"):
                        self.assertNotIn(gone, prompt)

    def test_the_design_and_propose_prompts_draw_visuals_as_inline_svg(self):
        for mode in ("design", "propose"):
            with self.subTest(mode=mode):
                prompt, _ = self.prompt(FRAKWORKS, mode)
                self.assertIn("draw it as inline SVG in your product", prompt)
                self.assertIn("by the rule above", prompt)

    def test_the_work_and_propose_prompts_say_the_push_is_fast_forward_only_and_to_merge_the_default_branch(self):
        for mode in ("work", "propose"):
            with self.subTest(mode=mode):
                prompt, _ = self.prompt(FRAKWORKS, mode)
                self.assertIn("pushes the branch fast-forward only", prompt)
                self.assertIn("`git merge origin/main`", prompt)

    def test_the_propose_prompt_names_options_and_the_pitch_lint(self):
        prompt, _ = self.prompt(FRAKWORKS, "propose")
        self.assertIn('"options"', prompt)
        self.assertIn("lints the pitch", prompt)

    def test_the_research_prompt_answers_without_changing_anything_and_cites_what_it_read(self):
        prompt, work = self.prompt(FRAKWORKS, "research")
        self.assertIn("answering exactly one research question", prompt)
        self.assertIn("Change no file in the repo and commit nothing", prompt)
        self.assertIn("cite what you read for every claim", prompt)
        self.assertIn("/etc/smith/pitch-guide.md", prompt)
        self.assertIn('"answered" or "ask"', prompt)
        self.assertIn("draw it as inline SVG in your product", prompt)
        self.assertNotIn("This is a later round", prompt)
        self.assertFalse(os.path.exists(os.path.join(work, "out", "commits.bundle")), "a research run cuts no bundle")

    def test_the_propose_prompt_says_check_result_lints_the_pitch_against_its_options(self):
        prompt, _ = self.prompt(FRAKWORKS, "propose")
        self.assertIn("`check-result propose` also checks the pitch page against the result's \"options\"", prompt)
        self.assertIn("run it after writing the page", prompt)

    def test_a_propose_run_checks_out_the_last_draft_on_top_of_the_pr_branch(self):
        def log(work, ref):
            return subprocess.run(["git", "log", "--format=%s", ref], cwd=os.path.join(work, "repo"), env=self.env,
                                  capture_output=True, text=True, check=True).stdout.split()

        prompt, work = self.prompt(FRAKWORKS, "propose", draft=True)
        self.assertIn("already holds the last redraft", prompt)
        repo = os.path.join(work, "repo")
        head = subprocess.run(["git", "rev-parse", BRANCH], cwd=repo, capture_output=True, text=True, check=True).stdout.strip()
        heads = subprocess.run(["git", "bundle", "list-heads", os.path.join(self.tmp, "draft.bundle")],
                               capture_output=True, text=True, check=True, cwd=repo).stdout
        self.assertIn(head, heads, "smith/issue-7 is the draft's head")
        self.assertEqual(["unpushed", "draft", "work", "base"], log(work, BRANCH))
        pr_head = subprocess.run(["git", "rev-parse", BRANCH], cwd=os.path.join(self.tmp, "origin"),
                                 capture_output=True, text=True, check=True).stdout.strip()
        pr = subprocess.run(["git", "merge-base", "--is-ancestor", pr_head, BRANCH], cwd=repo)
        self.assertEqual(0, pr.returncode, "the history contains the PR branch's head")
        # No new commit: the draft is still all the result would carry since the PR branch.
        self.assertTrue(os.path.exists(os.path.join(work, "out", "commits.bundle")))

        _, bare = self.prompt(FRAKWORKS, "propose")
        self.assertEqual(["work", "base"], log(bare, BRANCH), "no bundle: the PR branch, as before")

    def test_the_replan_prompt_names_the_filed_count_and_what_a_revise_may_add(self):
        over, _ = self.prompt(OTHER, "replan", brief={"split": {"filed": [{"index": i} for i in range(9)]}})
        self.assertIn('this split has 9 filed already, at or over the cap, so any "revise" is refused: answer "continue" or "done".', over)
        under, _ = self.prompt(OTHER, "replan", brief={"split": {"filed": [{"index": i} for i in range(2)]}})
        self.assertIn('this split has 2 filed, so a "revise" may list at most 4; a longer list is refused.', under)

    def test_the_refine_prompt_reads_the_rubric_from_the_image_and_the_tier_map_from_the_brief(self):
        tiers = [{"tier": n, "model": "claude-opus-5-5", "effort": "medium"} for n in range(1, 11)]
        tiers[5] = {"tier": 6, "model": "claude-fable-5-1", "effort": "high"}
        for repo in (FRAKWORKS, OTHER):
            with self.subTest(repo=repo):
                prompt, _ = self.prompt(repo, "refine", tiers=tiers)
                self.assertIn("first read /etc/smith/framework/model-choice.md, then pick from the engine's tier map below", prompt)
                self.assertIn("Config > frakworks > Tiers", prompt)
                self.assertIn("  Tier 1: claude-opus-5-5 at medium\n", prompt)
                self.assertIn("  Tier 6: claude-fable-5-1 at high\n", prompt)
                self.assertIn("  Tier 10: claude-opus-5-5 at medium", prompt)
                self.assertNotIn("tiers.toml", prompt)
                # A backtick in the unquoted heredoc runs as a command substitution: in the image's bash the
                # refine run died on `<id>` before claude started (exit 2, no transcript), every intake failing.
                self.assertIn('("instance `<id>`")', prompt)
        bare, _ = self.prompt(OTHER, "refine")
        self.assertIn("(the brief carries no tier map)", bare)

    def test_the_refine_prompt_says_a_title_names_the_change_the_user_would_see(self):
        prompt, _ = self.prompt(OTHER, "refine")
        self.assertIn("The title names the change the user would see, not the implementation", prompt)
        self.assertIn("not \"Add BuildHistoryController and builds pane\"", prompt)

    def test_every_prompt_says_what_the_repo_binds_and_what_the_prompt_binds(self):
        for repo in (FRAKWORKS, OTHER):
            for mode in MODES:
                with self.subTest(repo=repo, mode=mode):
                    prompt, work = self.prompt(repo, mode)
                    self.assertIn("If the repo has an AGENTS.md, read it.", prompt)
                    self.assertIn("The repo's CLAUDE.md and AGENTS.md bind on how to work in the code", prompt)
                    self.assertIn("This prompt binds on everything that reaches GitHub: you never push, open a PR, "
                                  f"comment or merge, and your product is {work}/out", prompt)

    def test_every_prompt_names_note_friction_when_the_engine_serves_it(self):
        for mode in MODES:
            with self.subTest(mode=mode):
                prompt, _ = self.prompt(OTHER, mode, ENGINE_URL="http://engine.test")
                self.assertIn("call note_friction (frakworks MCP server)", prompt)
                self.assertIn('/etc/smith/framework/smith.md, "Friction notes"', prompt)
                bare, _ = self.prompt(OTHER, mode)
                self.assertNotIn("note_friction", bare)

    def test_the_gradle_rule_is_frakworks_only(self):
        rule = "gradle :engine-<module>:test --tests <Class> --no-daemon --offline"
        for mode in ("work", "review", "design"):
            with self.subTest(mode=mode):
                prompt, _ = self.prompt(FRAKWORKS, mode)
                self.assertIn(rule, prompt)
                self.assertIn("Never a full gradle build or gradle test", prompt)
                other, _ = self.prompt(OTHER, mode)
                for gone in (rule, "engine/ changes", "cd engine", "gradle test", "the engine check"):
                    self.assertNotIn(gone, other)
                self.assertIn("the repo's CI is the test", other)

    def test_the_work_and_verify_prompts_run_gradle_once_in_the_foreground_and_frakworks_only(self):
        rules = ("Gradle runs one invocation at a time and in the foreground", "never two at once",
                 "never in the background (nohup, &, run_in_background, a Monitor)", "never a pgrep or sleep wait on a daemon",
                 "Run the class you changed once; never loop it.")
        for mode in ("work", "verify"):
            with self.subTest(mode=mode):
                prompt, _ = self.prompt(FRAKWORKS, mode)
                for rule in rules:
                    self.assertIn(rule, prompt)
                other, _ = self.prompt(OTHER, mode)
                for gone in rules + ("gradle :engine-",):
                    self.assertNotIn(gone, other)
        verify, _ = self.prompt(FRAKWORKS, "verify")
        self.assertIn("A run killed for memory (exit 137, OOM) is not a verdict", verify)
        self.assertIn('the outcome is "unverified", and the summary says why', verify)

    def test_a_flaky_test_work_prompt_runs_the_class_once_and_names_the_suspected_cause(self):
        prompt, _ = self.prompt(FRAKWORKS, "work", brief={"flakyTest": True})
        self.assertIn("This ticket is a flaky test seen on main's build", prompt)
        self.assertIn("Never run it in a loop", prompt)
        self.assertIn("Suspected cause:", prompt)
        review, _ = self.prompt(FRAKWORKS, "review", brief={"flakyTest": True})
        self.assertIn("It is an optimistic fix of a flaky test", review)
        self.assertNotIn("A testable reproduction needs a regression test", review)
        plain, _ = self.prompt(FRAKWORKS, "work")
        self.assertNotIn("Suspected cause:", plain)

    def test_the_review_prompt_judges_a_passing_checks_flaky_tests_only_when_the_branch_is_to_blame(self):
        ci = {"check": "engine", "outcome": "passed", "url": "u", "flaky": ["com.x.FooTest > does a thing()"]}
        prompt, _ = self.prompt(FRAKWORKS, "review", brief={"ci": ci})
        self.assertIn("com.x.FooTest > does a thing()", prompt)
        self.assertIn("A flaky test is a finding only if it is in code the branch changed, or its flakiness is plausibly caused by the branch", prompt)
        self.assertIn("otherwise say nothing about it", prompt)
        plain, _ = self.prompt(FRAKWORKS, "review")
        self.assertNotIn("A flaky test is a finding", plain)
        work, _ = self.prompt(FRAKWORKS, "work", brief={"ci": ci})
        self.assertNotIn("A flaky test is a finding", work)

    def test_the_work_prompt_says_a_sandbox_flake_in_a_changed_class_is_the_smiths_to_judge(self):
        prompt, _ = self.prompt(FRAKWORKS, "work")
        self.assertIn("fails and then passes when run again in the sandbox, that flake is yours to judge", prompt)

    def test_the_incident_prompts_clone_frakworks_only_from_another_repo(self):
        clone = f"git clone --depth 1 https://github.com/{FRAKWORKS} /tmp/frakworks"
        for mode in ("fix", "diagnose"):
            with self.subTest(mode=mode):
                prompt, _ = self.prompt(FRAKWORKS, mode)
                self.assertNotIn("git clone", prompt)
                self.assertIn("It is in this clone.", prompt)
                prompt, _ = self.prompt(OTHER, mode)
                self.assertIn(clone, prompt)

    def test_the_doctor_prompt_lists_the_self_retried_incidents_and_explains_the_activity_id(self):
        prompt, _ = self.prompt(FRAKWORKS, "doctor")
        for passage in ("A retry is not always the same job run again",
                        "\"the smith's result breaks the rules: \"",
                        "the call activity that started the run is started over",
                        "the stored bundle is dropped and a new Smith runs from the current default branch",
                        "(call-do-work, or call-draft for a proposal) is started over",
                        "failedJobRetryTimeCycle",
                        "it is the last asyncBefore step (the job) before the failure",
                        "The message (and stacktrace) names the real failing step"):
            self.assertIn(passage, prompt)

    def test_the_reproduce_run_leaves_its_files_in_out_repro_and_the_fix_and_verify_runs_find_them_in_in_repro(self):
        prompt, work = self.prompt(OTHER, "reproduce")
        self.assertIn(f"Files your repro needs (scripts, fixtures, fetched binaries) go under {work}/out/repro/, the one place", prompt)
        self.assertIn("the \"repro\" text must cite each as /work/in/repro/<path>", prompt)
        self.assertIn("which are thrown away, except under", prompt)
        verify, _ = self.prompt(OTHER, "verify")
        self.assertIn("The reproduce run's files, when the reproduction has any, are at /work/in/repro/", verify)
        self.assertIn('"filesDropped"', verify)
        bug = {"outcome": "reproduced", "rootCause": "c", "testable": True, "repro": "/work/in/repro/x.sh"}
        fix, _ = self.prompt(OTHER, "work", reproduction=bug)
        self.assertIn("The reproduce run's files (scripts, fixtures, fetched binaries its repro uses), when it left any, are at /work/in/repro/", fix)
        plain, _ = self.prompt(OTHER, "work")
        self.assertNotIn("/work/in/repro/", plain)

    def test_the_image_carries_the_framework_docs(self):
        with open(os.path.join(SMITH_DIR, "Dockerfile"), encoding="utf-8") as f:
            docker = f.read()
        self.assertIn("COPY --from=framework smith.md model-choice.md story-points.md /etc/smith/framework/", docker)
        self.assertNotIn("tiers.toml", docker)
        with open(os.path.join(os.path.dirname(SMITH_DIR), "compose.local.yaml"), encoding="utf-8") as f:
            self.assertIn("framework: ../bots", f.read())


if __name__ == "__main__":
    unittest.main()
