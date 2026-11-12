"""Generate signed static manifests after checking actual Release APK bytes."""
from __future__ import annotations
import argparse
import base64
from concurrent.futures import ThreadPoolExecutor
from datetime import datetime, timedelta, timezone
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
import tempfile
import time
from urllib.parse import quote, unquote, urlparse
from urllib.request import Request, HTTPRedirectHandler, build_opener
from urllib.error import URLError
from cryptography.hazmat.primitives import serialization
from cryptography.hazmat.primitives.asymmetric.ed25519 import Ed25519PrivateKey

MAX_APK = 512 * 1024 * 1024
MAX_PAYLOAD = 524_288


class PublishingUnavailable(OSError):
    pass


def signing_key():
    key = serialization.load_pem_private_key(os.environ["UPDATE_SIGNING_KEY"].encode(), password=None)
    if not isinstance(key, Ed25519PrivateKey):
        raise ValueError("An Ed25519 private key is required")
    expected = Path("updates/public-key.txt").read_text(encoding="utf-8-sig").strip()
    actual = base64.b64encode(key.public_key().public_bytes(
        serialization.Encoding.DER, serialization.PublicFormat.SubjectPublicKeyInfo)).decode()
    if actual != expected:
        raise ValueError("Signing key does not match the embedded client public key")
    return key


def current_manifest(channel, public):
    target = Path("updates", channel)
    latest = target / "latest.json"
    if not latest.exists():
        return None
    pointer = decode(latest.read_bytes(), public)
    path = pointer["manifestPath"]
    if not re.fullmatch(r"manifests/[1-9][0-9]*-[a-f0-9]{64}\.json", path) or pointer["channel"] != channel:
        raise ValueError("Invalid existing pointer")
    body = (target / path).read_bytes()
    if hashlib.sha256(body).hexdigest() != pointer["manifestSha256"]:
        raise ValueError("Existing manifest digest mismatch")
    manifest = decode(body, public)
    if manifest["channel"] != channel or manifest["policyRevision"] != pointer["policyRevision"]:
        raise ValueError("Existing manifest channel/revision mismatch")
    return manifest


def expected_signer(public):
    pins = {manifest["signerSha256"] for channel in ("stable", "beta")
            if (manifest := current_manifest(channel, public)) and manifest.get("signerSha256")}
    configured = os.environ.get("APK_SIGNER_SHA256", "").lower()
    if configured:
        pins.add(configured)
    if len(pins) != 1 or not re.fullmatch(r"[a-f0-9]{64}", next(iter(pins), "")):
        raise ValueError("Expected APK certificate is missing or differs from the previously signed certificate")
    return pins.pop()


def verify_apk_signer(report, expected):
    signers = re.findall(r"^(?:Signer #[0-9]+|V[0-9]+(?:\.[0-9]+)* Signer(?: #[0-9]+)?):? "
                         r"certificate SHA-256 digest: ([a-fA-F0-9]{64})$", report, re.MULTILINE)
    if not signers or {signer.lower() for signer in signers} != {expected.lower()}:
        raise ValueError("APK signing certificate does not match the trusted release certificate")


def needs_refresh(old, release, rule, now, source_hash=None):
    return (old is None or old["releaseUrl"] != release["url"] or old["changelog"] != release["body"]
            or old["maxForcedVersionCode"] != rule["maxForcedVersionCode"]
            or old.get("policyReason") != rule["reason"]
            or source_hash is not None and old.get("sourceConfigSha256") != source_hash
            or datetime.fromisoformat(old["expiresAt"].replace("Z", "+00:00")) <= now + timedelta(days=14))


