"""Run the release override detection against real Git commit ranges."""
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import textwrap
import unittest


ROOT = Path(__file__).resolve().parents[2]
SOURCE = (ROOT / ".github/workflows/check-release-pr.yml").read_text(encoding="utf-8")
SCRIPT = textwrap.dedent(SOURCE.split("        run: |\n", 1)[1].split("      - name: Create or update the release PR", 1)[0])
if os.name == "nt":
    git = Path(shutil.which("git"))
    BASH = next(str(path) for path in (git.parent / "bash.exe", git.parent.parent / "bin/bash.exe") if path.is_file())
else:
    BASH = shutil.which("bash")


class ReleaseOverrideTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory(prefix="release-override-test-")
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        self.git("init", "-q")
        self.git("config", "user.name", "Test")
        self.git("config", "user.email", "test@example.com")
        self.git("config", "commit.gpgsign", "false")
        self.git("config", "core.autocrlf", "false")
        (self.root / "file.txt").write_text("base\n")
        self.git("add", "file.txt")
        self.base = self.commit("chore: initial")

    def git(self, *arguments):
        return subprocess.run(["git", *arguments], cwd=self.root, check=True,
                              capture_output=True, text=True).stdout.strip()

    def commit(self, message, body=None):
        arguments = ["commit", "--allow-empty", "-q", "-m", message]
        if body:
            arguments.extend(["-m", body])
        self.git(*arguments)
        return self.git("rev-parse", "HEAD")

    def detect(self, before, after, root=None):
        root = root or self.root
        script = root / "detect.sh"
        output = root / "output.txt"
        script.write_text(SCRIPT, encoding="utf-8")
        output.write_text("")
        environment = {**os.environ, "BEFORE": before, "GITHUB_SHA": after, "GITHUB_OUTPUT": output.as_posix()}
        result = subprocess.run([BASH, script.as_posix()], cwd=root, env=environment,
                                capture_output=True, text=True)
        self.assertEqual(0, result.returncode, result.stderr)
        return output.read_text().strip()

    def test_empty_override_push_is_detected(self):
        head = self.commit("chore: release 2.0.0", "Release-As: 2.0.0")
        self.assertEqual("", self.git("diff", "--name-only", self.base, head))
        self.assertEqual("found=true", self.detect(self.base, head))

    def test_override_in_earlier_commit_and_mixed_case_is_detected(self):
        self.commit("chore: release 2.0.0-beta", "release-as: 2.0.0-beta")
        head = self.commit("chore: checkpoint")
        self.assertEqual("found=true", self.detect(self.base, head))

    def test_old_override_is_not_reused_for_an_unrelated_push(self):
        before = self.commit("chore: release 2.0.0", "Release-As: 2.0.0")
        head = self.commit("chore: checkpoint")
        self.assertEqual("found=false", self.detect(before, head))

    def test_normal_file_changes_do_not_start_an_additional_release_check(self):
        (self.root / "file.txt").write_text("changed\n")
        self.git("add", "file.txt")
        head = self.commit("chore: update file")
        self.assertEqual("found=false", self.detect(self.base, head))

    def test_new_branch_checks_its_head_commit(self):
        head = self.commit("chore: release 2.0.0", "Release-As: 2.0.0")
        self.assertEqual("found=true", self.detect("0" * 40, head))

    def test_force_push_fetches_missing_before_without_reusing_its_override(self):
        before = self.commit("chore: release 2.0.0", "Release-As: 2.0.0")
        remote = self.root / "remote.git"
        self.git("clone", "--bare", str(self.root), str(remote))
        self.git("remote", "add", "origin", str(remote))
        branch = self.git("branch", "--show-current")
        self.git("reset", "--hard", self.base)
        head = self.commit("chore: rewritten checkpoint")
        self.git("push", "--force", "origin", f"HEAD:{branch}")
        clone = self.root / "clone"
        self.git("clone", "--no-local", str(remote), str(clone))
        missing = subprocess.run(["git", "cat-file", "-e", f"{before}^{{commit}}"],
                                 cwd=clone, capture_output=True)
        self.assertNotEqual(0, missing.returncode)
        self.assertEqual("found=false", self.detect(before, head, clone))

    def test_workflow_has_unfiltered_pushes_and_keeps_manual_checks_release_free(self):
        events = SOURCE.split("\npermissions:", 1)[0]
        self.assertIn("  push:\n    branches: [main, beta]\n", events)
        self.assertNotIn("paths", events)
        self.assertIn("  workflow_dispatch:", events)
        self.assertIn("if: github.event_name == 'workflow_dispatch' || steps.override.outputs.found == 'true'", SOURCE)
        self.assertIn("skip-github-release: true", SOURCE)


if __name__ == "__main__":
    unittest.main()
