#!/usr/bin/env python3
"""Stage closed Windows update candidates in an immutable local channel store."""

from __future__ import annotations

import argparse
import json
import os
import shutil
import tempfile
from datetime import datetime, timezone
from pathlib import Path

from artifact_manifest_common import ManifestError, payload_files, sha256_file
from windows_update_channel_candidate import validate_candidate


def validate_release(
    release_root: Path,
    version_file: Path,
    source_revision: str,
    channel: str,
    qt_version: str,
    authenticode_signer_sha256: str,
    public_key_file_sha256: str,
    now_utc: datetime,
) -> dict[str, object]:
    identity = validate_candidate(
        release_root, version_file, source_revision, channel, qt_version,
        authenticode_signer_sha256, public_key_file_sha256, now_utc,
    )
    release_id, _ = sha256_file(release_root / "update/manifest.json")
    return {**identity, "releaseId": release_id}


def stage_release(
    candidate_root: Path,
    store_root: Path,
    version_file: Path,
    source_revision: str,
    channel: str,
    qt_version: str,
    authenticode_signer_sha256: str,
    public_key_file_sha256: str,
    now_utc: datetime,
) -> dict[str, object]:
    identity = validate_release(
        candidate_root, version_file, source_revision, channel, qt_version,
        authenticode_signer_sha256, public_key_file_sha256, now_utc,
    )
    if store_root.exists() and (store_root.is_symlink() or not store_root.is_dir()):
        raise ManifestError("Windows update channel store root is unsafe")
    store_root.mkdir(parents=True, exist_ok=True)
    releases = store_root / "releases"
    releases.mkdir(exist_ok=True)
    if releases.is_symlink() or not releases.is_dir():
        raise ManifestError("Windows update channel releases boundary is unsafe")
    destination = releases / str(identity["releaseId"])
    if destination.exists() or destination.is_symlink():
        if destination.is_symlink() or not destination.is_dir():
            raise ManifestError("Windows update immutable release path is unsafe")
        existing = validate_release(
            destination, version_file, source_revision, channel, qt_version,
            authenticode_signer_sha256, public_key_file_sha256, now_utc,
        )
        if existing != identity:
            raise ManifestError("Windows update immutable release identity changed")
        return {**identity, "stageStatus": "already-present"}

    temporary = Path(tempfile.mkdtemp(prefix=".staging-", dir=releases))
    try:
        for source in payload_files(candidate_root):
            relative = source.relative_to(candidate_root)
            target = temporary / relative
            target.parent.mkdir(parents=True, exist_ok=True)
            shutil.copyfile(source, target)
        copied = validate_release(
            temporary, version_file, source_revision, channel, qt_version,
            authenticode_signer_sha256, public_key_file_sha256, now_utc,
        )
        if copied != identity:
            raise ManifestError("Windows update release identity changed during staging")
        os.rename(temporary, destination)
    except Exception:
        shutil.rmtree(temporary, ignore_errors=True)
        raise
    return {**identity, "stageStatus": "staged"}


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("stage", choices=("stage",))
    parser.add_argument("--candidate-root", type=Path, required=True)
    parser.add_argument("--store-root", type=Path, required=True)
    parser.add_argument("--version-file", type=Path, required=True)
    parser.add_argument("--source-revision", required=True)
    parser.add_argument("--channel", choices=("stable", "beta"), required=True)
    parser.add_argument("--qt-version", required=True)
    parser.add_argument("--authenticode-signer-sha256", required=True)
    parser.add_argument("--public-key-file-sha256", required=True)
    args = parser.parse_args()
    now = datetime.now(timezone.utc).replace(microsecond=0)
    try:
        value = stage_release(
            args.candidate_root, args.store_root, args.version_file,
            args.source_revision, args.channel, args.qt_version,
            args.authenticode_signer_sha256, args.public_key_file_sha256, now,
        )
    except (ManifestError, OSError) as error:
        raise SystemExit(f"Windows update channel store failed: {error}") from None
    print(json.dumps(value, ensure_ascii=True, sort_keys=True))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
