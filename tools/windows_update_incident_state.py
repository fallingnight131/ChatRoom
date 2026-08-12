#!/usr/bin/env python3
"""Persist and inspect the one open Windows update rollout incident."""

from __future__ import annotations

import hashlib
import json
import os
import tempfile
from datetime import datetime, timezone
from pathlib import Path

from artifact_manifest_common import ManifestError


OPEN_NAME = ".open-rollout-incident.json"
INCIDENT_DIRECTORY = ".rollout-incidents"
KEYS = {
    "schemaVersion", "incidentType", "status", "channel", "incidentId",
    "failedReleaseId", "restoredReleaseId", "promotionCompletionSha256",
    "openedAt",
}


def _read(path: Path, label: str) -> dict[str, object]:
    if path.is_symlink() or not path.is_file() or path.stat().st_size > 64 * 1024:
        raise ManifestError(f"{label} must be a bounded regular file")

    def unique(pairs):
        value = {}
        for key, item in pairs:
            if key in value:
                raise ManifestError(f"{label} has duplicate keys")
            value[key] = item
        return value

    try:
        value = json.loads(path.read_text(encoding="utf-8"), object_pairs_hook=unique)
    except (OSError, UnicodeDecodeError, json.JSONDecodeError) as error:
        raise ManifestError(f"{label} is unreadable") from error
    if not isinstance(value, dict) or set(value) != KEYS:
        raise ManifestError(f"{label} has an unsupported shape")
    return value


def _validate(value: dict[str, object], now_utc: datetime) -> None:
    digest = value.get("incidentId")
    releases = (value.get("failedReleaseId"), value.get("restoredReleaseId"))
    if (value.get("schemaVersion") != 1
            or value.get("incidentType") != "windows-update-rollout-halt"
            or value.get("status") != "open-awaiting-forward-fix"
            or value.get("channel") not in ("stable", "beta")
            or not isinstance(digest, str) or len(digest) != 64
            or any(character not in "0123456789abcdef" for character in digest)
            or value.get("promotionCompletionSha256") != digest
            or any(not isinstance(item, str) or len(item) != 64
                   or any(character not in "0123456789abcdef" for character in item)
                   for item in releases)
            or releases[0] == releases[1]):
        raise ManifestError("Open Windows rollout incident identity is invalid")
    try:
        opened = datetime.strptime(
            str(value.get("openedAt")), "%Y-%m-%dT%H:%M:%SZ").replace(
                tzinfo=timezone.utc)
    except ValueError as error:
        raise ManifestError("Open Windows rollout incident time is invalid") from error
    if opened > now_utc:
        raise ManifestError("Open Windows rollout incident is from the future")


def open_incident(
    store_root: Path,
    completion_path: Path,
    channel: str,
    failed_release_id: str,
    restored_release_id: str,
    now_utc: datetime,
) -> dict[str, object]:
    if now_utc.tzinfo != timezone.utc or now_utc.microsecond:
        raise ManifestError("Windows rollout incident clock must be exact UTC")
    if completion_path.is_symlink() or not completion_path.is_file():
        raise ManifestError("Windows rollout incident completion must be a regular file")
    active = store_root / OPEN_NAME
    directory = store_root / INCIDENT_DIRECTORY
    if (not store_root.is_absolute() or store_root.is_symlink()
            or not store_root.is_dir() or active.exists() or active.is_symlink()
            or directory.is_symlink()):
        raise ManifestError("Open Windows rollout incident already exists or store is unsafe")
    incident_id = hashlib.sha256(completion_path.read_bytes()).hexdigest()
    value = {
        "schemaVersion": 1,
        "incidentType": "windows-update-rollout-halt",
        "status": "open-awaiting-forward-fix",
        "channel": channel,
        "incidentId": incident_id,
        "failedReleaseId": failed_release_id,
        "restoredReleaseId": restored_release_id,
        "promotionCompletionSha256": incident_id,
        "openedAt": now_utc.strftime("%Y-%m-%dT%H:%M:%SZ"),
    }
    _validate(value, now_utc)
    directory.mkdir(parents=True, exist_ok=True)
    if directory.is_symlink() or not directory.is_dir():
        raise ManifestError("Windows rollout incident directory is unsafe")
    record = directory / f"{incident_id}.json"
    if record.exists() or record.is_symlink():
        raise ManifestError("Windows rollout incident record already exists")
    temporary: Path | None = None
    try:
        with tempfile.NamedTemporaryFile(
            "w", encoding="utf-8", newline="\n", dir=directory, delete=False,
        ) as stream:
            stream.write(json.dumps(value, ensure_ascii=True, indent=2,
                                    sort_keys=True) + "\n")
            stream.flush()
            os.fsync(stream.fileno())
            temporary = Path(stream.name)
        os.link(temporary, record)
        os.link(record, active)
    except FileExistsError as error:
        raise ManifestError("Open Windows rollout incident already exists") from error
    finally:
        if temporary is not None:
            temporary.unlink(missing_ok=True)
    return value


def inspect_open_incident(
    store_root: Path, now_utc: datetime,
) -> dict[str, object] | None:
    active = store_root / OPEN_NAME
    if not active.exists() and not active.is_symlink():
        return None
    value = _read(active, "Open Windows rollout incident")
    _validate(value, now_utc)
    record = store_root / INCIDENT_DIRECTORY / f"{value['incidentId']}.json"
    retained = _read(record, "Retained Windows rollout incident")
    if value != retained or active.read_bytes() != record.read_bytes():
        raise ManifestError("Open Windows rollout incident differs from retained record")
    return value


def require_no_open_incident(
    store_root: Path, channel: str, now_utc: datetime,
) -> None:
    incident = inspect_open_incident(store_root, now_utc)
    if incident is not None:
        if incident["channel"] != channel:
            raise ManifestError("Open Windows rollout incident channel is inconsistent")
        raise ManifestError(
            "Open Windows rollout incident requires dedicated forward-fix execution")