def commit_and_push(path, message, policy_bytes, latest_path, latest_bytes, source_bytes=None):
    run("git", "add", str(path))
    run("git", "commit", "-m", message)
    for attempt in range(3):
        run("git", "fetch", "origin", "main")
        run("git", "rebase", "origin/main")
        remote_policy = subprocess.check_output(["git", "show", "origin/main:updates/policy.json"])
        remote_latest = subprocess.run(["git", "show", f"origin/main:{latest_path.as_posix()}"], capture_output=True)
        actual_latest = remote_latest.stdout if remote_latest.returncode == 0 else None
        if remote_policy != policy_bytes or actual_latest != latest_bytes:
            raise ValueError("Release policy/latest changed during publishing; refusing to overwrite it")
        if source_bytes is not None:
            remote_sources = subprocess.check_output(["git", "show", "origin/main:updates/sources.json"])
            if remote_sources != source_bytes:
                raise ValueError("CDN sources changed during publishing; refusing to publish stale verification")
        result = subprocess.run(["git", "push", "origin", "HEAD:main"])
        if result.returncode == 0:
            return
        if attempt < 2:
            time.sleep(5)
    raise subprocess.CalledProcessError(result.returncode, result.args)


def https(url):
    parsed = urlparse(url)
    if parsed.scheme != "https" or not parsed.hostname or parsed.username or parsed.password or parsed.port not in (None, 443):
        raise ValueError("Only absolute HTTPS URLs are allowed")
    return url


