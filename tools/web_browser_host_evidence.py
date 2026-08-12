#!/usr/bin/env python3
"""Verify one branded-browser Web release acceptance record."""

from __future__ import annotations

import argparse
import hashlib
import json
import re
from datetime import datetime, timedelta, timezone
from pathlib import Path

from artifact_manifest_common import ManifestError
from web_release_store import validate_release


HEX64 = re.compile(r"^[0-9a-f]{64}$")
BROWSER_VERSION = re.compile(r"^[1-9][0-9]*(?:\.[0-9]+){1,3}$")
ROOT_KEYS = {
    "schemaVersion", "evidenceType", "status", "product", "targetId",
    "browserFamily", "browserProduct", "supportPosition", "browserVersion",
    "browserExecutableSha256", "platform", "architecture", "userAgent",
    "releaseId", "version", "sourceRevision", "artifactManifestSha256",
    "checks", "observedAt",
}
CHECK_KEYS = {
    "productionLoginSurface", "requiredWebCapabilities", "indexedDb",
    "serverEndpointIsolation", "responsiveLogin", "keyboardAccessibleLogin",
    "announcedValidationError", "offlineLoginPaused",
    "recoveryStateAnnounced", "authenticatedClientShell",
    "credentialsRemainMemoryOnly", "authenticatedOfflineRecovery",
    "noPageErrors",
}
POLICY_KEYS = {"schemaVersion", "product", "targets"}
TARGET_KEYS = {
    "targetId", "browserFamily", "browserProduct", "supportPosition",
    "userAgentToken",
}


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
    policy = _read(policy_path, "Web browser support policy")
    targets = policy.get("targets")
    if (set(policy) != POLICY_KEYS or policy.get("schemaVersion") != 1
            or policy.get("product") != "chat-room-web-client"
            or not isinstance(targets, list) or len(targets) != 6):
        raise ManifestError("Web browser support policy shape is invalid")
    seen: set[str] = set()
    found = []
    for item in targets:
        if (not isinstance(item, dict) or set(item) != TARGET_KEYS
                or not all(isinstance(item.get(key), str) and item[key]
                           for key in TARGET_KEYS)
                or item["browserFamily"] not in {"chrome", "edge", "firefox"}
                or item["supportPosition"] not in {"current", "previous"}
                or item["targetId"] !=
                    f'{item["browserFamily"]}-{item["supportPosition"]}'
                or item["targetId"] in seen):
            raise ManifestError("Web browser support policy target is invalid")
        seen.add(str(item["targetId"]))
        if item["targetId"] == target_id:
            found.append(item)
    expected = {
        f"{family}-{position}"
        for family in ("chrome", "edge", "firefox")
        for position in ("current", "previous")
    }
    if seen != expected or len(found) != 1:
        raise ManifestError("Web browser support target is missing or duplicated")
    return found[0]


def verify_host_evidence(
    evidence_path: Path,
    policy_path: Path,
    target_id: str,
    release_root: Path,
    expected_browser_version: str,
    expected_executable_sha256: str,
    now_utc: datetime,
) -> dict[str, object]:
    target = _target(policy_path, target_id)
    identity = validate_release(release_root)
    if (not BROWSER_VERSION.fullmatch(expected_browser_version)
            or not HEX64.fullmatch(expected_executable_sha256)):
        raise ManifestError("Expected branded browser identity is invalid")
    evidence = _read(evidence_path, "Web browser host evidence")
    checks = evidence.get("checks")
    manifest_sha256 = hashlib.sha256(
        (release_root / "web-artifact-manifest.json").read_bytes()
    ).hexdigest()
    if (set(evidence) != ROOT_KEYS or evidence.get("schemaVersion") != 4
            or evidence.get("evidenceType") != "web-browser-host-acceptance"
            or evidence.get("status") != "candidate-smoke-observed"
            or evidence.get("product") != "chat-room-web-client"
            or evidence.get("targetId") != target_id
            or any(evidence.get(key) != target[key]
                   for key in ("browserFamily", "browserProduct", "supportPosition"))
            or evidence.get("browserVersion") != expected_browser_version
            or evidence.get("browserExecutableSha256") != expected_executable_sha256
            or not isinstance(evidence.get("platform"), str)
            or not evidence["platform"]
            or evidence.get("architecture") not in {"x86_64", "arm64"}
            or not isinstance(evidence.get("userAgent"), str)
            or target["userAgentToken"] not in evidence["userAgent"]
            or evidence.get("releaseId") != identity["releaseId"]
            or evidence.get("version") != identity["version"]
            or evidence.get("sourceRevision") != identity["sourceRevision"]
            or evidence.get("artifactManifestSha256") != manifest_sha256
            or not isinstance(checks, dict) or set(checks) != CHECK_KEYS
            or any(value is not True for value in checks.values())):
        raise ManifestError("Web browser host evidence identity or checks are invalid")
    try:
        observed = datetime.strptime(
            str(evidence.get("observedAt")), "%Y-%m-%dT%H:%M:%SZ"
        ).replace(tzinfo=timezone.utc)
    except ValueError as error:
        raise ManifestError("Web browser host observation time is invalid") from error
    if (now_utc.tzinfo != timezone.utc or now_utc.microsecond
            or observed > now_utc + timedelta(minutes=5)
            or observed < now_utc - timedelta(hours=24)):
        raise ManifestError("Web browser host evidence is stale or future")
    return evidence


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--evidence", type=Path, required=True)
    parser.add_argument("--policy", type=Path, required=True)
    parser.add_argument("--target-id", required=True)
    parser.add_argument("--release-root", type=Path, required=True)
    parser.add_argument("--expected-browser-version", required=True)
    parser.add_argument("--expected-executable-sha256", required=True)
    args = parser.parse_args()
    try:
        value = verify_host_evidence(
            args.evidence, args.policy, args.target_id, args.release_root,
            args.expected_browser_version, args.expected_executable_sha256,
            datetime.now(timezone.utc).replace(microsecond=0))
    except (ManifestError, OSError) as error:
        raise SystemExit(f"Web browser host evidence failed: {error}") from None
    print(json.dumps(value, ensure_ascii=True, sort_keys=True))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
