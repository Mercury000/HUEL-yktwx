import base64
from copy import deepcopy
import json
import hashlib
import unittest
import os
from pathlib import Path
import tempfile
from datetime import datetime, timedelta, timezone
from unittest.mock import patch
from cryptography.hazmat.primitives.asymmetric.ed25519 import Ed25519PrivateKey
from publish_app_update import validate, envelope, decode, https, apk_channel, apk_identity, verify_apk_signer, needs_refresh, release_order, publish_with_retry, PublishingUnavailable, maintain

class UpdatePublisherTest(unittest.TestCase):
    def manifest(self):
        return dict(schemaVersion=1, channel="stable", versionCode=10, policyRevision=2,
                    maxForcedVersionCode=9, size=100, minSdk=34, sha256="a"*64,
                    releaseUrl="https://github.com/release", publishedAt="2026-11-10T00:00:00Z",
                    expiresAt="2027-01-01T00:00:00Z",
                    urls=[f"https://cdn{i}.example.com/app.apk" for i in range(5)]+["https://github.com/app.apk"])
    def test_signatures_and_tampering(self):
        key = Ed25519PrivateKey.generate()
        payload = self.manifest()
        body = envelope(payload, key)
        self.assertEqual(payload, decode(body, key.public_key()))
        altered = json.loads(body)
        altered["payload"] = base64.b64encode(b"{}").decode()
        with self.assertRaises(Exception): decode(json.dumps(altered), key.public_key())

    def test_oversized_changelog_is_rejected_before_a_client_cannot_verify_it(self):
        key = Ed25519PrivateKey.generate()
        payload = self.manifest() | dict(changelog="更" * 180_000)
        with self.assertRaisesRegex(ValueError, "client verification limit"):
            envelope(payload, key)
    def test_forced_bounds_cdns_and_numeric_types(self):
        validate(self.manifest())
        mutations = [dict(maxForcedVersionCode=10), dict(versionCode="10"), dict(versionCode=True),
                     dict(sha256="A"*64), dict(urls=["https://one.example.com/app.apk"]*6),
                     dict(publishedAt="2026-11-10T00:00:00"), dict(releaseUrl="http://github.com/app")]
        for mutation in mutations:
            payload = self.manifest() | mutation
            with self.subTest(mutation=mutation):
                with self.assertRaises(Exception): validate(payload)
    def test_urls_reject_insecure_redirects_and_credentials(self):
        for url in ["http://example.com", "file:///app.apk", "https://user:password@example.com/a", "https://example.com:8080/a"]:
            with self.assertRaises(ValueError): https(url)

    def test_apk_channel_uses_aapt2_namespace_output(self):
        tree = 'A: http://schemas.android.com/apk/res/android:name(0x01010003)="app.update.channel" (Raw: "app.update.channel")\n  A: http://schemas.android.com/apk/res/android:value(0x01010024)="beta" (Raw: "beta")'
        self.assertEqual("beta", apk_channel(tree))
        with self.assertRaises(ValueError): apk_channel("no metadata")

    def test_apk_identity_accepts_old_and_new_minimum_sdk_output_but_not_debug_apks(self):
        package = "package: name='com.example.app' versionCode='100001' versionName='1.5.0-beta.100001'\n"
        for sdk in ("sdkVersion:'34'\n", "minSdkVersion:'34'\n"):
            with self.subTest(sdk=sdk):
                self.assertEqual(("com.example.app", 100001, "1.5.0-beta.100001", 34), apk_identity(package + sdk))
                with self.assertRaises(ValueError):
                    apk_identity(package + sdk + "application-debuggable\n")
        with self.assertRaises(ValueError):
            apk_identity(package + "targetSdkVersion:'37'\n")

    def test_certificate_pin_rejects_other_or_multiple_signers(self):
        report = f"Signer #1 certificate SHA-256 digest: {'a' * 64}\n"
        verify_apk_signer(report, "a" * 64)
        for invalid in ["", report.replace("a", "b"), report + f"Signer #2 certificate SHA-256 digest: {'b' * 64}\n"]:
            with self.assertRaises(ValueError):
                verify_apk_signer(invalid, "a" * 64)

    def test_certificate_pin_accepts_new_scheme_reports_and_checks_every_signer(self):
        pin = "a" * 64
        reports = [f"V3.0 Signer: certificate SHA-256 digest: {pin}\n",
                   f"V3.2 Signer #1: certificate SHA-256 digest: {pin.upper()}\n",
                   f"V2 Signer #1: certificate SHA-256 digest: {pin}\n"
                   f"V3.0 Signer: certificate SHA-256 digest: {pin}\n"]
        for report in reports:
            with self.subTest(report=report):
                verify_apk_signer(report, pin)
                with self.assertRaises(ValueError):
                    verify_apk_signer(report + f"V3.0 Signer #2: certificate SHA-256 digest: {'b' * 64}\n", pin)

    def test_renewal_policy_and_source_changes(self):
        now = datetime(2026, 11, 11, tzinfo=timezone.utc)
        rule = dict(maxForcedVersionCode=0, reason="optional")
        release = dict(url="https://github.com/release", body="notes")
        old = dict(releaseUrl=release["url"], changelog="notes", policyReason="optional",
                   maxForcedVersionCode=0, expiresAt=(now + timedelta(days=30)).isoformat(),
                   sourceConfigSha256="same")
        self.assertFalse(needs_refresh(old, release, rule, now, "same"))
        self.assertTrue(needs_refresh(old, release, rule, now, "changed"))
        self.assertTrue(needs_refresh(old | dict(expiresAt=(now + timedelta(days=14)).isoformat()), release, rule, now))
        self.assertTrue(needs_refresh(old, release, rule | dict(maxForcedVersionCode=9), now))
        self.assertTrue(needs_refresh(old, release | dict(body="new notes"), rule, now))

    def test_release_selection_uses_product_versions_before_beta_counters(self):
        self.assertGreater(release_order("v1.10.0", "stable"), release_order("v1.9.9", "stable"))
        self.assertGreater(release_order("v2.0.0-beta", "beta"), release_order("v1.5.0-beta.100101", "beta"))
        self.assertGreater(release_order("v1.5.0-beta.10", "beta"), release_order("v1.5.0-beta.9", "beta"))
        self.assertGreater(release_order("v1.5.0-beta.1", "beta"), release_order("v1.5.0-beta", "beta"))
        self.assertIsNone(release_order("9", "stable"))
        self.assertIsNone(release_order("v1.5.0-beta.1", "stable"))
        self.assertIsNone(release_order("v1.5.0", "beta"))

    def test_transient_failures_retry_but_validation_failures_do_not(self):
        with patch("publish_app_update.publish", side_effect=[PublishingUnavailable("CDN warming up"), None]) as publish, \
                patch("publish_app_update.time.sleep") as sleep:
            publish_with_retry("v1.5.0", True)
            self.assertEqual(2, publish.call_count)
            sleep.assert_called_once_with(30)
        with patch("publish_app_update.publish", side_effect=ValueError("signature mismatch")) as publish, \
                patch("publish_app_update.time.sleep") as sleep:
            with self.assertRaises(ValueError):
                publish_with_retry("v1.5.0", True)
            publish.assert_called_once()
            sleep.assert_not_called()

    def test_maintenance_processes_beta_even_when_stable_fails(self):
        releases = [dict(tagName="v1.5.0", isPrerelease=False, isDraft=False),
                    dict(tagName="v1.5.0-beta.100002", isPrerelease=True, isDraft=False)]

        def github(*arguments):
            if arguments[:3] == ("gh", "release", "list"):
                return json.dumps(releases)
            return json.dumps(dict(url="https://github.com/owner/repo/releases/tag/" + arguments[3],
                                   body="notes", assets=[dict(name="app.apk")]))

        with tempfile.TemporaryDirectory(prefix="metadata-maintenance-test-") as temporary:
            root = Path(temporary).resolve()
            (root / "updates").mkdir()
            (root / "updates/policy.json").write_text(json.dumps(dict(channels={channel: dict(
                maxForcedVersionCode=0, reason="optional") for channel in ("stable", "beta")})))
            (root / "updates/sources.json").write_text("{}")
            previous = Path.cwd()
            try:
                os.chdir(root)
                with patch.dict(os.environ, {"GITHUB_REPOSITORY": "owner/repo"}), \
                        patch("publish_app_update.signing_key", return_value=Ed25519PrivateKey.generate()), \
                        patch("publish_app_update.current_manifest", return_value=None), \
                        patch("publish_app_update.run", side_effect=github), \
                        patch("publish_app_update.publish_with_retry", side_effect=[ValueError("bad stable policy"), None]) as publish:
                    with self.assertRaisesRegex(RuntimeError, "stable"):
                        maintain(True)
                    self.assertEqual(2, publish.call_count)
                    self.assertEqual(("v1.5.0-beta.100002", True), publish.call_args.args)
            finally:
                os.chdir(previous)

    def test_maintenance_repairs_latest_after_a_successful_pointer_push(self):
        now = datetime.now(timezone.utc)
        tag = "v1.5.0"
        url = "https://github.com/owner/repo/releases/tag/" + tag
        old = dict(releaseUrl=url, changelog="notes", maxForcedVersionCode=0, policyReason="optional",
                   expiresAt=(now + timedelta(days=30)).isoformat(), sourceConfigSha256=hashlib.sha256(b"{}").hexdigest())
        calls = []

        def github(*arguments):
            calls.append(arguments)
            if arguments[:3] == ("gh", "release", "list"):
                return json.dumps([dict(tagName=tag, isPrerelease=False, isDraft=False)])
            if arguments[:3] == ("gh", "release", "view"):
                return json.dumps(dict(url=url, body="notes", assets=[dict(name="app.apk")]))
            return ""

        with tempfile.TemporaryDirectory(prefix="latest-recovery-test-") as temporary:
            root = Path(temporary)
            (root / "updates").mkdir()
            (root / "updates/policy.json").write_text(json.dumps(dict(channels={channel: dict(
                maxForcedVersionCode=0, reason="optional") for channel in ("stable", "beta")})))
            (root / "updates/sources.json").write_bytes(b"{}")
            previous = Path.cwd()
            try:
                os.chdir(root)
                with patch.dict(os.environ, {"GITHUB_REPOSITORY": "owner/repo"}), \
                        patch("publish_app_update.signing_key", return_value=Ed25519PrivateKey.generate()), \
                        patch("publish_app_update.current_manifest", side_effect=lambda channel, public: old if channel == "stable" else None), \
                        patch("publish_app_update.run", side_effect=github), \
                        patch("publish_app_update.publish_with_retry") as publish:
                    maintain(True)
                    publish.assert_not_called()
                    self.assertIn(("gh", "release", "edit", tag, "--repo", "owner/repo", "--latest"), calls)
            finally:
                os.chdir(previous)

if __name__ == "__main__": unittest.main()