class HTTPSRedirect(HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        https(newurl)
        return super().redirect_request(req, fp, code, msg, headers, newurl)


def fetch(url, maximum=MAX_APK, file=None):
    request = Request(https(url), headers={"User-Agent": "CaiXiaoTong-UpdatePublisher/1", "Cache-Control": "no-cache"})
    digest = hashlib.sha256()
    total = 0
    data = bytearray()
    deadline = time.monotonic() + 180
    with build_opener(HTTPSRedirect()).open(request, timeout=20) as response:
        https(response.url)
        if response.status != 200:
            raise ValueError("Unexpected HTTP status")
        while block := response.read(65536):
            total += len(block)
            if total > maximum or time.monotonic() > deadline:
                raise ValueError("Response too large or too slow")
            digest.update(block)
            if file:
                file.write(block)
            else:
                data.extend(block)
    return digest.hexdigest(), total, bytes(data), response.url


def encoded(payload):
    return json.dumps(payload, ensure_ascii=False, sort_keys=True, separators=(",", ":")).encode()


def envelope(payload, key):
    data = encoded(payload)
    if len(data) > MAX_PAYLOAD:
        raise ValueError("Signed payload exceeds client verification limit")
    return encoded({"algorithm": "Ed25519", "payload": base64.b64encode(data).decode(),
                    "signature": base64.b64encode(key.sign(data)).decode()})


def decode(envelope_bytes, public):
    wrapper = json.loads(envelope_bytes)
    if wrapper["algorithm"] != "Ed25519":
        raise ValueError("Unknown signature algorithm")
    data = base64.b64decode(wrapper["payload"], validate=True)
    public.verify(base64.b64decode(wrapper["signature"], validate=True), data)
    return json.loads(data)


def validate(manifest):
    for name in ("schemaVersion", "versionCode", "policyRevision", "maxForcedVersionCode", "size", "minSdk"):
        if type(manifest[name]) is not int:
            raise ValueError(f"Invalid integer: {name}")
    if manifest["schemaVersion"] != 1 or manifest["channel"] not in ("stable", "beta"):
        raise ValueError("Invalid schema/channel")
    if not 0 <= manifest["maxForcedVersionCode"] < manifest["versionCode"] <= 2147483647:
        raise ValueError("Invalid forced boundary")
    if manifest["policyRevision"] <= 0 or not 0 < manifest["size"] <= MAX_APK or manifest["minSdk"] <= 0:
        raise ValueError("Invalid revision, size or SDK")
    if not re.fullmatch(r"[a-f0-9]{64}", manifest["sha256"]):
        raise ValueError("Invalid SHA-256")
    urls = manifest["urls"]
    hosts = {urlparse(https(url)).hostname.lower() for url in urls if urlparse(url).hostname != "github.com"}
    if not 6 <= len(urls) <= 20 or len(urls) != len(set(urls)) or len(hosts) < 5:
        raise ValueError("At least five distinct CDN hosts plus the official fallback are required")
    https(manifest["releaseUrl"])
    for name in ("publishedAt", "expiresAt"):
        if datetime.fromisoformat(manifest[name].replace("Z", "+00:00")).tzinfo is None:
            raise ValueError("Time must include timezone")


def run(*args):
    return subprocess.check_output(args, text=True).strip()


def apk_channel(tree):
    match = re.search(r'A: [^\n]*:name[^\n]*=\s*"app.update.channel"[^\n]*\n\s*A: [^\n]*:value[^\n]*=\s*"(stable|beta)"', tree)
    if not match:
        raise ValueError("APK update channel is missing")
    return match.group(1)


def apk_identity(badging):
    package = re.search(r"^package: name='([^']+)' versionCode='(\d+)' versionName='([^']+)'", badging, re.MULTILINE)
    sdk = re.search(r"^(?:minSdkVersion|sdkVersion):'(\d+)'$", badging, re.MULTILINE)
    if not package or not sdk or "application-debuggable" in badging:
        raise ValueError("APK identity cannot be read or APK is debuggable")
    name, code, version = package.groups()
    return name, int(code), version, int(sdk.group(1))


def publish(tag, do_publish=False):
    repo = os.environ["GITHUB_REPOSITORY"]
    release = json.loads(run("gh", "release", "view", tag, "--repo", repo, "--json",
                             "tagName,body,publishedAt,isPrerelease,assets,url"))
    if not release["publishedAt"]:
        raise ValueError("Release has not been published")
    assets = [asset for asset in release["assets"] if asset["name"].lower().endswith(".apk")]
    if len(assets) != 1:
        raise ValueError("Release must contain exactly one APK")
    asset = assets[0]
    channel = "beta" if release["isPrerelease"] else "stable"
    key = signing_key()
    public = key.public_key()
    signer = expected_signer(public)
    policy_bytes = Path("updates/policy.json").read_bytes()
    source_bytes = Path("updates/sources.json").read_bytes()
    policy = json.loads(policy_bytes.decode("utf-8-sig"))
    if policy["schemaVersion"] != 1:
        raise ValueError("Unsupported policy schema")
    rule = policy["channels"][channel]
    if not rule["reason"].strip():
        raise ValueError("Policy change reason is required")
    with tempfile.TemporaryDirectory() as temporary:
        apk = Path(temporary) / "release.apk"
        with apk.open("wb") as stream:
            digest, size, _, _ = fetch(asset["url"], file=stream)
        tools = sorted(Path(os.environ["ANDROID_HOME"], "build-tools").iterdir(),
                       key=lambda path: [int(part) for part in re.findall(r"\d+", path.name)])[-1]
        verify_apk_signer(run(str(tools / "apksigner"), "verify", "--verbose", "--print-certs", str(apk)), signer)
        badging = run(str(tools / "aapt2"), "dump", "badging", str(apk))
        tree = run(str(tools / "aapt2"), "dump", "xmltree", str(apk), "--file", "AndroidManifest.xml")
        if apk_channel(tree) != channel:
            raise ValueError("APK update channel does not match Release prerelease flag")
        package_name, code, name, min_sdk = apk_identity(badging)
        if package_name != "com.mercury.yktwx" or tag not in (str(code), name, "v" + name):
            raise ValueError("Release tag/package does not match APK")
        if size != asset["size"]:
            raise ValueError("Release asset size mismatch")
    target = Path("updates", channel)
    latest = target / "latest.json"
    latest_bytes = latest.read_bytes() if latest.exists() else None
    old = current_manifest(channel, public)
    if old:
        if code < old["versionCode"]:
            print("A newer APK is already published; latest remains unchanged")
            return
        if rule["maxForcedVersionCode"] < old["maxForcedVersionCode"]:
            raise ValueError("Forced boundary cannot roll back")
        if code == old["versionCode"] and digest != old["sha256"]:
            raise ValueError("A versionCode cannot be reused for different APK bytes")
        if code == old["versionCode"] and digest == old["sha256"] and not needs_refresh(old, release, rule,
                datetime.now(timezone.utc), hashlib.sha256(source_bytes).hexdigest()):
            print("This Release/policy is already published")
            if do_publish and channel == "stable":
                run("gh", "release", "edit", tag, "--repo", repo, "--latest")
            return
    official = https(asset["url"])
    owner, repository = repo.split("/")
    templates = json.loads(source_bytes.decode("utf-8-sig"))["apkCandidates"]
    candidates = list(dict.fromkeys(template.format(officialUrl=official, owner=owner, repo=repository,
                                                  tag=quote(tag, safe=""), apk=quote(asset["name"], safe="")) for template in templates))
    def probe(url):
        start = time.monotonic()
        try:
            # Stream into the hash sink, without keeping a second APK in memory.
            class Sink:
                def write(self, block): pass
            sha, length, _, final_url = fetch(url, maximum=size, file=Sink())
            if sha != digest or length != size:
                return None
            final_host = urlparse(final_url).hostname.lower()
            if final_host == "github.com" or final_host.endswith(".githubusercontent.com"):
                return None
            return time.monotonic() - start, url, final_host
        except Exception:
            return None
    with ThreadPoolExecutor(max_workers=5) as pool:
        healthy = sorted(result for result in pool.map(probe, candidates) if result is not None)
    urls, hosts, final_hosts = [], set(), set()
    for _, url, final_host in healthy:
        host = urlparse(url).hostname.lower()
        if host not in hosts and host != "github.com" and final_host not in final_hosts:
            urls.append(url); hosts.add(host); final_hosts.add(final_host)
    if len(hosts) < 5:
        raise PublishingUnavailable("Fewer than five CDN hosts passed full APK SHA-256 validation; latest remains unchanged")
    urls = urls[:19] + [official]
    # A failed pointer switch may leave an uploaded immutable manifest. Never reuse its revision.
    revisions = [int(path.name.split("-", 1)[0]) for path in (target / "manifests").glob("*.json")]
    revision = max(revisions + [old["policyRevision"] if old else 0]) + 1
    expires = (datetime.now(timezone.utc) + timedelta(days=90)).isoformat(timespec="seconds").replace("+00:00", "Z")
    manifest = dict(schemaVersion=1, channel=channel, packageName=package_name, versionCode=code,
                    versionName=name, minSdk=min_sdk, publishedAt=release["publishedAt"],
                    changelog=release["body"], maxForcedVersionCode=rule["maxForcedVersionCode"],
                    policyReason=rule["reason"], policyRevision=revision, urls=urls, sha256=digest,
                    size=size, releaseUrl=release["url"], expiresAt=expires, signerSha256=signer,
                    sourceConfigSha256=hashlib.sha256(source_bytes).hexdigest())
    validate(manifest)
    body = envelope(manifest, key)
    if len(body) > 1_048_576:
        raise ValueError("Manifest exceeds client response limit")
    manifest_hash = hashlib.sha256(body).hexdigest()
    path = f"manifests/{revision}-{manifest_hash}.json"
    destination = target / path
    destination.parent.mkdir(parents=True, exist_ok=True)
    if destination.exists() and destination.read_bytes() != body:
        raise ValueError("Immutable manifest already exists with different content")
    destination.write_bytes(body)
    pointer = dict(schemaVersion=1, channel=channel, policyRevision=revision, manifestPath=path,
                   manifestSha256=manifest_hash, expiresAt=expires)
    if do_publish:
        run("git", "config", "user.name", "github-actions[bot]")
        run("git", "config", "user.email", "41898282+github-actions[bot]@users.noreply.github.com")
        commit_and_push(destination, f"chore(updates): publish immutable {channel} manifest {revision}",
                        policy_bytes, latest, latest_bytes, source_bytes)
        raw = f"https://raw.githubusercontent.com/{repo}/main/{destination.as_posix()}"
        for attempt in range(6):
            try:
                downloaded_hash, _, _, _ = fetch(raw + f"?t={time.time_ns()}", maximum=1_048_576)
                if downloaded_hash == manifest_hash:
                    break
            except Exception:
                pass
            time.sleep(3)
        else:
            raise PublishingUnavailable("Immutable manifest could not be verified after upload; latest unchanged")
    latest.write_bytes(envelope(pointer, key))
    if do_publish:
        commit_and_push(latest, f"chore(updates): advance {channel} policy to {revision}",
                        policy_bytes, latest, latest_bytes, source_bytes)
        if channel == "stable":
            run("gh", "release", "edit", tag, "--repo", repo, "--latest")
    print(f"Validated {channel} revision {revision}, {len(hosts)} CDN hosts")


def publish_with_retry(tag, do_publish):
    for attempt in range(3):
        try:
            return publish(tag, do_publish)
        except (PublishingUnavailable, URLError, TimeoutError, ConnectionError) as error:
            if attempt == 2:
                raise
            print(f"Temporary publishing failure: {error}; retrying")
            time.sleep(30)


def release_order(tag, channel):
    stable = re.fullmatch(r"v([0-9]+)\.([0-9]+)\.([0-9]+)", tag)
    beta = re.fullmatch(r"v([0-9]+)\.([0-9]+)\.([0-9]+)-beta(?:\.([0-9]+))?", tag)
    if channel == "stable" and stable:
        return tuple(int(part) for part in stable.groups())
    if channel == "beta" and beta:
        return tuple(int(part) for part in beta.groups()[:3]) + (int(beta.group(4)) if beta.group(4) else -1,)
    return None


def maintain(do_publish):
    public = signing_key().public_key()
    rules = json.loads(Path("updates/policy.json").read_text(encoding="utf-8-sig"))["channels"]
    repo = os.environ["GITHUB_REPOSITORY"]
    releases = json.loads(run("gh", "release", "list", "--repo", repo, "--limit", "1000",
                             "--json", "tagName,isPrerelease,isDraft"))
    failures = []
    for channel in ("stable", "beta"):
        try:
            old = current_manifest(channel, public)
            candidates = [(order, release["tagName"]) for release in releases
                          if not release["isDraft"] and release["isPrerelease"] == (channel == "beta")
                          and (order := release_order(release["tagName"], channel)) is not None]
            tag = max(candidates)[1] if candidates else None
            if tag is None and old:
                parsed = urlparse(old["releaseUrl"])
                prefix = f"/{repo}/releases/tag/"
                if parsed.hostname != "github.com" or not parsed.path.startswith(prefix):
                    raise ValueError("Existing releaseUrl does not identify this repository")
                tag = unquote(parsed.path[len(prefix):])
            if tag is None:
                print(f"No {channel} Release has been published; nothing to maintain")
                continue
            release = json.loads(run("gh", "release", "view", tag, "--repo", repo, "--json", "url,body,assets"))
            if not any(asset["name"].lower().endswith(".apk") for asset in release["assets"]):
                print(f"Release {tag} has no APK yet; leaving its metadata unchanged")
                if old is None:
                    continue
                parsed = urlparse(old["releaseUrl"])
                prefix = f"/{repo}/releases/tag/"
                if parsed.hostname != "github.com" or not parsed.path.startswith(prefix):
                    raise ValueError("Existing releaseUrl does not identify this repository")
                tag = unquote(parsed.path[len(prefix):])
                release = json.loads(run("gh", "release", "view", tag, "--repo", repo, "--json", "url,body,assets"))
            if needs_refresh(old, release, rules[channel], datetime.now(timezone.utc),
                    hashlib.sha256(Path("updates/sources.json").read_bytes()).hexdigest()):
                publish_with_retry(tag, do_publish)
            elif do_publish and channel == "stable":
                # The pointer push can succeed while the final GitHub Latest update fails.
                # Repair that final step without re-signing an otherwise current manifest.
                run("gh", "release", "edit", tag, "--repo", repo, "--latest")
        except Exception as error:
            failures.append(f"{channel}: {error}")
    if failures:
        raise RuntimeError("; ".join(failures))


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    selection = parser.add_mutually_exclusive_group(required=True)
    selection.add_argument("--tag")
    selection.add_argument("--maintain", action="store_true")
    parser.add_argument("--publish", action="store_true")
    args = parser.parse_args()
    if args.maintain:
        maintain(args.publish)
    else:
        publish_with_retry(args.tag, args.publish)
