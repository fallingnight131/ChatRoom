#!/usr/bin/env python3
"""Create or verify a short-lived Windows update-channel authorization."""

from __future__ import annotations

import argparse
import json
import os
import tempfile
from datetime import datetime, timedelta, timezone
from pathlib import Path

from artifact_manifest_common import ManifestError, sha256_file
from windows_update_channel_candidate import validate_candidate
from windows_update_manifest import read_canonical_manifest, verify_manifest_signature


ENVIRONMENT = "windows-update-production"
STATUS = "update-channel-promotion-approved-not-executed"
MAX_CANDIDATE_AGE = timedelta(hours=24)
KEYS = {
    "schemaVersion", "authorizationType", "status", "environment", "channel",
    "version", "sourceRevision", "manifestSequence", "signingKeyId",
    "installerUrl", "candidateManifestSha256", "updateManifestSha256",
    "updateSignatureSha256", "publicKeyFileSha256",
    "expectedAuthenticodeSignerSha256", "expectedCurrentManifestSequence",
    "expectedCurrentManifestSha256", "approvedAt", "expiresAt",
}


def _time(value: object, label: str) -> datetime:
    if not isinstance(value, str) or not value.endswith("Z"):
        raise ManifestError(f"Windows update authorization {label} is invalid")
    try:
        parsed = datetime.fromisoformat(value[:-1] + "+00:00")
    except ValueError as error:
        raise ManifestError(f"Windows update authorization {label} is invalid") from error
    if parsed.tzinfo != timezone.utc or parsed.microsecond:
        raise ManifestError(f"Windows update authorization {label} is invalid")
    return parsed


def _read_json(path: Path, label: str) -> dict[str, object]:
    if path.is_symlink() or not path.is_file():
        raise ManifestError(f"Windows update authorization {label} must be a regular file")

    def unique(pairs: list[tuple[str, object]]) -> dict[str, object]:
        result: dict[str, object] = {}
        for key, value in pairs:
            if key in result:
                raise ManifestError(f"Windows update authorization {label} has duplicate keys")
            result[key] = value
        return result

    try:
        value = json.loads(path.read_text(encoding="utf-8"), object_pairs_hook=unique)
    except (OSError, UnicodeDecodeError, json.JSONDecodeError) as error:
        raise ManifestError(f"Windows update authorization {label} is unreadable") from error
    if not isinstance(value, dict):
        raise ManifestError(f"Windows update authorization {label} must be an object")
    return value


def _candidate_identity(
    candidate_root: Path,
    version_file: Path,
    source_revision: str,
    channel: str,
    qt_version: str,
    authenticode_signer_sha256: str,
    public_key_file_sha256: str,
    now_utc: datetime,
) -> dict[str, object]:
    identity = validate_candidate(
        candidate_root, version_file, source_revision, channel, qt_version,
        authenticode_signer_sha256, public_key_file_sha256, now_utc,
    )
    outer_path = candidate_root / "windows-update-channel-candidate.json"
    outer = _read_json(outer_path, "candidate manifest")
    assembled = _time(outer.get("assembledAt"), "candidate assembly time")
    if assembled > now_utc or now_utc - assembled > MAX_CANDIDATE_AGE:
        raise ManifestError("Windows update candidate is stale or from the future")
    update_path = candidate_root / "update/manifest.json"
    signature_path = candidate_root / "update/manifest.json.sig"
    public_key_path = candidate_root / "evidence/update-public-key.pem"
    update = verify_manifest_signature(update_path, signature_path, public_key_path, now_utc)
    candidate_digest, _ = sha256_file(outer_path)
    update_digest, _ = sha256_file(update_path)
    signature_digest, _ = sha256_file(signature_path)
    return {
        **identity,
        "signingKeyId": update["signingKeyId"],
        "installerUrl": update["installer"]["url"],
        "candidateManifestSha256": candidate_digest,
        "updateManifestSha256": update_digest,
        "updateSignatureSha256": signature_digest,
    }


