#!/usr/bin/env python3
"""Verify Windows signature evidence against the final release bytes."""

from __future__ import annotations

import argparse
import json
import re
from datetime import datetime, timedelta, timezone
from pathlib import Path

from artifact_manifest_common import (
    ManifestError,
    read_version,
    sha256_file,
    validate_revision,
)


HEX64 = re.compile(r"^[0-9a-f]{64}$")
UTC_SECONDS = re.compile(r"^[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}Z$")
ROOT_KEYS = {
    "schemaVersion", "product", "version", "sourceRevision", "architecture",
    "observedAt", "expectedSignerCertificateSha256", "artifacts",
}
ARTIFACT_KEYS = {
    "role", "name", "size", "sha256", "signerCertificateSha256",
    "timestampCertificateSha256", "signatureStatus",
}
ROLES = ("client", "update-launcher", "uninstaller", "installer")


def _read_json(path: Path) -> dict[str, object]:
    if path.is_symlink() or not path.is_file():
        raise ManifestError("Windows release signature evidence must be a regular file")
    try:
        def strict_object(pairs: list[tuple[str, object]]) -> dict[str, object]:
            result: dict[str, object] = {}
            for key, item in pairs:
                if key in result:
                    raise ManifestError("Windows release signature evidence has duplicate keys")
                result[key] = item
            return result

        value = json.loads(
            path.read_text(encoding="utf-8"), object_pairs_hook=strict_object)
    except (OSError, UnicodeDecodeError, json.JSONDecodeError) as error:
        raise ManifestError("Windows release signature evidence is unreadable") from error
    if not isinstance(value, dict) or set(value) != ROOT_KEYS:
        raise ManifestError("Windows release signature evidence has an unsupported shape")
    return value


def _parse_observed_at(value: object, now_utc: datetime) -> datetime:
    if not isinstance(value, str) or not UTC_SECONDS.fullmatch(value):
        raise ManifestError("Windows release signature observation time is invalid")
    observed = datetime.strptime(value, "%Y-%m-%dT%H:%M:%SZ").replace(tzinfo=timezone.utc)
    if now_utc.tzinfo is None or now_utc.utcoffset() != timedelta(0):
        raise ManifestError("Windows release evidence verifier requires a UTC clock")
    if observed > now_utc + timedelta(minutes=5) or observed < now_utc - timedelta(hours=24):
        raise ManifestError("Windows release signature evidence is stale or from the future")
    return observed


def verify_evidence(
    evidence_path: Path,
    client_path: Path,
    launcher_path: Path,
    uninstaller_path: Path,
    installer_path: Path,
    version_file: Path,
    source_revision: str,
    expected_signer_sha256: str,
    now_utc: datetime,
) -> dict[str, object]:
    validate_revision(source_revision)
    version = read_version(version_file)
    if not HEX64.fullmatch(expected_signer_sha256):
        raise ManifestError("expected Windows publisher SHA-256 is invalid")
    evidence = _read_json(evidence_path)
    if (type(evidence["schemaVersion"]) is not int
            or evidence["schemaVersion"] != 2
            or evidence["product"] != "chat-room-windows-client"
            or evidence["version"] != version
            or evidence["sourceRevision"] != source_revision
            or evidence["architecture"] != "x86_64"
            or evidence["expectedSignerCertificateSha256"] != expected_signer_sha256):
        raise ManifestError("Windows release signature identity does not match the candidate")
    _parse_observed_at(evidence["observedAt"], now_utc)

    paths = {
        "client": (client_path, "ChatClient.exe"),
        "update-launcher": (launcher_path, "ChatRoomUpdateLauncher.exe"),
        "uninstaller": (uninstaller_path, f"ChatRoom-{version}-Uninstall.exe"),
        "installer": (installer_path, f"ChatRoom-{version}-Setup.exe"),
    }
    artifacts = evidence["artifacts"]
    if not isinstance(artifacts, list) or len(artifacts) != len(ROLES):
        raise ManifestError("Windows release signature artifact set is incomplete")
    for index, role in enumerate(ROLES):
        entry = artifacts[index]
        if not isinstance(entry, dict) or set(entry) != ARTIFACT_KEYS or entry["role"] != role:
            raise ManifestError("Windows release signature artifact shape or order is invalid")
        path, expected_name = paths[role]
        if path.is_symlink() or not path.is_file() or path.name != expected_name:
            raise ManifestError(f"Windows release {role} path or name is invalid")
        digest, size = sha256_file(path)
        if (entry["name"] != expected_name
                or type(entry["size"]) is not int or entry["size"] <= 0
                or entry["size"] != size
                or entry["sha256"] != digest
                or entry["signerCertificateSha256"] != expected_signer_sha256
                or not isinstance(entry["timestampCertificateSha256"], str)
                or not HEX64.fullmatch(entry["timestampCertificateSha256"])
                or entry["signatureStatus"] != "valid-timestamped-authenticode"):
            raise ManifestError(f"Windows release {role} evidence does not match final bytes")
    return evidence


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--evidence", type=Path, required=True)
    parser.add_argument("--client", type=Path, required=True)
    parser.add_argument("--launcher", type=Path, required=True)
    parser.add_argument("--uninstaller", type=Path, required=True)
    parser.add_argument("--installer", type=Path, required=True)
    parser.add_argument("--version-file", type=Path, required=True)
    parser.add_argument("--source-revision", required=True)
    parser.add_argument("--expected-signer-sha256", required=True)
    return parser.parse_args()


def main() -> int:
    args = parse_args()
    try:
        evidence = verify_evidence(
            args.evidence, args.client, args.launcher, args.uninstaller, args.installer,
            args.version_file, args.source_revision, args.expected_signer_sha256,
            datetime.now(timezone.utc),
        )
    except (ManifestError, OSError) as error:
        raise SystemExit(f"Windows release evidence verification failed: {error}") from None
    print(
        "Windows release evidence verified: "
        f"version={evidence['version']} revision={evidence['sourceRevision']}"
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
