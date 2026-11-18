"""Build a static Pages directory with release data from the public app repository."""

import argparse
import base64
from datetime import datetime, timezone
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys
import time
from urllib.error import HTTPError, URLError
from urllib.parse import urlparse
from urllib.request import Request, urlopen

ROOT = Path(__file__).resolve().parent.parent
PROJECT = ROOT.parent


def project_config():
    """Reuse the application's repository identity, public key and CDN pool."""
    repository = os.environ.get("GITHUB_REPOSITORY")
    if not repository:
        remote = subprocess.run(["git", "-C", str(PROJECT), "remote", "get-url", "origin"],
                                check=True, capture_output=True, text=True).stdout.strip()
        match = re.fullmatch(r"(?:https://github\.com/|git@github\.com:)([^/]+/[^/]+?)(?:\.git)?", remote)
        if not match:
            raise ValueError("Cannot identify the main project's GitHub repository")
        repository = match[1]
    candidates = json.loads((PROJECT / "updates/sources.json").read_text(encoding="utf-8"))["apkCandidates"]
    sources = [{"label": "项目 GitHub 直连", "template": "{officialUrl}"}]
    sources.extend({"label": urlparse(template).hostname, "template": template} for template in candidates)
    return {"appRepository": repository,
            "updatePublicKey": (PROJECT / "updates/public-key.txt").read_text(encoding="utf-8").strip(),
            "downloadSources": sources}


def read_remote(url):
    headers = {"User-Agent": "CaiXiaoTong-Website", "Accept": "application/vnd.github+json", "Cache-Control": "no-cache"}
    token = os.environ.get("GITHUB_TOKEN")
    if token and urlparse(url).hostname == "api.github.com":
        headers["Authorization"] = f"Bearer {token}"
    for attempt in range(3):
        try:
            with urlopen(Request(url, headers=headers), timeout=15) as response:
                raw = response.read(8 * 1024 * 1024 + 1)
                if len(raw) > 8 * 1024 * 1024:
                    raise ValueError("Release response is too large")
                return raw
        except (HTTPError, URLError, TimeoutError) as error:
            if isinstance(error, HTTPError) and error.code < 500:
                raise
            if attempt == 2:
                raise
            time.sleep(attempt + 1)


def signed_metadata(config):
    """Optional enrichment: only accept the app's signed, unexpired stable manifest."""
    from cryptography.hazmat.primitives.serialization import load_der_public_key

    key = load_der_public_key(base64.b64decode(config["updatePublicKey"], validate=True))

    def unpack(raw):
        envelope = json.loads(raw)
        if envelope["algorithm"] != "Ed25519":
            raise ValueError("Unexpected signature algorithm")
        payload = base64.b64decode(envelope["payload"], validate=True)
        key.verify(base64.b64decode(envelope["signature"], validate=True), payload)
        value = json.loads(payload)
        expiry = datetime.fromisoformat(value["expiresAt"].replace("Z", "+00:00"))
        if value["schemaVersion"] != 1 or value["channel"] != "stable" or expiry <= datetime.now(timezone.utc):
            raise ValueError("Invalid or expired stable metadata")
        return value

    channel = PROJECT / "updates/stable"
    pointer = unpack((channel / "latest.json").read_bytes())
    path = pointer["manifestPath"]
    if not re.fullmatch(r"manifests/\d+-[a-f0-9]{64}\.json", path):
        raise ValueError("Invalid manifest path")
    raw = (channel / path).read_bytes()
    if hashlib.sha256(raw).hexdigest() != pointer["manifestSha256"]:
        raise ValueError("Manifest digest does not match")
    manifest = unpack(raw)
    if manifest["policyRevision"] != pointer["policyRevision"]:
        raise ValueError("Manifest revision does not match")
    return manifest


def release_requirements(tag):
    """Read each release's own source configuration, including legacy numeric tags."""
    if not re.fullmatch(r"[A-Za-z0-9][A-Za-z0-9._/-]*", tag):
        raise ValueError("Invalid Release tag")
    source = subprocess.run(["git", "-C", str(PROJECT), "show", f"refs/tags/{tag}:app/build.gradle.kts"],
                            capture_output=True, text=True, encoding="utf-8")
    if source.returncode:
        raise ValueError(f"Missing source for Release {tag}; checkout must include tags and history")
    package = re.search(r'\bapplicationId\s*=\s*"([\w.]+)"', source.stdout)
    sdk = re.search(r"\bminSdk\s*=\s*(\d+)", source.stdout)
    if not package or not sdk:
        raise ValueError(f"Cannot read application identity and system requirement for {tag}")
    return {"minSdk": int(sdk[1]), "packageName": package[1]}


