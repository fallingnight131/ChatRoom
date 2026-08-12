#!/usr/bin/env python3
"""Validate a Vite production tree and create an undeployed Web artifact manifest."""

from __future__ import annotations

import argparse
import json
import re
from html.parser import HTMLParser
from pathlib import Path
from typing import Iterable
from urllib.parse import urlsplit

from artifact_manifest_common import (
    ManifestError,
    atomic_write,
    payload_files,
    sha256_file,
    validate_revision,
)


HASHED_ASSET = re.compile(r"^assets/.+-[A-Za-z0-9_-]{8,}\.[A-Za-z0-9.]+$")
WEB_SEMVER = re.compile(
    r"^(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)"
    r"(?:-[0-9A-Za-z-]+(?:\.[0-9A-Za-z-]+)*)?$"
)
SOURCE_MAP_DIRECTIVE = re.compile(r"(?://[#@]\s*sourceMappingURL=|/\*[#@]\s*sourceMappingURL=)")
IMMUTABLE_CACHE = "public,max-age=31536000,immutable"
REVALIDATE_CACHE = "no-cache"
ENTRYPOINT_CACHE = "no-store"


class EntrypointParser(HTMLParser):
    def __init__(self) -> None:
        super().__init__(convert_charrefs=True)
        self.references: set[str] = set()

    def handle_starttag(self, tag: str, attrs: list[tuple[str, str | None]]) -> None:
        values = dict(attrs)
        if tag == "script":
            source = values.get("src")
            if not source:
                raise ManifestError("Web entrypoint must not contain inline scripts")
            self.references.add(normalize_asset_reference(source))
        elif tag == "link" and values.get("href"):
            self.references.add(normalize_asset_reference(values["href"] or ""))

    def handle_startendtag(self, tag: str, attrs: list[tuple[str, str | None]]) -> None:
        self.handle_starttag(tag, attrs)


def normalize_asset_reference(value: str) -> str:
    parsed = urlsplit(value)
    if parsed.scheme or parsed.netloc or parsed.query or parsed.fragment:
        raise ManifestError("Web entrypoint must reference local immutable assets only")
    path = parsed.path.removeprefix("/")
    if not HASHED_ASSET.fullmatch(path):
        raise ManifestError("Web entrypoint references an unhashed asset")
    return path


def read_package_version(package_json: Path) -> str:
    try:
        package = json.loads(package_json.read_text(encoding="utf-8"))
        package_lock = json.loads(package_json.with_name("package-lock.json").read_text(encoding="utf-8"))
    except (json.JSONDecodeError, OSError) as error:
        raise ManifestError("Web package metadata is unreadable") from error
    locked_packages = package_lock.get("packages")
    locked_root = locked_packages.get("") if isinstance(locked_packages, dict) else None
    if not isinstance(locked_root, dict):
        raise ManifestError("Web package and lockfile versions must be one canonical SemVer")
    version = package.get("version")
    locked_versions = [package_lock.get("version"), locked_root.get("version")]
    if (package.get("name") != "chatroom-web" or package.get("private") is not True
            or not isinstance(version, str) or not WEB_SEMVER.fullmatch(version)
            or any(not isinstance(value, str) or value != version for value in locked_versions)):
        raise ManifestError("Web package and lockfile versions must be one canonical SemVer")
    return version


def validate_site(site_root: Path) -> tuple[list[Path], list[str]]:
    files = payload_files(site_root)
    relative_paths = {path.relative_to(site_root).as_posix() for path in files}
    if "index.html" not in relative_paths:
        raise ManifestError("Web payload is missing index.html")

    for path in files:
        relative = path.relative_to(site_root).as_posix()
        if any(ord(character) < 32 for character in relative):
            raise ManifestError("Web payload contains an unsafe path")
        if relative.endswith(".map"):
            raise ManifestError("Web verification payload must not publish source maps")
        if relative.startswith("assets/") and not HASHED_ASSET.fullmatch(relative):
            raise ManifestError("Web assets must use content-hashed names")
        if path.suffix in {".js", ".css"}:
            try:
                content = path.read_text(encoding="utf-8")
            except UnicodeDecodeError as error:
                raise ManifestError("Web JavaScript and CSS must be UTF-8") from error
            # Vite/esbuild emit a browser-consumed source-map directive at the
            # file trailer. Inspecting the trailer avoids treating third-party
            # runtime strings that generate CSS diagnostics as JS map output.
            if SOURCE_MAP_DIRECTIVE.search(content[-8192:]):
                raise ManifestError("Web verification payload must not reference source maps")

    parser = EntrypointParser()
    try:
        parser.feed((site_root / "index.html").read_text(encoding="utf-8"))
        parser.close()
    except UnicodeDecodeError as error:
        raise ManifestError("Web entrypoint must be UTF-8") from error
    if not parser.references or not any(reference.endswith(".js") for reference in parser.references):
        raise ManifestError("Web entrypoint must reference a hashed JavaScript module")
    missing = parser.references - relative_paths
    if missing:
        raise ManifestError("Web entrypoint references a missing asset")
    return files, sorted(parser.references)


def cache_control(relative: str) -> str:
    if relative == "index.html":
        return ENTRYPOINT_CACHE
    if relative.startswith("assets/"):
        return IMMUTABLE_CACHE
    return REVALIDATE_CACHE


def build_manifest(
    site_root: Path,
    package_json: Path,
    source_revision: str,
) -> tuple[dict[str, object], list[str]]:
    validate_revision(source_revision)
    version = read_package_version(package_json)
    files, entrypoints = validate_site(site_root)

    entries: list[dict[str, object]] = []
    checksums: list[str] = []
    for path in files:
        relative = path.relative_to(site_root).as_posix()
        digest, size = sha256_file(path)
        artifact_path = f"site/{relative}"
        entries.append({
            "path": artifact_path,
            "sha256": digest,
            "size": size,
            "cacheControl": cache_control(relative),
        })
        checksums.append(f"{digest}  {artifact_path}")

    return {
        "schemaVersion": 1,
        "product": "chat-room-web-client",
        "version": version,
        "sourceRevision": source_revision,
        "releaseStatus": "unsigned-not-deployed-verification-only",
        "entrypoint": "site/index.html",
        "referencedAssets": [f"site/{path}" for path in entrypoints],
        "files": entries,
    }, checksums


def write_manifest(output_dir: Path, manifest: dict[str, object], checksums: Iterable[str]) -> None:
    atomic_write(
        output_dir / "web-artifact-manifest.json",
        json.dumps(manifest, ensure_ascii=True, indent=2, sort_keys=True) + "\n",
    )
    atomic_write(output_dir / "SHA256SUMS", "\n".join(checksums) + "\n")


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--site-root", type=Path, required=True)
    parser.add_argument("--package-json", type=Path, required=True)
    parser.add_argument("--source-revision", required=True)
    parser.add_argument("--output-dir", type=Path, required=True)
    return parser.parse_args()


def main() -> int:
    args = parse_args()
    try:
        manifest, checksums = build_manifest(
            args.site_root,
            args.package_json,
            args.source_revision,
        )
        write_manifest(args.output_dir, manifest, checksums)
    except (ManifestError, OSError) as error:
        raise SystemExit(f"Web artifact manifest failed: {error}") from None
    print(
        "Web artifact manifest: "
        f"version={manifest['version']} files={len(manifest['files'])} "
        "status=unsigned-not-deployed-verification-only"
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
