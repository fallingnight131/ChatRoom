#!/usr/bin/env python3
"""Select and inspect an immutable Web release on the non-production preview origin."""

from __future__ import annotations

import argparse
import json
from datetime import datetime, timezone
from pathlib import Path

from artifact_manifest_common import ManifestError, atomic_write
from web_release_store import validate_release


POINTER = "preview-release.json"
KEYS = {
    "schemaVersion", "purpose", "releaseId", "version", "sourceRevision",
    "responsePolicySha256", "entrypoint", "fileCount", "selectedAt",
}


def _read(path: Path) -> dict[str, object]:
    if path.is_symlink() or not path.is_file() or path.stat().st_size > 64 * 1024:
        raise ManifestError("Web preview pointer must be a bounded regular file")

    def unique(pairs):
        result = {}
        for key, value in pairs:
            if key in result:
                raise ManifestError("Web preview pointer has duplicate keys")
            result[key] = value
        return result

    try:
        value = json.loads(path.read_text(encoding="utf-8"), object_pairs_hook=unique)
    except (OSError, UnicodeDecodeError, json.JSONDecodeError) as error:
        raise ManifestError("Web preview pointer is unreadable") from error
    if not isinstance(value, dict) or set(value) != KEYS:
        raise ManifestError("Web preview pointer shape is invalid")
    return value


def _time(value: object) -> datetime:
    try:
        parsed = datetime.strptime(str(value), "%Y-%m-%dT%H:%M:%SZ").replace(
            tzinfo=timezone.utc)
    except ValueError as error:
        raise ManifestError("Web preview selection time is invalid") from error
    return parsed


def select_preview(
    store_root: Path, release_id: str, now_utc: datetime,
) -> dict[str, object]:
    if now_utc.tzinfo != timezone.utc or now_utc.microsecond:
        raise ManifestError("Web preview selection clock must be exact UTC")
    if (not store_root.is_absolute() or store_root.is_symlink()
            or not store_root.is_dir() or (store_root / "releases").is_symlink()
            or (store_root / POINTER).is_symlink()):
        raise ManifestError("Web preview store boundary is unsafe")
    if (not release_id or release_id in {".", ".."}
            or "/" in release_id or "\\" in release_id):
        raise ManifestError("Web preview release ID is unsafe")
    identity = validate_release(store_root / "releases" / release_id)
    if identity["releaseId"] != release_id:
        raise ManifestError("Web preview directory differs from release identity")
    value = {
        "schemaVersion": 1,
        "purpose": "non-production-candidate-preview",
        **identity,
        "selectedAt": now_utc.strftime("%Y-%m-%dT%H:%M:%SZ"),
    }
    atomic_write(store_root / POINTER, json.dumps(
        value, ensure_ascii=True, indent=2, sort_keys=True) + "\n")
    if inspect_preview(store_root, now_utc) != {"status": "healthy", **value}:
        raise ManifestError("Web preview pointer did not select candidate")
    return value


def inspect_preview(
    store_root: Path, now_utc: datetime,
) -> dict[str, object]:
    value = _read(store_root / POINTER)
    if value.get("schemaVersion") != 1 or value.get("purpose") != "non-production-candidate-preview":
        raise ManifestError("Web preview pointer identity is invalid")
    selected = _time(value.get("selectedAt"))
    if now_utc.tzinfo != timezone.utc or now_utc.microsecond or selected > now_utc:
        raise ManifestError("Web preview pointer time is future or clock is invalid")
    release_id = value.get("releaseId")
    if not isinstance(release_id, str):
        raise ManifestError("Web preview release ID is invalid")
    identity = validate_release(store_root / "releases" / release_id)
    expected = {
        "schemaVersion": 1,
        "purpose": "non-production-candidate-preview",
        **identity,
        "selectedAt": value["selectedAt"],
    }
    if value != expected:
        raise ManifestError("Web preview pointer differs from immutable release")
    return {"status": "healthy", **value}


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("command", choices=("select", "status"))
    parser.add_argument("--store-root", type=Path, required=True)
    parser.add_argument("--release-id")
    args = parser.parse_args()
    now = datetime.now(timezone.utc).replace(microsecond=0)
    try:
        if args.command == "select":
            if args.release_id is None:
                raise ManifestError("Web preview release ID is required")
            value = select_preview(args.store_root, args.release_id, now)
        else:
            value = inspect_preview(args.store_root, now)
    except (ManifestError, OSError) as error:
        raise SystemExit(f"Web preview selection failed: {error}") from None
    print(json.dumps(value, ensure_ascii=True, sort_keys=True))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
