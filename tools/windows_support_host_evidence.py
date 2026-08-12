#!/usr/bin/env python3
"""Verify one clean-host Windows product-support acceptance record."""

from __future__ import annotations

import argparse
import json
import re
from datetime import datetime, timedelta, timezone
from pathlib import Path

from artifact_manifest_common import ManifestError, read_version, sha256_file, validate_revision
from windows_release_candidate import validate_candidate


HEX64 = re.compile(r"^[0-9a-f]{64}$")
ROOT_KEYS = {
    "schemaVersion", "evidenceType", "status", "product", "targetId",
    "architecture", "osCaption", "osVersion", "osBuild", "osProductType",
    "currentVersion", "currentSourceRevision", "previousVersion",
    "previousSourceRevision", "channel", "qtVersion",
    "expectedSignerCertificateSha256", "currentCandidateManifestSha256",
    "previousCandidateManifestSha256", "checks", "observedAt",
}
CHECK_KEYS = {
    "cleanHost", "previousInstalled", "previousLaunched",
    "upgradeSucceeded", "accountDataPreservedOnUpgrade", "currentLaunched",
    "runningClientUpgradeRejected", "downgradeRejected", "uninstallSucceeded",
    "accountDataPreservedOnUninstall", "programFilesRemoved",
    "registrationRemoved",
}
POLICY_KEYS = {"schemaVersion", "product", "architecture", "targets"}
TARGET_KEYS = {"targetId", "captionContains", "build"}


def _read(path: Path, label: str) -> dict[str, object]:
    if path.is_symlink() or not path.is_file() or path.stat().st_size > 1024 * 1024:
        raise ManifestError(f"{label} must be a bounded regular file")

    def unique(pairs):
        result = {}
        for key, value in pairs:
            if key in result:
                raise ManifestError(f"{label} has duplicate keys")
            result[key] = value
        return result

    try:
        value = json.loads(path.read_text(encoding="utf-8"), object_pairs_hook=unique)
    except (OSError, UnicodeDecodeError, json.JSONDecodeError) as error:
        raise ManifestError(f"{label} is unreadable") from error
    if not isinstance(value, dict):
        raise ManifestError(f"{label} must be an object")
    return value


def _target(policy_path: Path, target_id: str) -> dict[str, object]:
    policy = _read(policy_path, "Windows support policy")
    targets = policy.get("targets")
    if (set(policy) != POLICY_KEYS or policy.get("schemaVersion") != 1
            or policy.get("product") != "chat-room-windows-client"
            or policy.get("architecture") != "x86_64"
            or not isinstance(targets, list) or not targets):
        raise ManifestError("Windows support policy shape is invalid")
    found = []
    for item in targets:
        if (not isinstance(item, dict) or set(item) != TARGET_KEYS
                or not isinstance(item.get("targetId"), str)
                or not isinstance(item.get("captionContains"), str)
                or not item["captionContains"]
                or type(item.get("build")) is not int or item["build"] <= 0):
            raise ManifestError("Windows support policy target is invalid")
        if item["targetId"] == target_id:
            found.append(item)
    if len(found) != 1:
        raise ManifestError("Windows support target is missing or duplicated")
    return found[0]