def create_authorization(
    candidate_root: Path,
    current_manifest_path: Path,
    version_file: Path,
    source_revision: str,
    channel: str,
    qt_version: str,
    authenticode_signer_sha256: str,
    public_key_file_sha256: str,
    now_utc: datetime,
    lifetime_seconds: int = 900,
) -> dict[str, object]:
    if now_utc.tzinfo != timezone.utc or now_utc.microsecond:
        raise ManifestError("Windows update authorization clock must be an exact UTC second")
    if not 60 <= lifetime_seconds <= 900:
        raise ManifestError("Windows update authorization lifetime must be 60 to 900 seconds")
    candidate = _candidate_identity(
        candidate_root, version_file, source_revision, channel, qt_version,
        authenticode_signer_sha256, public_key_file_sha256, now_utc,
    )
    current = read_canonical_manifest(current_manifest_path, now_utc)
    current_digest, _ = sha256_file(current_manifest_path)
    if current["channel"] != channel:
        raise ManifestError("Windows update current manifest channel is invalid")
    if current["manifestSequence"] >= candidate["manifestSequence"]:
        raise ManifestError("Windows update manifest sequence does not advance the channel")
    return {
        "schemaVersion": 1,
        "authorizationType": "windows-update-channel-promotion",
        "status": STATUS,
        "environment": ENVIRONMENT,
        "channel": channel,
        "version": candidate["version"],
        "sourceRevision": source_revision,
        "manifestSequence": candidate["manifestSequence"],
        "signingKeyId": candidate["signingKeyId"],
        "installerUrl": candidate["installerUrl"],
        "candidateManifestSha256": candidate["candidateManifestSha256"],
        "updateManifestSha256": candidate["updateManifestSha256"],
        "updateSignatureSha256": candidate["updateSignatureSha256"],
        "publicKeyFileSha256": public_key_file_sha256,
        "expectedAuthenticodeSignerSha256": authenticode_signer_sha256,
        "expectedCurrentManifestSequence": current["manifestSequence"],
        "expectedCurrentManifestSha256": current_digest,
        "approvedAt": now_utc.strftime("%Y-%m-%dT%H:%M:%SZ"),
        "expiresAt": (now_utc + timedelta(seconds=lifetime_seconds)).strftime(
            "%Y-%m-%dT%H:%M:%SZ"),
    }


def write_once(path: Path, value: dict[str, object]) -> None:
    if path.exists() or path.is_symlink() or not path.is_absolute():
        raise ManifestError("Windows update authorization output is unsafe or already exists")
    path.parent.mkdir(parents=True, exist_ok=True)
    if path.parent.is_symlink() or not path.parent.is_dir():
        raise ManifestError("Windows update authorization output directory is unsafe")
    rendered = json.dumps(value, ensure_ascii=True, indent=2, sort_keys=True) + "\n"
    temporary: Path | None = None
    try:
        with tempfile.NamedTemporaryFile(
            "w", encoding="utf-8", newline="\n", dir=path.parent, delete=False,
        ) as stream:
            stream.write(rendered)
            stream.flush()
            os.fsync(stream.fileno())
            temporary = Path(stream.name)
        try:
            os.link(temporary, path)
        except FileExistsError as error:
            raise ManifestError("Windows update authorization output already exists") from error
    finally:
        if temporary is not None:
            temporary.unlink(missing_ok=True)


def verify_authorization(
    authorization_path: Path,
    candidate_root: Path,
    current_manifest_path: Path,
    version_file: Path,
    source_revision: str,
    channel: str,
    qt_version: str,
    authenticode_signer_sha256: str,
    public_key_file_sha256: str,
    now_utc: datetime,
) -> dict[str, object]:
    recorded = _read_json(authorization_path, "record")
    if set(recorded) != KEYS:
        raise ManifestError("Windows update authorization has an unsupported shape")
    approved = _time(recorded.get("approvedAt"), "approval time")
    expires = _time(recorded.get("expiresAt"), "expiry time")
    if now_utc.tzinfo != timezone.utc or now_utc.microsecond:
        raise ManifestError("Windows update authorization clock must be an exact UTC second")
    lifetime = expires - approved
    if lifetime < timedelta(seconds=60) or lifetime > timedelta(seconds=900):
        raise ManifestError("Windows update authorization lifetime is invalid")
    if approved > now_utc + timedelta(minutes=1) or now_utc >= expires:
        raise ManifestError("Windows update authorization is expired or from the future")
    expected = create_authorization(
        candidate_root, current_manifest_path, version_file, source_revision,
        channel, qt_version, authenticode_signer_sha256,
        public_key_file_sha256, approved, int(lifetime.total_seconds()),
    )
    if recorded != expected:
        raise ManifestError("Windows update authorization does not match its candidate")
    return recorded


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("command", choices=("create", "verify"))
    parser.add_argument("--candidate-root", type=Path, required=True)
    parser.add_argument("--current-manifest", type=Path, required=True)
    parser.add_argument("--version-file", type=Path, required=True)
    parser.add_argument("--source-revision", required=True)
    parser.add_argument("--channel", choices=("stable", "beta"), required=True)
    parser.add_argument("--qt-version", required=True)
    parser.add_argument("--authenticode-signer-sha256", required=True)
    parser.add_argument("--public-key-file-sha256", required=True)
    parser.add_argument("--lifetime-seconds", type=int, default=900)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    now = datetime.now(timezone.utc).replace(microsecond=0)
    try:
        if args.command == "create":
            value = create_authorization(
                args.candidate_root, args.current_manifest, args.version_file,
                args.source_revision, args.channel, args.qt_version,
                args.authenticode_signer_sha256, args.public_key_file_sha256,
                now, args.lifetime_seconds,
            )
            write_once(args.output.resolve(strict=False), value)
        else:
            value = verify_authorization(
                args.output, args.candidate_root, args.current_manifest,
                args.version_file, args.source_revision, args.channel, args.qt_version,
                args.authenticode_signer_sha256, args.public_key_file_sha256, now,
            )
    except (ManifestError, OSError) as error:
        raise SystemExit(f"Windows update authorization failed: {error}") from None
    print(json.dumps(value, ensure_ascii=True, sort_keys=True))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
