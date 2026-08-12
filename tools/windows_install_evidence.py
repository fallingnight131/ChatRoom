#!/usr/bin/env python3
"""Independently verify native Windows install/uninstall acceptance evidence."""

from __future__ import annotations

import argparse
import json
import re
from datetime import datetime, timedelta, timezone
from pathlib import Path

from artifact_manifest_common import ManifestError, read_version, sha256_file, validate_revision


HEX64 = re.compile(r"^[0-9a-f]{64}$")
UTC_SECONDS = re.compile(r"^[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}Z$")
ROOT_KEYS = {
    "schemaVersion", "evidenceType", "status", "product", "version",
    "sourceRevision", "architecture", "observedAt",
    "expectedSignerCertificateSha256", "sourceArtifacts", "installedArtifacts",
    "installExitCode", "uninstallExitCode", "registrationMatched",
    "installRootRemoved", "temporaryPathsRemoved", "registrationRemoved",
}
ENTRY_KEYS = {"role", "name", "size", "sha256"}
SOURCE_ROLES = ("client", "update-launcher", "uninstaller", "installer")
INSTALLED_ROLES = ("client", "update-launcher", "uninstaller")


def _read(path: Path) -> dict[str, object]:
    if path.is_symlink() or not path.is_file():
        raise ManifestError("Windows install evidence must be a regular file")

    def strict_object(pairs: list[tuple[str, object]]) -> dict[str, object]:
        result: dict[str, object] = {}
        for key, value in pairs:
            if key in result:
                raise ManifestError("Windows install evidence has duplicate keys")
            result[key] = value
        return result

    try:
        value = json.loads(path.read_text(encoding="utf-8"), object_pairs_hook=strict_object)
    except (OSError, UnicodeDecodeError, json.JSONDecodeError) as error:
        raise ManifestError("Windows install evidence is unreadable") from error
    if not isinstance(value, dict) or set(value) != ROOT_KEYS:
        raise ManifestError("Windows install evidence has an unsupported shape")
    return value


def _entries(value: object, roles: tuple[str, ...], label: str) -> dict[str, dict[str, object]]:
    if not isinstance(value, list) or len(value) != len(roles):
        raise ManifestError(f"Windows {label} artifact set is incomplete")
    result: dict[str, dict[str, object]] = {}
    for index, role in enumerate(roles):
        entry = value[index]
        if (not isinstance(entry, dict) or set(entry) != ENTRY_KEYS
                or entry.get("role") != role
                or not isinstance(entry.get("name"), str)
                or type(entry.get("size")) is not int or entry["size"] <= 0
                or not isinstance(entry.get("sha256"), str)
                or not HEX64.fullmatch(entry["sha256"])):
            raise ManifestError(f"Windows {label} artifact entry is invalid")
        result[role] = entry
    return result


def verify_install_evidence(
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
    version = read_version(version_file)
    validate_revision(source_revision)
    if not HEX64.fullmatch(expected_signer_sha256):
        raise ManifestError("Windows install evidence signer identity is invalid")
    evidence = _read(evidence_path)
    if (type(evidence["schemaVersion"]) is not int or evidence["schemaVersion"] != 1
            or evidence["evidenceType"] != "windows-native-install-acceptance"
            or evidence["status"] != "install-uninstall-observed"
            or evidence["product"] != "chat-room-windows-client"
            or evidence["version"] != version
            or evidence["sourceRevision"] != source_revision
            or evidence["architecture"] != "x86_64"
            or evidence["expectedSignerCertificateSha256"] != expected_signer_sha256
            or type(evidence["installExitCode"]) is not int
            or type(evidence["uninstallExitCode"]) is not int
            or evidence["installExitCode"] != 0 or evidence["uninstallExitCode"] != 0
            or evidence["registrationMatched"] is not True
            or evidence["installRootRemoved"] is not True
            or evidence["temporaryPathsRemoved"] is not True
            or evidence["registrationRemoved"] is not True):
        raise ManifestError("Windows install evidence identity or result is invalid")
    observed_value = evidence["observedAt"]
    if not isinstance(observed_value, str) or not UTC_SECONDS.fullmatch(observed_value):
        raise ManifestError("Windows install evidence observation time is invalid")
    observed = datetime.strptime(observed_value, "%Y-%m-%dT%H:%M:%SZ").replace(tzinfo=timezone.utc)
    if now_utc.tzinfo is None or now_utc.utcoffset() != timedelta(0):
        raise ManifestError("Windows install evidence verifier requires a UTC clock")
    if observed > now_utc + timedelta(minutes=5) or observed < now_utc - timedelta(hours=24):
        raise ManifestError("Windows install evidence is stale or from the future")

    source = _entries(evidence["sourceArtifacts"], SOURCE_ROLES, "source")
    installed = _entries(evidence["installedArtifacts"], INSTALLED_ROLES, "installed")
    paths = {
        "client": (client_path, "ChatClient.exe"),
        "update-launcher": (launcher_path, "ChatRoomUpdateLauncher.exe"),
        "uninstaller": (uninstaller_path, f"ChatRoom-{version}-Uninstall.exe"),
        "installer": (installer_path, f"ChatRoom-{version}-Setup.exe"),
    }
    for role, (path, expected_name) in paths.items():
        if path.is_symlink() or not path.is_file() or path.name != expected_name:
            raise ManifestError(f"Windows install evidence {role} source path is invalid")
        digest, size = sha256_file(path)
        entry = source[role]
        if entry["name"] != expected_name or entry["sha256"] != digest or entry["size"] != size:
            raise ManifestError(f"Windows install evidence {role} source bytes changed")
        if role in installed:
            installed_entry = installed[role]
            installed_name = "Uninstall.exe" if role == "uninstaller" else expected_name
            if (installed_entry["name"] != installed_name
                    or installed_entry["sha256"] != digest
                    or installed_entry["size"] != size):
                raise ManifestError(f"Windows installed {role} did not match source bytes")
    return evidence


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--evidence", type=Path, required=True)
    parser.add_argument("--client", type=Path, required=True)
    parser.add_argument("--launcher", type=Path, required=True)
    parser.add_argument("--uninstaller", type=Path, required=True)
    parser.add_argument("--installer", type=Path, required=True)
    parser.add_argument("--version-file", type=Path, required=True)
    parser.add_argument("--source-revision", required=True)
    parser.add_argument("--expected-signer-sha256", required=True)
    args = parser.parse_args()
    try:
        evidence = verify_install_evidence(
            args.evidence, args.client, args.launcher, args.uninstaller, args.installer,
            args.version_file, args.source_revision, args.expected_signer_sha256,
            datetime.now(timezone.utc),
        )
    except (ManifestError, OSError) as error:
        raise SystemExit(f"Windows install evidence verification failed: {error}") from None
    print(f"Windows install evidence verified: version={evidence['version']}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
