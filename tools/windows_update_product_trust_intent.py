#!/usr/bin/env python3
"""Create or verify reviewed public trust inputs for one Windows release build."""

from __future__ import annotations

import argparse
import json
import os
import re
import subprocess
import tempfile
from datetime import datetime, timedelta, timezone
from pathlib import Path
from urllib.parse import urlsplit

from artifact_manifest_common import ManifestError, read_version, sha256_file, validate_revision


STATUS = "reviewed-product-update-trust-not-built"
ENVIRONMENT = "windows-update-product-trust"
KEY_ID = re.compile(r"^[a-z0-9][a-z0-9.-]{0,63}$")
HEX64 = re.compile(r"^[0-9a-f]{64}$")
SPKI_PREFIX = bytes.fromhex("302a300506032b6570032100")
ROOT_KEYS = {
    "schemaVersion", "intentType", "status", "environment", "version",
    "sourceRevision", "channel", "manifestUrl", "primaryKey", "secondaryKey",
    "approvedAt", "expiresAt",
}
KEY_KEYS = {"keyId", "publicKeyHex", "publicKeyFileSha256"}


def _time(value: object, label: str) -> datetime:
    if not isinstance(value, str) or not value.endswith("Z"):
        raise ManifestError(f"Windows update trust intent {label} is invalid")
    try:
        parsed = datetime.fromisoformat(value[:-1] + "+00:00")
    except ValueError as error:
        raise ManifestError(f"Windows update trust intent {label} is invalid") from error
    if parsed.tzinfo != timezone.utc or parsed.microsecond:
        raise ManifestError(f"Windows update trust intent {label} is invalid")
    return parsed


def _manifest_url(value: str, channel: str) -> None:
    parsed = urlsplit(value)
    try:
        parsed.port
    except ValueError as error:
        raise ManifestError("Windows update trust manifest URL is invalid") from error
    if (parsed.scheme != "https" or not parsed.hostname or parsed.username or parsed.password
            or parsed.query or parsed.fragment
            or parsed.path != f"/windows/{channel}/manifest.json"
            or "%" in parsed.path or "\\" in parsed.path or "//" in parsed.path
            or any(ord(character) < 33 or ord(character) > 126 for character in value)):
        raise ManifestError("Windows update trust manifest URL is invalid")


def _public_key(path: Path, key_id: str) -> dict[str, str]:
    if not KEY_ID.fullmatch(key_id) or path.is_symlink() or not path.is_file():
        raise ManifestError("Windows update trust public key input is invalid")
    try:
        result = subprocess.run(
            ["openssl", "pkey", "-pubin", "-in", str(path), "-outform", "DER"],
            check=False, stdout=subprocess.PIPE, stderr=subprocess.DEVNULL,
        )
    except OSError as error:
        raise ManifestError("OpenSSL is unavailable for public-key inspection") from error
    if result.returncode != 0 or len(result.stdout) != len(SPKI_PREFIX) + 32:
        raise ManifestError("Windows update trust public key is not Ed25519")
    if not result.stdout.startswith(SPKI_PREFIX):
        raise ManifestError("Windows update trust public key is not canonical Ed25519 SPKI")
    digest, _ = sha256_file(path)
    return {
        "keyId": key_id,
        "publicKeyHex": result.stdout[len(SPKI_PREFIX):].hex(),
        "publicKeyFileSha256": digest,
    }


def create_intent(
    version_file: Path,
    source_revision: str,
    channel: str,
    manifest_url: str,
    primary_key_id: str,
    primary_public_key: Path,
    now_utc: datetime,
    lifetime_seconds: int = 7200,
    secondary_key_id: str | None = None,
    secondary_public_key: Path | None = None,
) -> dict[str, object]:
    if now_utc.tzinfo != timezone.utc or now_utc.microsecond:
        raise ManifestError("Windows update trust intent clock must be exact UTC")
    if not 300 <= lifetime_seconds <= 7200:
        raise ManifestError("Windows update trust intent lifetime must be 300 to 7200 seconds")
    validate_revision(source_revision)
    if channel not in {"stable", "beta"}:
        raise ManifestError("Windows update trust channel is invalid")
    _manifest_url(manifest_url, channel)
    primary = _public_key(primary_public_key, primary_key_id)
    if (secondary_key_id is None) != (secondary_public_key is None):
        raise ManifestError("Windows update secondary trust key is incomplete")
    secondary = (
        _public_key(secondary_public_key, secondary_key_id)
        if secondary_key_id is not None and secondary_public_key is not None else None)
    if secondary is not None and (
            secondary["keyId"] == primary["keyId"]
            or secondary["publicKeyHex"] == primary["publicKeyHex"]):
        raise ManifestError("Windows update trust keys must be distinct")
    return {
        "schemaVersion": 1,
        "intentType": "windows-update-product-trust-build",
        "status": STATUS,
        "environment": ENVIRONMENT,
        "version": read_version(version_file),
        "sourceRevision": source_revision,
        "channel": channel,
        "manifestUrl": manifest_url,
        "primaryKey": primary,
        "secondaryKey": secondary,
        "approvedAt": now_utc.strftime("%Y-%m-%dT%H:%M:%SZ"),
        "expiresAt": (now_utc + timedelta(seconds=lifetime_seconds)).strftime(
            "%Y-%m-%dT%H:%M:%SZ"),
    }


