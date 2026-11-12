import os
from pathlib import Path
import subprocess
import tempfile
import unittest
from unittest.mock import patch
from publish_app_update import commit_and_push


class MetadataPushTest(unittest.TestCase):
    def exercise(self, change_policy, change_sources=False):
        with tempfile.TemporaryDirectory(prefix="update-publish-test-") as temporary:
            root = Path(temporary).resolve()
            remote = root / "remote.git"
            worker = root / "worker"
            other = root / "other"

            def git(directory, *arguments):
                return subprocess.check_output(["git", *arguments], cwd=directory, stderr=subprocess.STDOUT)

            git(root, "init", "--bare", str(remote))
            git(root, "clone", str(remote), str(worker))
            git(worker, "checkout", "-b", "main")
            git(worker, "config", "user.name", "Metadata test")
            git(worker, "config", "user.email", "metadata@example.invalid")
            policy = worker / "updates/policy.json"
            policy.parent.mkdir()
            policy.write_bytes(b'{"policy":1}\n')
            sources = worker / "updates/sources.json"
            sources.write_bytes(b'{"sources":1}\n')
            latest = worker / "updates/stable/latest.json"
            latest.parent.mkdir()
            latest.write_bytes(b"original pointer\n")
            git(worker, "add", ".")
            git(worker, "commit", "-m", "initial state")
            git(worker, "push", "origin", "main")
            git(root, "clone", "-b", "main", str(remote), str(other))
            git(other, "config", "user.name", "Concurrent test")
            git(other, "config", "user.email", "concurrent@example.invalid")
            destination = Path("updates/stable/new-manifest.json")
            (worker / destination).write_bytes(b"immutable manifest\n")
            original_run = subprocess.run
            injected = False

            def competing_push(arguments, *args, **kwargs):
                nonlocal injected
                if arguments[:2] == ["git", "push"] and not injected:
                    injected = True
                    path = other / ("updates/sources.json" if change_sources else
                                    "updates/policy.json" if change_policy else "README.md")
                    path.write_bytes(b"concurrent change\n")
                    git(other, "add", ".")
                    git(other, "commit", "-m", "concurrent update")
                    git(other, "push", "origin", "main")
                return original_run(arguments, *args, **kwargs)

            previous_directory = Path.cwd()
            try:
                os.chdir(worker)
                with patch("publish_app_update.subprocess.run", side_effect=competing_push), patch("publish_app_update.time.sleep"):
                    if change_policy or change_sources:
                        with self.assertRaises(ValueError):
                            commit_and_push(destination, "publish manifest", policy.read_bytes(),
                                            Path("updates/stable/latest.json"), latest.read_bytes(), sources.read_bytes())
                    else:
                        commit_and_push(destination, "publish manifest", policy.read_bytes(),
                                        Path("updates/stable/latest.json"), latest.read_bytes(), sources.read_bytes())
            finally:
                os.chdir(previous_directory)
            result = subprocess.run(["git", "--git-dir", str(remote), "show", "main:" + destination.as_posix()], capture_output=True)
            if change_policy or change_sources:
                self.assertNotEqual(0, result.returncode)
            else:
                self.assertEqual(b"immutable manifest\n", result.stdout)
                self.assertEqual(b"concurrent change\n", git(root, "--git-dir", str(remote), "show", "main:README.md"))

    def test_concurrent_code_commit_is_preserved_and_push_retries(self):
        self.exercise(False)

    def test_concurrent_policy_change_cannot_be_overwritten(self):
        self.exercise(True)

    def test_concurrent_cdn_source_change_cannot_publish_stale_verification(self):
        self.exercise(False, True)


if __name__ == "__main__":
    unittest.main()
