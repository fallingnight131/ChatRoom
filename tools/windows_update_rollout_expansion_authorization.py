#!/usr/bin/env python3
"""Authorize one health-gated Windows rollout expansion without executing it."""

from __future__ import annotations

import argparse
import hashlib
import json
import os
import re
import subprocess
import tempfile
from datetime import datetime, timedelta, timezone
from pathlib import Path

from artifact_manifest_common import ManifestError
from windows_update_channel_candidate import validate_candidate
from windows_update_manifest import verify_manifest_signature
from windows_update_product_trust_intent import SPKI_PREFIX
from windows_update_release_completion import verify_completion
from windows_update_rollout_health import verify as verify_health


STATUS = "rollout-expansion-approved-not-executed"
ENVIRONMENT = "windows-update-production"
MAX_TARGET_AGE = timedelta(hours=24)
KEY_ID = re.compile(r"^[a-z0-9][a-z0-9.-]{0,63}$")
HEX64 = re.compile(r"^[0-9a-f]{64}$")
KEYS = {
    "schemaVersion", "authorizationType", "status", "environment", "channel",
    "version", "sourceRevision", "currentManifestSequence",
    "targetManifestSequence", "currentRolloutPercentage",
    "targetRolloutPercentage", "rolloutSeed", "metricsKeyId",
    "metricsPublicKeyFileSha256", "metricsSignatureSha256",
    "metricsSha256", "healthDecisionSha256", "promotionCompletionSha256",
    "expectedCurrentManifestSha256", "targetManifestSha256",
    "targetCandidateManifestSha256", "windowsCandidateManifestSha256",
    "expectedAuthenticodeSignerSha256", "updatePublicKeyFileSha256",
    "approvedAt", "expiresAt",
}


def _read(path: Path, label: str) -> dict[str, object]:
    if path.is_symlink() or not path.is_file() or path.stat().st_size > 1024 * 1024:
        raise ManifestError(f"Windows rollout expansion {label} is unsafe")

    def unique(pairs):
        result = {}
        for key, value in pairs:
            if key in result:
                raise ManifestError(f"Windows rollout expansion {label} has duplicate keys")
            result[key] = value
        return result

    try:
        value = json.loads(path.read_text(encoding="utf-8"), object_pairs_hook=unique)
    except (OSError, UnicodeDecodeError, json.JSONDecodeError) as error:
        raise ManifestError(f"Windows rollout expansion {label} is unreadable") from error
    if not isinstance(value, dict):
        raise ManifestError(f"Windows rollout expansion {label} must be an object")
    return value


def _time(value: object, label: str) -> datetime:
    try:
        return datetime.strptime(str(value), "%Y-%m-%dT%H:%M:%SZ").replace(
            tzinfo=timezone.utc)
    except ValueError as error:
        raise ManifestError(f"Windows rollout expansion {label} is invalid") from error


def _digest(path: Path) -> str:
    if path.is_symlink() or not path.is_file():
        raise ManifestError("Windows rollout expansion input must be a regular file")
    return hashlib.sha256(path.read_bytes()).hexdigest()


