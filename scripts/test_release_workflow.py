"""Exercise the actual publication step with fake GitHub calls and local APK bytes."""
import os
from pathlib import Path
import re
import shutil
import subprocess
import tempfile
from types import SimpleNamespace
import unittest


ROOT = Path(__file__).resolve().parents[1]
WORKFLOW = ROOT / ".github/workflows/build.yml"
if os.name == "nt":
    git = Path(shutil.which("git"))
    BASH = next(str(path) for path in (git.parent / "bash.exe", git.parent.parent / "bin/bash.exe") if path.is_file())
else:
    BASH = shutil.which("bash")


class ReleaseWorkflowTest(unittest.TestCase):
    def test_signing_runs_after_skipped_release_preparation_only_for_successful_release_builds(self):
        source = WORKFLOW.read_text(encoding="utf-8")
        release = source.split("\n  release:\n", 1)[1].split("\n  publish-updates:\n", 1)[0]
        condition = next(line.strip()[4:] for line in release.splitlines() if line.strip().startswith("if: "))
        # Actions adds success() unless the condition explicitly checks job status.
        explicit_status = re.search(r"\b(always|success|failure|cancelled)\s*\(", condition)
        expression = condition.replace("&&", " and ").replace("||", " or ")
        cases = [("workflow_dispatch", "release", "success", "skipped", True),
                 ("workflow_dispatch", "debug", "success", "skipped", False),
                 ("pull_request", "release", "success", "skipped", False),
                 ("push", "release", "success", "success", True),
                 ("push", "debug", "success", "success", False),
                 ("workflow_dispatch", "release", "failure", "skipped", False),
                 ("workflow_dispatch", "release", "cancelled", "skipped", False),
                 ("workflow_dispatch", "release", "skipped", "skipped", False)]
        for event, build_type, build_result, preparation_result, expected in cases:
            with self.subTest(event=event, build_type=build_type, build_result=build_result,
                              preparation_result=preparation_result):
                ancestors_succeeded = build_result == preparation_result == "success"
                context = dict(always=lambda: True,
                               needs=SimpleNamespace(build=SimpleNamespace(
                                   result=build_result, outputs=SimpleNamespace(build_type=build_type))),
                               github=SimpleNamespace(event_name=event))
                runs = bool(explicit_status or ancestors_succeeded) and eval(expression, {"__builtins__": {}}, context)
                self.assertEqual(expected, runs)

    def preparation_step(self, name):
        source = WORKFLOW.read_text(encoding="utf-8").split("\n  build:\n", 1)[0]
        step = source.split(f"      - name: {name}\n", 1)[1]
        body = step.split("        run: |\n", 1)[1].split("\n      - ", 1)[0]
        return "\n".join(line[10:] for line in body.splitlines())

    def exercise_preparation(self, apk_count, *, exists=True, token="write", downloaded_name=None):
        body = self.preparation_step("Reuse an already published APK on retries") + "\n" + \
            self.preparation_step("Prepare release notes")
        with tempfile.TemporaryDirectory(prefix="release-preparation-test-") as temporary:
            root = Path(temporary)
            (root / "fixture.apk").write_bytes(b"original signed APK")
            script = root / "prepare.sh"
            script.write_text('''gh() {
              printf '%s\\n' "$*" >> calls.txt
              if [ "$EXISTS" != true ] || [ "$GH_TOKEN" != write ]; then
                echo 'release not found' >&2; return 1
              fi
              if [ "$2" = view ]; then
                case "$*" in
                  *'--json assets'*) printf '%s\\n' "$APK_COUNT" ;;
                  *'--json body'*) printf '%s' "$NOTES" ;;
                  *) return 2 ;;
                esac
              elif [ "$2" = download ]; then
                cp fixture.apk "release-input/$DOWNLOADED_APK"
              else
                return 2
              fi
            }
''' + body, encoding="utf-8")
            notes = "## Beta 更新\n\n- 保留 Markdown 和换行\n"
            environment = os.environ.copy()
            environment.update(GH_TOKEN=token, RELEASE_TAG="v1.5.0-beta", VERSION_NAME="1.5.0-beta",
                               GITHUB_OUTPUT=str(root / "output"), EXISTS=str(exists).lower(),
                               APK_COUNT=str(apk_count), NOTES=notes,
                               DOWNLOADED_APK=downloaded_name or "CaiXiaoTong-1.5.0-beta-release.apk")
            result = subprocess.run([BASH, script.as_posix()], cwd=root, env=environment, capture_output=True, text=True)
            output = (root / "output").read_text() if (root / "output").exists() else ""
            note_file = root / "release-input/release-notes.md"
            written_notes = note_file.read_text(encoding="utf-8") if note_file.exists() else None
            apk_file = root / "release-input/CaiXiaoTong-1.5.0-beta-release.apk"
            apk = apk_file.read_bytes() if apk_file.exists() else None
            calls = (root / "calls.txt").read_text()
            return result, output, written_notes, apk, calls, notes

    def test_new_draft_prepares_notes_with_existing_writer_and_no_apk_reuse(self):
        result, output, written, apk, calls, notes = self.exercise_preparation(0)
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertEqual(notes, written)
        self.assertEqual("", output)
        self.assertIsNone(apk)
        self.assertNotIn("release download", calls)

    def test_draft_retry_passes_original_apk_and_notes_without_rebuilding(self):
        result, output, written, apk, calls, notes = self.exercise_preparation(1)
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertIn("reused=true\n", output)
        self.assertEqual(notes, written)
        self.assertEqual(b"original signed APK", apk)
        self.assertEqual(1, calls.count("release download"))

    def test_missing_inaccessible_ambiguous_or_misnamed_release_input_fails_preparation(self):
        cases = [(0, dict(exists=False), "release not found"),
                 (0, dict(token="read"), "release not found"),
                 (2, {}, "multiple APKs"),
                 (1, dict(downloaded_name="wrong-version.apk"), None)]
        for count, options, message in cases:
            with self.subTest(count=count, options=options):
                result, output, written, *_ = self.exercise_preparation(count, **options)
                self.assertNotEqual(0, result.returncode)
                if message:
                    self.assertIn(message, result.stderr)
                self.assertEqual("", output)
                self.assertIsNone(written)

    def test_read_only_build_consumes_writer_preparation_and_resolved_retry_tag(self):
        source = WORKFLOW.read_text(encoding="utf-8")
        defaults, jobs = source.split("\njobs:\n", 1)
        writer, rest = jobs.split("\n  build:\n", 1)
        build = rest.split("\n  release:\n", 1)[0]
        self.assertIn("permissions:\n  contents: read\n", defaults)
        self.assertIn("      contents: write\n", writer)
        self.assertNotIn("contents: write", build)
        self.assertNotIn("gh release", build)
        self.assertIn("release_tag: ${{ steps.version.outputs.release_tag }}", writer)
        self.assertIn("ref: ${{ needs.release-please.outputs.release_tag || github.sha }}", build)
        self.assertIn("name: release-preparation", writer)
        self.assertIn("name: release-preparation", build)
        self.assertIn("uses: actions/download-artifact@", build)
        self.assertNotIn("steps.existing", build)
        self.assertIn("reused: ${{ needs.release-please.outputs.reused }}", build)
        self.assertIn("if: steps.version.outputs.publish == 'true'", build)

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
