#!/usr/bin/env python3
"""Rebuild the OpenDisplay update feeds from published GitHub releases.

The Android receiver reads public/android-version.json, while the two macOS
apps read Sparkle appcasts. Keeping those files in sync used to be a manual
release step; this script selects the newest stable release that actually has
the required asset and only rewrites a feed when its target changes.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import os
import re
import subprocess
import sys
import tempfile
from pathlib import Path
from xml.etree import ElementTree

RELEASE_LIMIT = 30


def run(command: list[str], *, env: dict[str, str] | None = None) -> str:
    result = subprocess.run(
        command,
        check=True,
        text=True,
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
        env=env,
    )
    return result.stdout


def gh_json(repo: str, *args: str):
    return json.loads(run(["gh", *args, "--repo", repo]))


def release_tags(repo: str) -> list[str]:
    releases = gh_json(
        repo,
        "release",
        "list",
        "--limit",
        str(RELEASE_LIMIT),
        "--exclude-drafts",
        "--json",
        "tagName,isDraft,isPrerelease,publishedAt",
    )
    return [
        release["tagName"]
        for release in releases
        if not release.get("isDraft") and not release.get("isPrerelease")
    ]


def release_info(repo: str, tag: str) -> dict:
    return gh_json(
        repo,
        "release",
        "view",
        tag,
        "--json",
        "tagName,name,body,assets,isDraft,isPrerelease,publishedAt",
    )


def download_asset(repo: str, tag: str, name: str, directory: Path) -> Path:
    directory.mkdir(parents=True, exist_ok=True)
    run(
        [
            "gh",
            "release",
            "download",
            tag,
            "--repo",
            repo,
            "--pattern",
            name,
            "--dir",
            str(directory),
        ]
    )
    path = directory / name
    if not path.is_file():
        raise RuntimeError(f"gh did not download {name} from {tag}")
    return path


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def find_aapt() -> str:
    configured = os.environ.get("AAPT")
    if configured and Path(configured).is_file():
        return configured

    roots = [
        os.environ.get("ANDROID_HOME"),
        os.environ.get("ANDROID_SDK_ROOT"),
        "/opt/homebrew/share/android-commandlinetools",
        str(Path.home() / "Library/Android/sdk"),
    ]
    candidates: list[Path] = []
    for root in roots:
        if not root:
            continue
        build_tools = Path(root) / "build-tools"
        if build_tools.is_dir():
            candidates.extend(build_tools.glob("*/aapt"))
    if not candidates:
        raise RuntimeError("aapt was not found; install Android build-tools")
    return str(sorted(candidates, key=lambda path: path.parent.name, reverse=True)[0])


def apk_version(apk: Path) -> tuple[int, str]:
    output = run([find_aapt(), "dump", "badging", str(apk)])
    match = re.search(
        r"package: name='[^']*' versionCode='([0-9]+)' versionName='([^']*)'",
        output,
    )
    if not match:
        raise RuntimeError("could not read versionCode/versionName from the APK")
    return int(match.group(1)), match.group(2)


def asset_url(repo: str, tag: str, name: str) -> str:
    return f"https://github.com/{repo}/releases/download/{tag}/{name}"


def select_asset(assets: list[dict], predicate) -> dict | None:
    return next((asset for asset in assets if predicate(asset["name"])), None)


def update_android(
    repo: str,
    releases: list[tuple[str, dict]],
    public_dir: Path,
) -> bool:
    for tag, release in releases:
        apk_asset = select_asset(release["assets"], lambda name: name == "OpenDisplay-Android.apk")
        if apk_asset is None:
            continue

        with tempfile.TemporaryDirectory(prefix="od-android-feed-") as temporary:
            directory = Path(temporary)
            apk = download_asset(repo, tag, apk_asset["name"], directory)
            actual_sha = sha256(apk)
            sha_asset = select_asset(
                release["assets"], lambda name: name == "OpenDisplay-Android.apk.sha256"
            )
            if sha_asset is not None:
                checksum = download_asset(repo, tag, sha_asset["name"], directory)
                published_sha = checksum.read_text(encoding="utf-8").split()[0].lower()
                if published_sha != actual_sha:
                    raise RuntimeError(
                        f"{tag}: APK SHA-256 {actual_sha} does not match {published_sha}"
                    )
            version_code, version_name = apk_version(apk)

        manifest = {
            "_comment": (
                "Generated from the latest mofu2003/opendisplay GitHub release "
                "with a signed OpenDisplay-Android.apk asset."
            ),
            "versionCode": version_code,
            "versionName": version_name,
            "apkUrl": asset_url(repo, tag, apk_asset["name"]),
            "sha256": actual_sha,
            "notes": release.get("body", "").strip()[:1200],
        }
        target = public_dir / "android-version.json"
        rendered = json.dumps(manifest, ensure_ascii=False, indent=2) + "\n"
        if target.is_file() and target.read_text(encoding="utf-8") == rendered:
            print(f"Android feed already points at {tag}")
            return False
        if target.is_file():
            previous = json.loads(target.read_text(encoding="utf-8"))
            if int(previous.get("versionCode", 0)) > version_code:
                raise RuntimeError(
                    f"{tag}: versionCode {version_code} is older than the published "
                    f"feed ({previous['versionCode']})"
                )
        target.write_text(rendered, encoding="utf-8")
        print(f"Android feed updated to {version_name} ({version_code}) from {tag}")
        return True

    print("No release with OpenDisplay-Android.apk was found")
    return False


def mac_asset(assets: list[dict], *, receiver: bool) -> dict | None:
    def matches(name: str) -> bool:
        lowered = name.lower()
        if not lowered.endswith((".zip", ".dmg")):
            return False
        is_receiver = "receiver" in lowered
        return is_receiver if receiver else not is_receiver

    # Prefer ZIP when a release carries both, because Sparkle can always
    # extract it without mounting a disk image.
    zipped = select_asset(assets, lambda name: matches(name) and name.lower().endswith(".zip"))
    return zipped or select_asset(assets, matches)


def appcast_already_targets(path: Path, url: str) -> bool:
    if not path.is_file():
        return False
    try:
        root = ElementTree.parse(path).getroot()
    except ElementTree.ParseError:
        return False
    for enclosure in root.iter("enclosure"):
        if enclosure.attrib.get("url") == url:
            return True
    return False


def appcast_version(path: Path) -> int:
    if not path.is_file():
        return 0
    try:
        root = ElementTree.parse(path).getroot()
    except ElementTree.ParseError:
        return 0
    versions = []
    for item in root.iter("item"):
        value = item.findtext("{http://www.andymatuschak.org/xml-namespaces/sparkle}version")
        if value and value.isdigit():
            versions.append(int(value))
    return max(versions, default=0)


def update_mac_appcast(
    repo: str,
    releases: list[tuple[str, dict]],
    public_dir: Path,
    *,
    receiver: bool,
    generator: Path,
    key_file: Path,
) -> bool:
    for tag, release in releases:
        asset = mac_asset(release["assets"], receiver=receiver)
        if asset is None:
            continue

        url = asset_url(repo, tag, asset["name"])
        filename = "appcast-receiver.xml" if receiver else "appcast.xml"
        target = public_dir / filename
        if appcast_already_targets(target, url):
            print(f"{filename} already points at {tag}")
            return False

        with tempfile.TemporaryDirectory(prefix="od-mac-feed-") as temporary:
            directory = Path(temporary)
            archive = download_asset(repo, tag, asset["name"], directory)
            output = directory / filename
            run(
                [
                    str(generator),
                    "--ed-key-file",
                    str(key_file),
                    "--download-url-prefix",
                    f"https://github.com/{repo}/releases/download/{tag}/",
                    "-o",
                    str(output),
                    str(archive.parent),
                ]
            )
            previous_version = appcast_version(target)
            new_version = appcast_version(output)
            if new_version <= previous_version:
                raise RuntimeError(
                    f"{tag}: Sparkle build {new_version} is not newer than the "
                    f"published feed ({previous_version})"
                )
            target.write_bytes(output.read_bytes())
        print(f"{filename} updated from {tag} ({asset['name']})")
        return True

    kind = "receiver" if receiver else "sender"
    print(f"No macOS {kind} update archive was found")
    return False


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--repo", default=os.environ.get("GITHUB_REPOSITORY"))
    parser.add_argument("--tag")
    parser.add_argument("--public-dir", default="public")
    parser.add_argument("--platform", choices=("all", "android", "mac"), default="all")
    parser.add_argument("--sparkle-generator", type=Path)
    parser.add_argument("--sparkle-key-file", type=Path)
    args = parser.parse_args()

    if not args.repo:
        parser.error("--repo or GITHUB_REPOSITORY is required")
    public_dir = Path(args.public_dir)
    public_dir.mkdir(parents=True, exist_ok=True)

    tags = [args.tag] if args.tag else release_tags(args.repo)
    releases: list[tuple[str, dict]] = []
    for tag in tags:
        info = release_info(args.repo, tag)
        if not info.get("isDraft") and not info.get("isPrerelease"):
            releases.append((tag, info))

    changed = False
    if args.platform in ("all", "android"):
        changed |= update_android(args.repo, releases, public_dir)
    if args.platform in ("all", "mac"):
        if args.sparkle_generator is None or args.sparkle_key_file is None:
            parser.error("--sparkle-generator and --sparkle-key-file are required for macOS feeds")
        changed |= update_mac_appcast(
            args.repo,
            releases,
            public_dir,
            receiver=False,
            generator=args.sparkle_generator,
            key_file=args.sparkle_key_file,
        )
        changed |= update_mac_appcast(
            args.repo,
            releases,
            public_dir,
            receiver=True,
            generator=args.sparkle_generator,
            key_file=args.sparkle_key_file,
        )

    return 0


if __name__ == "__main__":
    try:
        sys.exit(main())
    except subprocess.CalledProcessError as error:
        if error.stderr:
            print(error.stderr, file=sys.stderr)
        raise