def write_once(path: Path, value: dict[str, object]) -> None:
    if path.exists() or path.is_symlink() or not path.is_absolute():
        raise ManifestError("Windows update trust intent output is unsafe or already exists")
    path.parent.mkdir(parents=True, exist_ok=True)
    if path.parent.is_symlink() or not path.parent.is_dir():
        raise ManifestError("Windows update trust intent output directory is unsafe")
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
            raise ManifestError("Windows update trust intent output already exists") from error
    finally:
        if temporary is not None:
            temporary.unlink(missing_ok=True)


def verify_intent(
    intent_path: Path,
    version_file: Path,
    source_revision: str,
    channel: str,
    manifest_url: str,
    primary_key_id: str,
    primary_public_key: Path,
    now_utc: datetime,
    secondary_key_id: str | None = None,
    secondary_public_key: Path | None = None,
) -> dict[str, object]:
    if intent_path.is_symlink() or not intent_path.is_file():
        raise ManifestError("Windows update trust intent must be a regular file")

    def unique(pairs):
        result = {}
        for key, value in pairs:
            if key in result:
                raise ManifestError("Windows update trust intent has duplicate keys")
            result[key] = value
        return result

    try:
        recorded = json.loads(intent_path.read_text(encoding="utf-8"), object_pairs_hook=unique)
    except (OSError, UnicodeDecodeError, json.JSONDecodeError) as error:
        raise ManifestError("Windows update trust intent is unreadable") from error
    if (not isinstance(recorded, dict) or set(recorded) != ROOT_KEYS
            or not isinstance(recorded.get("primaryKey"), dict)
            or set(recorded["primaryKey"]) != KEY_KEYS
            or (recorded.get("secondaryKey") is not None
                and (not isinstance(recorded["secondaryKey"], dict)
                     or set(recorded["secondaryKey"]) != KEY_KEYS))):
        raise ManifestError("Windows update trust intent has an unsupported shape")
    approved = _time(recorded.get("approvedAt"), "approval time")
    expires = _time(recorded.get("expiresAt"), "expiry time")
    lifetime = expires - approved
    if lifetime < timedelta(seconds=300) or lifetime > timedelta(seconds=7200):
        raise ManifestError("Windows update trust intent lifetime is invalid")
    if now_utc.tzinfo != timezone.utc or now_utc.microsecond:
        raise ManifestError("Windows update trust intent clock must be exact UTC")
    if approved > now_utc + timedelta(minutes=1) or now_utc >= expires:
        raise ManifestError("Windows update trust intent is expired or from the future")
    expected = create_intent(
        version_file, source_revision, channel, manifest_url, primary_key_id,
        primary_public_key, approved, int(lifetime.total_seconds()),
        secondary_key_id, secondary_public_key,
    )
    if recorded != expected:
        raise ManifestError("Windows update trust intent does not match reviewed inputs")
    return recorded


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("command", choices=("create", "verify"))
    parser.add_argument("--intent", type=Path, required=True)
    parser.add_argument("--version-file", type=Path, required=True)
    parser.add_argument("--source-revision", required=True)
    parser.add_argument("--channel", choices=("stable", "beta"), required=True)
    parser.add_argument("--manifest-url", required=True)
    parser.add_argument("--primary-key-id", required=True)
    parser.add_argument("--primary-public-key", type=Path, required=True)
    parser.add_argument("--secondary-key-id")
    parser.add_argument("--secondary-public-key", type=Path)
    parser.add_argument("--lifetime-seconds", type=int, default=7200)
    args = parser.parse_args()
    now = datetime.now(timezone.utc).replace(microsecond=0)
    values = (
        args.version_file, args.source_revision, args.channel, args.manifest_url,
        args.primary_key_id, args.primary_public_key,
    )
    try:
        if args.command == "create":
            result = create_intent(
                *values, now, args.lifetime_seconds, args.secondary_key_id,
                args.secondary_public_key)
            write_once(args.intent, result)
        else:
            result = verify_intent(
                args.intent, *values, now, args.secondary_key_id,
                args.secondary_public_key)
    except (ManifestError, OSError) as error:
        raise SystemExit(f"Windows update trust intent failed: {error}") from None
    print(json.dumps(result, ensure_ascii=True, sort_keys=True))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