def _verify_metrics_signature(
    metrics_path: Path,
    signature_path: Path,
    public_key_path: Path,
    key_id: str,
    expected_public_key_sha256: str,
) -> None:
    if (not KEY_ID.fullmatch(key_id) or not HEX64.fullmatch(expected_public_key_sha256)
            or signature_path.is_symlink() or not signature_path.is_file()
            or signature_path.stat().st_size != 64
            or public_key_path.is_symlink() or not public_key_path.is_file()
            or _digest(public_key_path) != expected_public_key_sha256):
        raise ManifestError("Windows rollout metrics attestation identity is invalid")
    try:
        inspected = subprocess.run(
            ["openssl", "pkey", "-pubin", "-in", str(public_key_path),
             "-outform", "DER"],
            check=False, stdout=subprocess.PIPE, stderr=subprocess.DEVNULL)
        verified = subprocess.run(
            ["openssl", "pkeyutl", "-verify", "-pubin", "-rawin",
             "-inkey", str(public_key_path), "-in", str(metrics_path),
             "-sigfile", str(signature_path)],
            check=False, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    except OSError as error:
        raise ManifestError("OpenSSL is unavailable for rollout metrics attestation") from error
    if (inspected.returncode != 0 or inspected.stdout[:len(SPKI_PREFIX)] != SPKI_PREFIX
            or len(inspected.stdout) != len(SPKI_PREFIX) + 32
            or verified.returncode != 0):
        raise ManifestError("Windows rollout metrics attestation is invalid")


def _candidate(
    root: Path,
    version_file: Path,
    source_revision: str,
    channel: str,
    qt_version: str,
    signer_sha256: str,
    update_public_key_sha256: str,
    now_utc: datetime,
    require_fresh_candidate: bool,
) -> tuple[dict[str, object], dict[str, object], dict[str, object]]:
    identity = validate_candidate(
        root, version_file, source_revision, channel, qt_version,
        signer_sha256, update_public_key_sha256, now_utc)
    outer = _read(root / "windows-update-channel-candidate.json", "candidate manifest")
    assembled = _time(outer.get("assembledAt"), "candidate assembly time")
    if (assembled > now_utc
            or (require_fresh_candidate and now_utc - assembled > MAX_TARGET_AGE)):
        raise ManifestError("Windows rollout expansion candidate is stale or from the future")
    update = verify_manifest_signature(
        root / "update/manifest.json", root / "update/manifest.json.sig",
        root / "evidence/update-public-key.pem", now_utc)
    return identity, outer, update


def create_authorization(
    completion_path: Path,
    execution_path: Path,
    promotion_authorization_path: Path,
    current_candidate_root: Path,
    rollback_release_root: Path,
    pre_promotion_manifest_path: Path,
    promotion_observation_path: Path,
    health_decision_path: Path,
    metrics_path: Path,
    metrics_signature_path: Path,
    metrics_public_key_path: Path,
    metrics_key_id: str,
    metrics_public_key_sha256: str,
    health_policy_path: Path,
    target_candidate_root: Path,
    version_file: Path,
    source_revision: str,
    channel: str,
    qt_version: str,
    authenticode_signer_sha256: str,
    update_public_key_sha256: str,
    now_utc: datetime,
    lifetime_seconds: int = 900,
) -> dict[str, object]:
    if now_utc.tzinfo != timezone.utc or now_utc.microsecond:
        raise ManifestError("Windows rollout expansion clock must be an exact UTC second")
    if not 60 <= lifetime_seconds <= 900:
        raise ManifestError("Windows rollout expansion lifetime must be 60 to 900 seconds")
    completion = verify_completion(
        completion_path, execution_path, promotion_authorization_path,
        current_candidate_root, rollback_release_root, pre_promotion_manifest_path,
        version_file, source_revision, channel, qt_version,
        authenticode_signer_sha256, update_public_key_sha256,
        promotion_observation_path)
    health = verify_health(
        health_decision_path, completion_path, current_candidate_root,
        metrics_path, health_policy_path)
    if health.get("decision") != "expand-eligible":
        raise ManifestError("Windows rollout health does not permit expansion")
    health_recorded = _time(health.get("recordedAt"), "health decision time")
    if health_recorded > now_utc or now_utc - health_recorded > timedelta(minutes=5):
        raise ManifestError("Windows rollout health decision is stale or from the future")
    _verify_metrics_signature(
        metrics_path, metrics_signature_path, metrics_public_key_path,
        metrics_key_id, metrics_public_key_sha256)
    current_identity, current_outer, current_update = _candidate(
        current_candidate_root, version_file, source_revision, channel,
        qt_version, authenticode_signer_sha256, update_public_key_sha256,
        now_utc, False)
    target_identity, target_outer, target_update = _candidate(
        target_candidate_root, version_file, source_revision, channel,
        qt_version, authenticode_signer_sha256, update_public_key_sha256,
        now_utc, True)
    if (current_identity["version"] != target_identity["version"]
            or current_update["version"] != target_update["version"]
            or current_update["sourceRevision"] != target_update["sourceRevision"]
            or current_outer["windowsCandidateManifestSha256"]
                != target_outer["windowsCandidateManifestSha256"]
            or current_update["installer"] != target_update["installer"]
            or current_update["signingKeyId"] != target_update["signingKeyId"]
            or current_update["minimumUpdatableVersion"]
                != target_update["minimumUpdatableVersion"]
            or current_update["rollout"]["seed"] != target_update["rollout"]["seed"]
            or target_update["manifestSequence"] <= current_update["manifestSequence"]
            or health["releaseId"] != completion["releaseId"]
            or health["currentRolloutPercentage"]
                != current_update["rollout"]["percentage"]
            or health["nextRolloutPercentage"]
                != target_update["rollout"]["percentage"]):
        raise ManifestError("Windows rollout expansion target is not the approved next step")
    approved = now_utc.strftime("%Y-%m-%dT%H:%M:%SZ")
    return {
        "schemaVersion": 1,
        "authorizationType": "windows-update-rollout-expansion",
        "status": STATUS,
        "environment": ENVIRONMENT,
        "channel": channel,
        "version": current_update["version"],
        "sourceRevision": source_revision,
        "currentManifestSequence": current_update["manifestSequence"],
        "targetManifestSequence": target_update["manifestSequence"],
        "currentRolloutPercentage": current_update["rollout"]["percentage"],
        "targetRolloutPercentage": target_update["rollout"]["percentage"],
        "rolloutSeed": current_update["rollout"]["seed"],
        "metricsKeyId": metrics_key_id,
        "metricsPublicKeyFileSha256": metrics_public_key_sha256,
        "metricsSignatureSha256": _digest(metrics_signature_path),
        "metricsSha256": _digest(metrics_path),
        "healthDecisionSha256": _digest(health_decision_path),
        "promotionCompletionSha256": _digest(completion_path),
        "expectedCurrentManifestSha256": _digest(
            current_candidate_root / "update/manifest.json"),
        "targetManifestSha256": _digest(target_candidate_root / "update/manifest.json"),
        "targetCandidateManifestSha256": _digest(
            target_candidate_root / "windows-update-channel-candidate.json"),
        "windowsCandidateManifestSha256": current_outer["windowsCandidateManifestSha256"],
        "expectedAuthenticodeSignerSha256": authenticode_signer_sha256,
        "updatePublicKeyFileSha256": update_public_key_sha256,
        "approvedAt": approved,
        "expiresAt": (now_utc + timedelta(seconds=lifetime_seconds)).strftime(
            "%Y-%m-%dT%H:%M:%SZ"),
    }


def write_once(path: Path, value: dict[str, object]) -> None:
    if path.exists() or path.is_symlink() or not path.is_absolute():
        raise ManifestError("Windows rollout expansion output is unsafe or already exists")
    path.parent.mkdir(parents=True, exist_ok=True)
    if path.parent.is_symlink() or not path.parent.is_dir():
        raise ManifestError("Windows rollout expansion output directory is unsafe")
    temporary: Path | None = None
    try:
        with tempfile.NamedTemporaryFile(
            "w", encoding="utf-8", newline="\n", dir=path.parent, delete=False,
        ) as stream:
            stream.write(json.dumps(value, ensure_ascii=True, indent=2, sort_keys=True) + "\n")
            stream.flush()
            os.fsync(stream.fileno())
            temporary = Path(stream.name)
        try:
            os.link(temporary, path)
        except FileExistsError as error:
            raise ManifestError("Windows rollout expansion output already exists") from error
    finally:
        if temporary is not None:
            temporary.unlink(missing_ok=True)


def verify_authorization(path: Path, *args, now_utc: datetime) -> dict[str, object]:
    recorded = _read(path, "authorization")
    if set(recorded) != KEYS:
        raise ManifestError("Windows rollout expansion authorization shape is invalid")
    approved = _time(recorded.get("approvedAt"), "approval time")
    expires = _time(recorded.get("expiresAt"), "expiry time")
    lifetime = int((expires - approved).total_seconds())
    if lifetime < 60 or lifetime > 900:
        raise ManifestError("Windows rollout expansion authorization lifetime is invalid")
    if (now_utc.tzinfo != timezone.utc or now_utc.microsecond
            or approved > now_utc + timedelta(minutes=1) or now_utc >= expires):
        raise ManifestError("Windows rollout expansion authorization is expired or future")
    expected = create_authorization(*args, approved, lifetime)
    if recorded != expected:
        raise ManifestError("Windows rollout expansion authorization does not match inputs")
    return recorded


def add_authorization_arguments(parser: argparse.ArgumentParser) -> None:
    paths = (
        "completion", "execution", "promotion-authorization", "current-candidate-root",
        "rollback-release-root", "pre-promotion-manifest", "promotion-observation",
        "health-decision", "metrics", "metrics-signature", "metrics-public-key",
        "health-policy", "target-candidate-root", "version-file",
    )
    for name in paths:
        parser.add_argument(f"--{name}", type=Path, required=True)
    parser.add_argument("--metrics-key-id", required=True)
    parser.add_argument("--metrics-public-key-sha256", required=True)
    parser.add_argument("--source-revision", required=True)
    parser.add_argument("--channel", choices=("stable", "beta"), required=True)
    parser.add_argument("--qt-version", required=True)
    parser.add_argument("--authenticode-signer-sha256", required=True)
    parser.add_argument("--update-public-key-sha256", required=True)


def authorization_values(args: argparse.Namespace) -> tuple[object, ...]:
    return (
        args.completion, args.execution, args.promotion_authorization,
        args.current_candidate_root, args.rollback_release_root,
        args.pre_promotion_manifest, args.promotion_observation,
        args.health_decision, args.metrics, args.metrics_signature,
        args.metrics_public_key, args.metrics_key_id,
        args.metrics_public_key_sha256, args.health_policy,
        args.target_candidate_root, args.version_file, args.source_revision,
        args.channel, args.qt_version, args.authenticode_signer_sha256,
        args.update_public_key_sha256,
    )


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("command", choices=("create", "verify"))
    add_authorization_arguments(parser)
    parser.add_argument("--lifetime-seconds", type=int, default=900)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    values = authorization_values(args)
    try:
        if args.command == "create":
            value = create_authorization(
                *values, datetime.now(timezone.utc).replace(microsecond=0),
                args.lifetime_seconds)
            write_once(args.output.resolve(strict=False), value)
        else:
            value = verify_authorization(
                args.output, *values,
                now_utc=datetime.now(timezone.utc).replace(microsecond=0))
    except (ManifestError, OSError) as error:
        raise SystemExit(f"Windows rollout expansion authorization failed: {error}") from None
    print(json.dumps(value, ensure_ascii=True, sort_keys=True))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