def verify_host_evidence(
    evidence_path: Path,
    policy_path: Path,
    target_id: str,
    current_root: Path,
    current_version_file: Path,
    current_source_revision: str,
    previous_root: Path,
    previous_version_file: Path,
    previous_source_revision: str,
    channel: str,
    qt_version: str,
    expected_signer_sha256: str,
    now_utc: datetime,
) -> dict[str, object]:
    target = _target(policy_path, target_id)
    current_version = read_version(current_version_file)
    previous_version = read_version(previous_version_file)
    validate_revision(current_source_revision)
    validate_revision(previous_source_revision)
    current_parts = tuple(int(part) for part in current_version.split("."))
    previous_parts = tuple(int(part) for part in previous_version.split("."))
    if (current_parts <= previous_parts
            or not HEX64.fullmatch(expected_signer_sha256)):
        raise ManifestError("Windows support candidate transition is invalid")
    validate_candidate(
        current_root, current_version_file, current_source_revision, channel,
        qt_version, expected_signer_sha256, now_utc)
    validate_candidate(
        previous_root, previous_version_file, previous_source_revision, channel,
        qt_version, expected_signer_sha256, now_utc)
    evidence = _read(evidence_path, "Windows support host evidence")
    checks = evidence.get("checks")
    current_manifest_sha, _ = sha256_file(
        current_root / "windows-release-candidate.json")
    previous_manifest_sha, _ = sha256_file(
        previous_root / "windows-release-candidate.json")
    if (set(evidence) != ROOT_KEYS or evidence.get("schemaVersion") != 1
            or evidence.get("evidenceType") != "windows-support-host-acceptance"
            or evidence.get("status") != "clean-install-upgrade-uninstall-observed"
            or evidence.get("product") != "chat-room-windows-client"
            or evidence.get("targetId") != target_id
            or evidence.get("architecture") != "x86_64"
            or not isinstance(evidence.get("osCaption"), str)
            or target["captionContains"] not in evidence["osCaption"]
            or not isinstance(evidence.get("osVersion"), str)
            or evidence.get("osBuild") != target["build"]
            or evidence.get("osProductType") != 1
            or evidence.get("currentVersion") != current_version
            or evidence.get("currentSourceRevision") != current_source_revision
            or evidence.get("previousVersion") != previous_version
            or evidence.get("previousSourceRevision") != previous_source_revision
            or evidence.get("channel") != channel
            or evidence.get("qtVersion") != qt_version
            or evidence.get("expectedSignerCertificateSha256")
                != expected_signer_sha256
            or evidence.get("currentCandidateManifestSha256")
                != current_manifest_sha
            or evidence.get("previousCandidateManifestSha256")
                != previous_manifest_sha
            or not isinstance(checks, dict) or set(checks) != CHECK_KEYS
            or any(value is not True for value in checks.values())):
        raise ManifestError("Windows support host evidence identity or checks are invalid")
    try:
        observed = datetime.strptime(
            str(evidence.get("observedAt")), "%Y-%m-%dT%H:%M:%SZ").replace(
                tzinfo=timezone.utc)
    except ValueError as error:
        raise ManifestError("Windows support host observation time is invalid") from error
    if (now_utc.tzinfo != timezone.utc or now_utc.microsecond
            or observed > now_utc + timedelta(minutes=5)
            or observed < now_utc - timedelta(hours=24)):
        raise ManifestError("Windows support host evidence is stale or future")
    return evidence


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--evidence", type=Path, required=True)
    parser.add_argument("--policy", type=Path, required=True)
    parser.add_argument("--target-id", required=True)
    parser.add_argument("--current-candidate-root", type=Path, required=True)
    parser.add_argument("--current-version-file", type=Path, required=True)
    parser.add_argument("--current-source-revision", required=True)
    parser.add_argument("--previous-candidate-root", type=Path, required=True)
    parser.add_argument("--previous-version-file", type=Path, required=True)
    parser.add_argument("--previous-source-revision", required=True)
    parser.add_argument("--channel", choices=("stable", "beta"), required=True)
    parser.add_argument("--qt-version", required=True)
    parser.add_argument("--expected-signer-sha256", required=True)
    args = parser.parse_args()
    try:
        value = verify_host_evidence(
            args.evidence, args.policy, args.target_id,
            args.current_candidate_root, args.current_version_file,
            args.current_source_revision, args.previous_candidate_root,
            args.previous_version_file, args.previous_source_revision,
            args.channel, args.qt_version, args.expected_signer_sha256,
            datetime.now(timezone.utc).replace(microsecond=0))
    except (ManifestError, OSError) as error:
        raise SystemExit(f"Windows support host evidence failed: {error}") from None
    print(json.dumps(value, ensure_ascii=True, sort_keys=True))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
