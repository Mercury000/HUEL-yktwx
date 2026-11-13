"""Check generated application profiles and the corresponding APK packaging."""

import argparse
import hashlib
import json
from pathlib import Path
import re
import struct
import zipfile
import zlib


APPLICATION = "Lcom/mercury/yktwx/"


def application_classes(mapping: Path | None) -> set[str]:
    """Resolve application class names after R8 renaming and class merging."""
    if mapping is None:
        return set()
    classes = set()
    for line in mapping.read_text(encoding="utf-8").splitlines():
        if line.startswith((" ", "#")) or not line.endswith(":") or " -> " not in line:
            continue
        original, renamed = line[:-1].split(" -> ", 1)
        if ("L" + original.replace(".", "/") + ";").startswith(APPLICATION):
            classes.add("L" + renamed.replace(".", "/") + ";")
    if not classes:
        raise ValueError(f"{mapping}: no application classes in R8 mapping")
    return classes


def compiled_application_methods(binary: bytes, package: zipfile.ZipFile,
                                 renamed_classes: set[str] | None = None) -> int:
    """Resolve shipped ART method IDs against the APK's matching DEX files."""
    if binary[:8] != b"pro\x00010\x00":
        raise ValueError("Unsupported APK profile format; update the verifier for the new format")
    dex_count, expanded_size, compressed_size = struct.unpack_from("<BII", binary, 8)
    if not 0 < expanded_size <= 32_000_000 or len(binary) != 17 + compressed_size:
        raise ValueError("Invalid APK profile dimensions")
    decoder = zlib.decompressobj()
    payload = decoder.decompress(binary[17:], expanded_size + 1)
    if len(payload) != expanded_size or not decoder.eof or decoder.unused_data:
        raise ValueError("Invalid APK profile compression")
    position = 0
    dex_profiles = []
    for _ in range(dex_count):
        key_size, class_count, hot_size, checksum, method_count = struct.unpack_from("<HHIII", payload, position)
        position += 16
        key = payload[position:position + key_size].decode("utf-8")
        position += key_size
        key_match = re.search(r"classes(?:\d+)?\.dex$", key)
        dex_name = key_match.group() if key_match else "classes.dex"
        dex = package.read(dex_name)
        # ART identifies a DEX inside an APK by the ZIP entry CRC, not its
        # internal Adler-32 checksum (the value at DEX header offset 8).
        if dex[:4] != b"dex\n" or package.getinfo(dex_name).CRC != checksum:
            raise ValueError(f"Profile checksum does not match {dex_name}")
        if struct.unpack_from("<I", dex, 88)[0] != method_count:
            raise ValueError(f"Profile method count does not match {dex_name}")
        dex_profiles.append((dex, class_count, hot_size, method_count))
    application_methods = 0
    for dex, class_count, hot_size, method_count in dex_profiles:
        methods = set()
        hot_end = position + hot_size
        method_id = 0
        while position < hot_end:
            delta, cache_count = struct.unpack_from("<HH", payload, position)
            position += 4
            method_id += delta
            if method_id >= method_count:
                raise ValueError("Invalid hot method ID")
            methods.add(method_id)
            for _ in range(cache_count):
                _, map_size = struct.unpack_from("<HB", payload, position)
                position += 3
                if map_size in (6, 7):  # Missing types or megamorphic inline cache.
                    continue
                for _ in range(map_size):
                    _, classes = struct.unpack_from("<BB", payload, position)
                    position += 2 + 2 * classes
        if position != hot_end:
            raise ValueError("Invalid hot method region")
        position += class_count * 2
        bitmap_size = (method_count * 2 + 7) // 8
        bitmap = payload[position:position + bitmap_size]
        position += bitmap_size
        for index in range(method_count):
            startup = bitmap[index // 8] & (1 << (index % 8))
            post_startup_index = index + method_count
            post_startup = bitmap[post_startup_index // 8] & (1 << (post_startup_index % 8))
            if startup or post_startup:
                methods.add(index)
        method_offset = struct.unpack_from("<I", dex, 92)[0]
        type_offset = struct.unpack_from("<I", dex, 68)[0]
        string_offset = struct.unpack_from("<I", dex, 60)[0]
        descriptors = {}
        for index in methods:
            class_id = struct.unpack_from("<H", dex, method_offset + index * 8)[0]
            if class_id not in descriptors:
                string_id = struct.unpack_from("<I", dex, type_offset + class_id * 4)[0]
                string_position = struct.unpack_from("<I", dex, string_offset + string_id * 4)[0]
                # DEX strings start with their ULEB128 UTF-16 length.
                while dex[string_position] & 0x80:
                    string_position += 1
                string_position += 1
                end = dex.index(0, string_position)
                descriptors[class_id] = dex[string_position:end].decode("utf-8")
            if descriptors[class_id].startswith(APPLICATION) or descriptors[class_id] in (renamed_classes or ()):
                application_methods += 1
    if position != len(payload) or not application_methods:
        raise ValueError("APK profile has no matching application methods or contains trailing data")
    return application_methods


def verify(apk: Path, profile_directory: Path, mapping: Path | None = None) -> dict:
    profiles = {}
    for name in ("baseline-prof.txt", "startup-prof.txt"):
        source = profile_directory / name
        rules = [line for line in source.read_text(encoding="utf-8").splitlines()
                 if line.strip() and not line.startswith("#")]
        if not rules or not any(APPLICATION in rule for rule in rules):
            raise ValueError(f"{source}: no generated application rules")
        if any(APPLICATION + "preview/" in rule for rule in rules):
            raise ValueError(f"{source}: contains preview-only code")
        profiles[name] = {"rules": len(rules), "sha256": hashlib.sha256(source.read_bytes()).hexdigest()}
    with zipfile.ZipFile(apk) as package:
        binary = package.read("assets/dexopt/baseline.prof")
        metadata = package.read("assets/dexopt/baseline.profm")
        if not 17 < len(binary) < 1_500_000:
            raise ValueError("APK baseline profile is invalid or exceeds 1.5 MB")
        application_methods = compiled_application_methods(binary, package, application_classes(mapping))
    if not metadata.startswith(b"prm\x00") or len(metadata) <= 8:
        raise ValueError("APK profile metadata is invalid")
    with apk.open("rb") as stream:
        apk_sha256 = hashlib.file_digest(stream, "sha256").hexdigest()
    return {"apk": str(apk.resolve()), "apkSha256": apk_sha256,
            "profiles": profiles, "profiledApplicationMethods": application_methods,
            "packagedProfileBytes": len(binary), "packagedMetadataBytes": len(metadata)}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("apk", type=Path)
    parser.add_argument("--profiles", type=Path,
                        default=Path(__file__).resolve().parents[1] / "app/src/main/generated/baselineProfiles")
    parser.add_argument("--mapping", type=Path, help="Matching R8 mapping.txt for an optimized APK")
    arguments = parser.parse_args()
    print(json.dumps(verify(arguments.apk, arguments.profiles, arguments.mapping), indent=2, ensure_ascii=False))


if __name__ == "__main__":
    main()
