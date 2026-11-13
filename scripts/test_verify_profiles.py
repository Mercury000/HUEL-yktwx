"""Exercise ART profile ownership checks without a device or a Gradle build."""

import io
from pathlib import Path
import struct
import tempfile
import unittest
import zipfile
import zlib

from verify_profiles import APPLICATION, verify


def dex_fixture():
    # Only the DEX tables consumed by the profile verifier are needed here.
    dex = bytearray(144)
    dex[:8] = b"dex\n039\0"
    struct.pack_into("<I", dex, 8, 123)  # Internal Adler-32 deliberately differs from ZIP CRC.
    struct.pack_into("<II", dex, 56, 2, 112)
    struct.pack_into("<II", dex, 64, 2, 120)
    struct.pack_into("<II", dex, 88, 2, 128)
    struct.pack_into("<II", dex, 120, 0, 1)
    struct.pack_into("<HHI", dex, 128, 0, 0, 0)
    struct.pack_into("<HHI", dex, 136, 1, 0, 1)
    for index, descriptor in enumerate((b"La/b;", b"Landroid/view/View;")):
        struct.pack_into("<I", dex, 112 + index * 4, len(dex))
        dex.extend(bytes([len(descriptor)]) + descriptor + b"\0")
    return bytes(dex)


def apk_fixture(*, bad_checksum=False, library_only=False):
    dex = dex_fixture()
    crc = zlib.crc32(dex) ^ int(bad_checksum)
    headers, bodies = [], []
    # Exercise multidex, delta-encoded hot methods and startup bitmap methods.
    for index, key in enumerate((b"fixture.apk", b"fixture.apk!classes2.dex")):
        method = int(library_only)
        hot = struct.pack("<HH", method, 0) if index == 0 else b""
        headers.append(struct.pack("<HHIII", len(key), 0, len(hot), crc, 2) + key)
        bodies.append(hot + bytes([0 if index == 0 else 1 << method]))
    payload = b"".join(headers + bodies)
    compressed = zlib.compress(payload)
    profile = b"pro\0" + b"010\0" + struct.pack("<BII", 2, len(payload), len(compressed)) + compressed
    stream = io.BytesIO()
    with zipfile.ZipFile(stream, "w") as package:
        package.writestr("classes.dex", dex)
        package.writestr("classes2.dex", dex)
        package.writestr("assets/dexopt/baseline.prof", profile)
        package.writestr("assets/dexopt/baseline.profm", b"prm\0" + b"002\0" + b"fixture")
    return stream.getvalue()


class VerifyProfilesTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        self.apk = self.root / "fixture.apk"
        self.apk.write_bytes(apk_fixture())
        for name in ("baseline-prof.txt", "startup-prof.txt"):
            (self.root / name).write_text(f"HS{APPLICATION}MainActivity;->onCreate()V\n", encoding="utf-8")
        self.mapping = self.root / "mapping.txt"
        package = APPLICATION[1:-1].replace("/", ".")
        self.mapping.write_text(f"{package}.MainActivity -> a.b:\n    void onCreate() -> c\n", encoding="utf-8")

    def test_resolves_hot_and_startup_application_methods_after_r8_renaming(self):
        result = verify(self.apk, self.root, self.mapping)
        self.assertEqual(2, result["profiledApplicationMethods"])
        self.assertEqual(64, len(result["apkSha256"]))

    def test_rejects_profile_from_different_dex(self):
        self.apk.write_bytes(apk_fixture(bad_checksum=True))
        with self.assertRaisesRegex(ValueError, "checksum"):
            verify(self.apk, self.root, self.mapping)

    def test_library_methods_cannot_satisfy_application_coverage(self):
        self.apk.write_bytes(apk_fixture(library_only=True))
        with self.assertRaisesRegex(ValueError, "no matching application methods"):
            verify(self.apk, self.root, self.mapping)

    def test_r8_renamed_application_requires_matching_mapping(self):
        self.mapping.write_text("unrelated.Class -> a.b:\n", encoding="utf-8")
        with self.assertRaisesRegex(ValueError, "no application classes"):
            verify(self.apk, self.root, self.mapping)

    def test_preview_rules_are_rejected(self):
        (self.root / "startup-prof.txt").write_text(f"HS{APPLICATION}preview/Fixture;->seed()V\n", encoding="utf-8")
        with self.assertRaisesRegex(ValueError, "preview-only"):
            verify(self.apk, self.root, self.mapping)

    def test_empty_startup_profile_is_rejected(self):
        (self.root / "startup-prof.txt").write_text("", encoding="utf-8")
        with self.assertRaisesRegex(ValueError, "no generated application rules"):
            verify(self.apk, self.root, self.mapping)


if __name__ == "__main__":
    unittest.main()
