"""Verify Pages event routing and dispatch the real command with a fake GitHub CLI."""
import os
from pathlib import Path
import re
import shutil
import subprocess
import tempfile
from types import SimpleNamespace
import unittest


ROOT = Path(__file__).resolve().parents[2]
SOURCE = (ROOT / ".github/workflows/pages.yml").read_text(encoding="utf-8")
if os.name == "nt":
    git = Path(shutil.which("git"))
    BASH = next(str(path) for path in (git.parent / "bash.exe", git.parent.parent / "bin/bash.exe")
                if path.is_file())
else:
    BASH = shutil.which("bash")


def job_source(name):
    return re.split(r"\n  [\w-]+:\n", SOURCE.split(f"\n  {name}:\n", 1)[1], maxsplit=1)[0]


def job_condition(name):
    lines = job_source(name).splitlines()
    for index, line in enumerate(lines):
        if line.startswith("    if: "):
            condition = line.removeprefix("    if: ")
            if condition == ">-":
                parts = []
                for continuation in lines[index + 1:]:
                    if not continuation.startswith("      "):
                        break
                    parts.append(continuation.strip())
                condition = " ".join(parts)
            return condition.replace("&&", " and ").replace("||", " or ")
    raise AssertionError(f"Missing condition for {name}")


class PagesWorkflowTest(unittest.TestCase):
    def test_release_tags_are_forwarded_and_only_main_can_deploy(self):
        # A successful build is assumed here; Actions also gates deploy on needs.build.
        cases = [
            ("release", "refs/tags/v1.5.1", "", (True, False, False)),
            ("release", "refs/tags/v1.5.0-beta.1", "", (True, False, False)),
            ("push", "refs/heads/main", "", (False, True, True)),
            ("pull_request", "refs/pull/12/merge", "", (False, True, False)),
            ("workflow_dispatch", "refs/heads/main", "", (False, True, True)),
            ("workflow_dispatch", "refs/heads/beta", "", (False, False, False)),
            ("workflow_dispatch", "refs/tags/v1.5.1", "", (False, False, False)),
            ("workflow_run", "refs/heads/main", "success", (False, True, True)),
            ("workflow_run", "refs/heads/main", "failure", (False, False, False)),
            ("workflow_run", "refs/heads/main", "cancelled", (False, False, False)),
        ]
        for event, ref, conclusion, expected in cases:
            with self.subTest(event=event, ref=ref, conclusion=conclusion):
                context = {"github": SimpleNamespace(event_name=event, ref=ref, event=SimpleNamespace(
                    workflow_run=SimpleNamespace(conclusion=conclusion)))}
                refresh, build, deploy = (
                    eval(job_condition(name), {"__builtins__": {}}, context)
                    for name in ("refresh-release", "build", "deploy"))
                self.assertEqual(expected, (refresh, build, build and deploy))

    def test_release_forwarding_dispatches_main_without_entering_pages_environment(self):
        job = job_source("refresh-release")
        self.assertIn("permissions:\n      actions: write", job)
        self.assertNotIn("environment:", job)
        self.assertIn("GH_TOKEN: ${{ github.token }}", job)
        self.assertIn("GH_REPO: ${{ github.repository }}", job)
        command = next(line.strip().removeprefix("run: ") for line in job.splitlines()
                       if line.strip().startswith("run: "))
        with tempfile.TemporaryDirectory(prefix="pages-dispatch-test-") as temporary:
            root = Path(temporary)
            script = root / "dispatch.sh"
            script.write_text('''set -euo pipefail
gh() {
  test "$GH_TOKEN" = test-token
  printf '%s\\n' "$@" > arguments.txt
}
''' + command + "\n", encoding="utf-8")
            environment = {**os.environ, "GH_TOKEN": "test-token", "GH_REPO": "owner/repo"}
            result = subprocess.run([BASH, script.as_posix()], cwd=root, env=environment,
                                    capture_output=True, text=True)
            self.assertEqual(0, result.returncode, result.stderr)
            self.assertEqual(["workflow", "run", "pages.yml", "--ref", "main", "--repo", "owner/repo"],
                             (root / "arguments.txt").read_text().splitlines())


if __name__ == "__main__":
    unittest.main()
