import argparse
import json
import os
from pathlib import Path
import re
import subprocess


def properties(path):
    return dict(line.split("=", 1) for line in Path(path).read_text().splitlines()
                if line.strip() and not line.startswith("#"))


def build_version(base, offset, run_number, channel, commit):
    if not re.fullmatch(r"(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)(?:-beta(?:\.(0|[1-9][0-9]*))?)?", base):
        raise ValueError("version.txt must contain a stable or beta Release Please version")
    if channel not in ("stable", "beta") or run_number <= 0 or offset < 0:
        raise ValueError("Invalid channel or CI numbering configuration")
    code = offset + run_number
    if not 0 < code <= 2147483647:
        raise ValueError("versionCode exceeds Android integer limits")
    if not re.fullmatch(r"[a-f0-9]{40}", commit):
        raise ValueError("A full commit SHA is required")
    return dict(versionName=base, versionCode=str(code), channel=channel, commit=commit)


def output(values):
    with open(os.environ["GITHUB_OUTPUT"], "a", encoding="utf-8") as stream:
        for key, value in values.items():
            stream.write(f"{key}={value}\n")


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--release-tag", default="")
    args = parser.parse_args()
    base = Path("version.txt").read_text().strip()
    config = properties("ci-version.properties")
    event = os.environ["GITHUB_EVENT_NAME"]
    branch = os.environ.get("GITHUB_BASE_REF") or os.environ["GITHUB_REF_NAME"]
    commit = subprocess.check_output(["git", "rev-parse", "HEAD"], text=True).strip()
    channel = "beta" if branch == "beta" else "stable"
    tag = args.release_tag
    if event == "push" and branch in ("main", "beta") and not tag and ("-beta" in base) == (channel == "beta"):
        candidate = f"v{base}"
        resolved = subprocess.run(["git", "rev-parse", "--verify", f"{candidate}^{{commit}}"],
                                  capture_output=True, text=True)
        if resolved.returncode == 0 and resolved.stdout.strip() == commit:
            tag = candidate
        elif os.environ.get("GH_TOKEN"):
            release = subprocess.run(["gh", "release", "view", candidate, "--repo", os.environ["GITHUB_REPOSITORY"],
                                      "--json", "targetCommitish,isDraft"], capture_output=True, text=True)
            if release.returncode == 0:
                details = json.loads(release.stdout)
                if details["isDraft"] and details["targetCommitish"] == commit:
                    tag = candidate
    publish = event == "push" and branch in ("main", "beta") and bool(tag)
    values = build_version(base, int(config["versionCodeOffset"]), int(os.environ["GITHUB_RUN_NUMBER"]), channel, commit)
    if int(values["versionCode"]) <= int(config["localVersionCode"]):
        raise ValueError("CI numbering must exceed the existing installed version")
    if publish and ("-beta" in base) != (channel == "beta"):
        raise ValueError("Release Please version does not match the publishing channel")
    if tag and tag != f"v{values['versionName']}":
        raise ValueError("Release tag does not match version.txt/channel")
    build_type = "release" if publish else os.environ.get("REQUESTED_BUILD_TYPE", "debug") or "debug"
    if build_type not in ("debug", "release"):
        raise ValueError("Invalid build type")
    umeng_enabled = build_type == "release" and (
        publish or event == "workflow_dispatch" and os.environ.get("REQUESTED_UMENG_ENABLED") == "true")
    Path("build-version.properties").write_text("".join(f"{key}={value}\n" for key, value in values.items()), encoding="utf-8")
    Path("build-metadata.json").write_text(json.dumps(values, indent=2) + "\n", encoding="utf-8")
    output(dict(version_name=values["versionName"], version_code=values["versionCode"], channel=channel,
                release_tag=tag, publish=str(publish).lower(), build_type=build_type,
                umeng_enabled=str(umeng_enabled).lower()))


if __name__ == "__main__":
    main()
