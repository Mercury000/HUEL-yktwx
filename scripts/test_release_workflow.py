"""Exercise the actual publication step with fake GitHub calls and local APK bytes."""
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest


ROOT = Path(__file__).resolve().parents[1]
WORKFLOW = ROOT / ".github/workflows/build.yml"
if os.name == "nt":
    git = Path(shutil.which("git"))
    BASH = next(str(path) for path in (git.parent / "bash.exe", git.parent.parent / "bin/bash.exe") if path.is_file())
else:
    BASH = shutil.which("bash")


class ReleaseWorkflowTest(unittest.TestCase):
    def publication_step(self):
        source = WORKFLOW.read_text(encoding="utf-8")
        step = source.split("      - name: Publish the Release without replacing existing APKs\n", 1)[1]
        step = step.split("\n  publish-updates:", 1)[0]
        environment, body = step.split("        run: |\n", 1)
        # This job has no checkout. Resolve its actual repository environment declaration.
        repo = "owner/repo" if "          GH_REPO: ${{ github.repository }}\n" in environment else ""
        return repo, "\n".join(line[10:] for line in body.splitlines())

    def exercise(self, channel, exists, reused):
        repo, body = self.publication_step()
        with tempfile.TemporaryDirectory(prefix="release-step-test-") as temporary:
            root = Path(temporary)
            (root / "release-input").mkdir()
            (root / "release-input/build-metadata.json").write_text("{}")
            (root / "release-input/release-notes.md").write_text("notes")
            (root / "app.apk").write_bytes(b"fixture")
            script = root / "publish.sh"
            script.write_text('''gh() {
              test "$GH_REPO" = owner/repo || { echo 'repository missing' >&2; return 2; }
              if [ "$2" = view ]; then test "$EXISTS" = true; return; fi
              printf '%s\\n' "$*" >> calls.txt
            }
''' + body, encoding="utf-8")
            environment = os.environ.copy()
            environment.update(GH_REPO=repo, RELEASE_TAG="v1.5.0" if channel == "stable" else "v1.5.0-beta.1",
                               VERSION_NAME="1.5.0", CHANNEL=channel, APK="app.apk", REUSED=str(reused).lower(),
                               GITHUB_SHA="a" * 40, EXISTS=str(exists).lower())
            result = subprocess.run([BASH, script.as_posix()], cwd=root, env=environment, capture_output=True, text=True)
            calls = (root / "calls.txt").read_text() if (root / "calls.txt").exists() else ""
            return result, calls

    def test_stable_upload_precedes_publication_and_does_not_set_latest(self):
        result, calls = self.exercise("stable", True, False)
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertLess(calls.index("release upload"), calls.index("release edit"))
        self.assertIn("--draft=false --prerelease=false --latest=false", calls)

    def test_retry_reuses_existing_apk_without_upload_or_replacement(self):
        result, calls = self.exercise("stable", True, True)
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertNotIn("release upload", calls)
        self.assertNotIn("--clobber", calls)

    def test_beta_upload_precedes_publication_of_the_release_please_prerelease(self):
        result, calls = self.exercise("beta", True, False)
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertLess(calls.index("release upload"), calls.index("release edit"))
        self.assertIn("--draft=false --prerelease=true --latest=false", calls)
        self.assertNotIn("release create", calls)

    def test_both_channels_require_a_release_please_draft(self):
        for channel in ("stable", "beta"):
            with self.subTest(channel=channel):
                result, calls = self.exercise(channel, False, False)
                self.assertNotEqual(0, result.returncode)
                self.assertIn("Release Please", result.stderr)
                self.assertEqual("", calls)

    def test_beta_retry_reuses_the_apk_and_preserves_prerelease_status(self):
        result, calls = self.exercise("beta", True, True)
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertNotIn("release upload", calls)
        self.assertIn("--prerelease=true", calls)

    def test_signing_step_recognizes_scheme_reports_and_rejects_any_other_certificate(self):
        source = WORKFLOW.read_text(encoding="utf-8")
        extraction = next(line.strip() for line in source.splitlines() if line.strip().startswith("ACTUAL=$("))
        pin = "a" * 64
        reports = [(f"Signer #1 certificate SHA-256 digest: {pin}\n", True),
                   (f"V3.0 Signer: certificate SHA-256 digest: {pin}\n", True),
                   (f"V2 Signer #1: certificate SHA-256 digest: {pin.upper()}\n"
                    f"V3.2 Signer: certificate SHA-256 digest: {pin}\n", True),
                   (f"V3.0 Signer: certificate SHA-256 digest: {pin}\n"
                    f"V3.0 Signer #2: certificate SHA-256 digest: {'b' * 64}\n", False),
                   (f"Source Stamp Signer certificate SHA-256 digest: {pin}\n", False), ("", False)]
        for report, accepted in reports:
            with self.subTest(report=report), tempfile.TemporaryDirectory(prefix="signing-step-test-") as temporary:
                root = Path(temporary)
                (root / "signing-report.txt").write_text(report)
                script = root / "certificate.sh"
                script.write_text('set -euo pipefail\n' + extraction + '\ntest "$ACTUAL" = "$EXPECTED"\n')
                environment = os.environ.copy()
                environment["EXPECTED"] = pin
                result = subprocess.run([BASH, script.as_posix()], cwd=root, env=environment, capture_output=True, text=True)
                self.assertEqual(accepted, result.returncode == 0, result.stderr)


if __name__ == "__main__":
    unittest.main()