def release_data(config):
    repository = config["appRepository"]
    if not re.fullmatch(r"[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+", repository):
        raise ValueError("Invalid app repository")
    endpoint = f"https://api.github.com/repos/{repository}/releases"
    raw_releases = []
    for page in range(1, 21):
        records = json.loads(read_remote(f"{endpoint}?per_page=100&page={page}"))
        if not isinstance(records, list):
            raise ValueError("Invalid GitHub releases response")
        raw_releases.extend(records)
        if len(records) < 100:
            break
    else:
        raise ValueError("More than 2,000 releases; increase the explicit snapshot limit")
    try:
        latest_id = json.loads(read_remote(endpoint + "/latest"))["id"]
    except HTTPError as error:
        if error.code != 404:
            raise
        latest_id = None

    try:
        metadata = signed_metadata(config)
    except Exception as error:
        metadata = None
        print(f"Signed app metadata unavailable ({type(error).__name__}); keeping GitHub release fields.", file=sys.stderr)

    releases = []
    for record in raw_releases:
        if record["draft"] or record["prerelease"]:
            continue
        assets = []
        requirements = None
        for asset in record.get("assets", []):
            if not re.fullmatch(r"CaiXiaoTong-\d+\.\d+\.\d+-release\.apk", asset["name"]):
                continue
            url = asset["browser_download_url"]
            if not url.startswith(f"https://github.com/{repository}/releases/download/"):
                raise ValueError("Unexpected APK download origin")
            digest = asset.get("digest") or ""
            digest = digest.removeprefix("sha256:") if digest.startswith("sha256:") else None
            if digest and not re.fullmatch(r"[a-fA-F0-9]{64}", digest):
                digest = None
            if requirements is None:
                requirements = release_requirements(record["tag_name"])
            info = {"id": str(asset["id"]), "name": asset["name"], "sizeBytes": asset["size"],
                    "downloadUrl": url, "sha256": digest, **requirements}
            if metadata and metadata["versionName"] == record["tag_name"].removeprefix("v") and url in metadata["urls"]:
                if metadata["size"] != asset["size"] or (digest and digest.lower() != metadata["sha256"].lower()):
                    raise ValueError("GitHub asset and signed app metadata disagree")
                if requirements["minSdk"] != metadata["minSdk"] or requirements["packageName"] != metadata["packageName"]:
                    raise ValueError("Release source and signed app metadata disagree")
                info.update(sha256=metadata["sha256"], minSdk=metadata["minSdk"], packageName=metadata["packageName"])
            if not info["sha256"] or not isinstance(info["sizeBytes"], int) or info["sizeBytes"] <= 0:
                raise ValueError(f"Release {record['tag_name']} is missing APK size or SHA-256")
            assets.append(info)
        if not assets:
            continue
        if not record.get("published_at"):
            raise ValueError(f"Release {record['tag_name']} is missing its publication time")
        version = re.fullmatch(r"CaiXiaoTong-(\d+\.\d+\.\d+)-release\.apk", assets[0]["name"])[1]
        releases.append({"id": str(record["id"]), "versionName": version,
                         "channel": "stable", "name": record["name"] or record["tag_name"],
                         "publishedAt": record["published_at"], "notes": record.get("body") or "",
                         "releaseUrl": record["html_url"], "assets": assets})
    releases.sort(key=lambda release: release["publishedAt"], reverse=True)
    latest = next((release for release in releases if release["id"] == str(latest_id)), None)
    return {"schemaVersion": 1, "generatedAt": datetime.now(timezone.utc).isoformat(),
            "repository": repository, "latest": latest, "releases": releases,
            "downloadSources": config["downloadSources"]}


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--output", type=Path, default=ROOT / "_site")
    parser.add_argument("--offline", action="store_true", help="Reuse the last locally generated _site/releases.json")
    args = parser.parse_args()
    config = project_config()
    if args.offline:
        cached = ROOT / "_site/releases.json"
        if not cached.is_file():
            raise ValueError("Build online once before using --offline; no version data is stored in source")
        data = json.loads(cached.read_text(encoding="utf-8"))
        data["downloadSources"] = config["downloadSources"]
    else:
        data = release_data(config)
    serialized = json.dumps(data, ensure_ascii=False, indent=2) + "\n"
    output = args.output.resolve()
    if output == ROOT or ROOT in output.parents and output.name != "_site":
        raise ValueError("Use _site or an output directory outside the source tree")
    output.mkdir(parents=True, exist_ok=True)
    for name in ("index.html", "download.html", "styles.css", "download.css", "script.js", "site-theme.js",
                 "static-preview.js",
                 "release-data.js", "download.js", "document.css"):
        shutil.copy2(ROOT / name, output / name)
    shutil.copytree(ROOT / "vendor", output / "vendor", dirs_exist_ok=True)
    assets = [ROOT / "assets/logo.svg", *(ROOT / "assets/fonts").glob("*"), *(ROOT / "assets/screens").rglob("*.webp")]
    for source in assets:
        if not source.is_file() or source.suffix.lower() == ".png":
            continue
        if source.suffix.lower() == ".mp4":
            continue
        if source.read_bytes()[:80].startswith(b"version https://git-lfs.github.com/spec/v1"):
            raise ValueError(f"Missing Git LFS asset: {source.relative_to(ROOT)}")
        destination = output / source.relative_to(ROOT)
        destination.parent.mkdir(parents=True, exist_ok=True)
        shutil.copy2(source, destination)
    (output / "releases.json").write_text(serialized, encoding="utf-8")
    from build_documents import build_documents, validate_site
    build_documents(PROJECT, ROOT, output, config["appRepository"])
    # A deployment changes URLs for changed scripts/styles, so an open browser
    # cannot reuse a previous version's behavior with current release data.
    for page in output.glob("*.html"):
        html = page.read_text(encoding="utf-8")
        def asset_version(match):
            path = output / match[2]
            version = hashlib.sha256(path.read_bytes()).hexdigest()[:12]
            return f'{match[1]}{match[2]}?v={version}"'
        html = re.sub(r'((?:src|href)=")([^"?]+\.(?:js|css))"', asset_version, html)
        page.write_text(html, encoding="utf-8")
    (output / ".nojekyll").touch()
    validate_site(output)
    print(f'Pages site: {output}; {len(data["releases"])} stable releases; all video files excluded')


if __name__ == "__main__":
    main()
