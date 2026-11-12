import unittest
import os
from pathlib import Path
import subprocess
import tempfile
from unittest.mock import patch
from prepare_build_version import build_version, main


class BuildVersionTest(unittest.TestCase):
    def test_two_channels_share_one_monotonic_sequence(self):
        stable = build_version("1.4.1", 100000, 101, "stable", "a" * 40)
        beta = build_version("1.5.0-beta.1", 100000, 102, "beta", "b" * 40)
        final = build_version("1.5.0", 100000, 103, "stable", "c" * 40)
        self.assertEqual("1.5.0-beta.1", beta["versionName"])
        self.assertEqual("1.5.0", final["versionName"])
        self.assertLess(int(stable["versionCode"]), int(beta["versionCode"]))
        self.assertLess(int(beta["versionCode"]), int(final["versionCode"]))

    def test_retry_reuses_identity_and_invalid_inputs_fail(self):
        values = ("1.5.0-beta", 100000, 10, "beta", "a" * 40)
        self.assertEqual(build_version(*values), build_version(*values))
        self.assertEqual("1.5.0-beta", build_version(*values)["versionName"])
        for invalid in [("1.5.0-rc.1", 0, 1, "beta", "a" * 40),
                        ("1.5.0-beta.01", 0, 1, "beta", "a" * 40),
                        ("1.5.0", 2147483647, 1, "stable", "a" * 40),
                        ("1.5.0", 0, 0, "stable", "a" * 40),
                        ("1.5.0", 0, 1, "unknown", "a" * 40)]:
            with self.assertRaises(ValueError):
                build_version(*invalid)

    def test_push_and_pr_events_have_explicit_publish_gates(self):
        cases = [("push", "main", "", "1.4.1", "", "false", "stable"),
                 ("push", "main", "", "1.5.0", "v1.5.0", "true", "stable"),
                 ("push", "beta", "", "1.4.1", "", "false", "beta"),
                 ("push", "beta", "", "1.5.0-beta", "", "false", "beta"),
                 ("push", "beta", "", "1.5.0-beta", "v1.5.0-beta", "true", "beta"),
                 ("push", "beta", "", "1.5.0-beta.1", "v1.5.0-beta.1", "true", "beta"),
                 ("push", "main", "", "1.5.0-beta.1", "", "false", "stable"),
                 ("pull_request", "42/merge", "beta", "1.5.0-beta.1", "", "false", "beta"),
                 ("workflow_dispatch", "beta", "", "1.5.0-beta.1", "", "false", "beta")]
        for event, branch, base_branch, version, tag, publish, channel in cases:
            with self.subTest(event=event, branch=branch), tempfile.TemporaryDirectory(prefix="version-plan-test-") as temporary:
                root = Path(temporary).resolve()
                (root / "version.txt").write_text(version + "\n")
                (root / "ci-version.properties").write_text("localVersionCode=9\nversionCodeOffset=100000\n")
                environment = dict(GITHUB_EVENT_NAME=event, GITHUB_REF_NAME=branch, GITHUB_BASE_REF=base_branch,
                                   GITHUB_SHA="b" * 40, GITHUB_RUN_NUMBER="42", GITHUB_OUTPUT=str(root / "output"))
                previous = Path.cwd()
                try:
                    os.chdir(root)
                    with patch.dict(os.environ, environment, clear=True), patch("sys.argv", ["prepare", "--release-tag", tag]), \
                            patch("prepare_build_version.subprocess.check_output", return_value="a" * 40 + "\n"), \
                            patch("prepare_build_version.subprocess.run", return_value=subprocess.CompletedProcess([], 1, "")):
                        main()
                    output = (root / "output").read_text()
                    self.assertIn(f"publish={publish}\n", output)
                    self.assertIn(f"channel={channel}\n", output)
                    self.assertIn("versionCode=100042\n", (root / "build-version.properties").read_text())
                    generated = (root / "build-version.properties").read_text()
                    self.assertIn(f"versionName={version}\n", generated)
                    self.assertIn("commit=" + "a" * 40 + "\n", generated)
                finally:
                    os.chdir(previous)

    def test_umeng_is_enabled_only_for_publication_or_opted_in_manual_release(self):
        cases = [("workflow_dispatch", "main", "release", "", "", "false"),
                 ("workflow_dispatch", "main", "release", "false", "", "false"),
                 ("workflow_dispatch", "main", "release", "true", "", "true"),
                 ("workflow_dispatch", "beta", "release", "true", "", "true"),
                 ("workflow_dispatch", "main", "debug", "true", "", "false"),
                 ("workflow_dispatch", "main", "release", "1", "", "false"),
                 ("push", "main", "debug", "false", "v1.5.0", "true"),
                 ("push", "beta", "debug", "false", "v1.5.0-beta.1", "true"),
                 ("push", "main", "release", "true", "", "false"),
                 ("pull_request", "main", "release", "true", "", "false")]
        for event, branch, build_type, requested, tag, enabled in cases:
            with self.subTest(event=event, branch=branch, build_type=build_type, requested=requested), \
                    tempfile.TemporaryDirectory(prefix="umeng-plan-test-") as temporary:
                root = Path(temporary).resolve()
                (root / "version.txt").write_text("1.5.0-beta.1" if branch == "beta" else "1.5.0")
                (root / "ci-version.properties").write_text("localVersionCode=9\nversionCodeOffset=100000\n")
                environment = dict(GITHUB_EVENT_NAME=event, GITHUB_REF_NAME=branch,
                                   GITHUB_RUN_NUMBER="42", GITHUB_OUTPUT=str(root / "output"),
                                   REQUESTED_BUILD_TYPE=build_type, REQUESTED_UMENG_ENABLED=requested)
                previous = Path.cwd()
                try:
                    os.chdir(root)
                    with patch.dict(os.environ, environment, clear=True), patch("sys.argv", ["prepare", "--release-tag", tag]), \
                            patch("prepare_build_version.subprocess.check_output", return_value="a" * 40 + "\n"), \
                            patch("prepare_build_version.subprocess.run", return_value=subprocess.CompletedProcess([], 1, "")):
                        main()
                    output = (root / "output").read_text()
                    self.assertIn(f"umeng_enabled={enabled}\n", output)
                    self.assertIn(f"publish={str(bool(tag)).lower()}\n", output)
                    self.assertIn(f"build_type={'release' if tag else build_type}\n", output)
                finally:
                    os.chdir(previous)

    def test_retries_only_recover_a_release_at_the_exact_commit_and_channel(self):
        cases = [("beta", "1.5.0-beta.1", "a" * 40, "true"),
                 ("beta", "1.5.0-beta.1", "b" * 40, "false"),
                 ("main", "1.5.0", "a" * 40, "true"),
                 ("beta", "1.5.0", "a" * 40, "false"),
                 ("main", "1.5.0-beta.1", "a" * 40, "false")]
        for branch, version, tag_commit, publish in cases:
            with self.subTest(branch=branch, version=version, tag_commit=tag_commit), tempfile.TemporaryDirectory() as temporary:
                root = Path(temporary).resolve()
                (root / "version.txt").write_text(version)
                (root / "ci-version.properties").write_text("localVersionCode=9\nversionCodeOffset=100000\n")
                environment = dict(GITHUB_EVENT_NAME="push", GITHUB_REF_NAME=branch,
                                   GITHUB_RUN_NUMBER="42", GITHUB_OUTPUT=str(root / "output"))
                previous = Path.cwd()
                try:
                    os.chdir(root)
                    with patch.dict(os.environ, environment, clear=True), patch("sys.argv", ["prepare"]), \
                            patch("prepare_build_version.subprocess.check_output", return_value="a" * 40 + "\n"), \
                            patch("prepare_build_version.subprocess.run", return_value=subprocess.CompletedProcess([], 0, tag_commit + "\n")):
                        main()
                    self.assertIn(f"publish={publish}\n", (root / "output").read_text())
                    self.assertIn(f"umeng_enabled={publish}\n", (root / "output").read_text())
                finally:
                    os.chdir(previous)

    def test_publishing_rejects_mismatched_release_versions_and_channels(self):
        for branch, version, tag in [("beta", "1.5.0", "v1.5.0"),
                                     ("main", "1.5.0-beta.1", "v1.5.0-beta.1"),
                                     ("beta", "1.5.0-beta.1", "v1.5.0-beta.2")]:
            with self.subTest(branch=branch, tag=tag), tempfile.TemporaryDirectory() as temporary:
                root = Path(temporary).resolve()
                (root / "version.txt").write_text(version)
                (root / "ci-version.properties").write_text("localVersionCode=9\nversionCodeOffset=100000\n")
                environment = dict(GITHUB_EVENT_NAME="push", GITHUB_REF_NAME=branch,
                                   GITHUB_RUN_NUMBER="42", GITHUB_OUTPUT=str(root / "output"))
                previous = Path.cwd()
                try:
                    os.chdir(root)
                    with patch.dict(os.environ, environment, clear=True), patch("sys.argv", ["prepare", "--release-tag", tag]), \
                            patch("prepare_build_version.subprocess.check_output", return_value="a" * 40 + "\n"):
                        with self.assertRaises(ValueError):
                            main()
                    self.assertFalse((root / "build-version.properties").exists())
                finally:
                    os.chdir(previous)


if __name__ == "__main__":
    unittest.main()
