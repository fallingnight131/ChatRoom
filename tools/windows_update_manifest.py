#!/usr/bin/env python3
"""Create, sign, and verify canonical Ed25519 Windows update manifests."""

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

from artifact_manifest_common import ManifestError, atomic_write, sha256_file, validate_revision


SEMVER = re.compile(r"^(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)$")
KEY_ID = re.compile(r"^[a-z0-9][a-z0-9.-]{0,63}$")
HEX_32 = re.compile(r"^[0-9a-f]{64}$")
UTC_TIMESTAMP = re.compile(r"^[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}Z$")
CHANNELS = {"stable", "beta"}
MAX_VALIDITY = timedelta(days=31)
REPOSITORY_ROOT = Path(__file__).resolve().parents[1]


def canonical_bytes(manifest: dict[str, object]) -> bytes:
    return (json.dumps(
        manifest,
        ensure_ascii=True,
        allow_nan=False,
        separators=(",", ":"),
        sort_keys=True,
    ) + "\n").encode("utf-8")


def _parse_version(value: object, label: str) -> tuple[int, int, int]:
    if not isinstance(value, str) or not SEMVER.fullmatch(value):
        raise ManifestError(f"{label} must be canonical numeric SemVer")
    parts = tuple(int(part) for part in value.split("."))
    if any(part > 65535 for part in parts):
        raise ManifestError(f"{label} exceeds Windows version component bounds")
    return parts  # type: ignore[return-value]


def _parse_timestamp(value: object, label: str) -> datetime:
    if not isinstance(value, str) or not UTC_TIMESTAMP.fullmatch(value):
        raise ManifestError(f"{label} must be a whole-second UTC timestamp")
    try:
        parsed = datetime.strptime(value, "%Y-%m-%dT%H:%M:%SZ").replace(tzinfo=timezone.utc)
    except ValueError as error:
        raise ManifestError(f"{label} is invalid") from error
    return parsed


def validate_manifest(
    manifest: dict[str, object],
    observed_at: datetime | None = None,
) -> dict[str, object]:
    expected_keys = {
        "schemaVersion", "product", "architecture", "channel", "manifestSequence", "signingKeyId",
        "publishedAt", "expiresAt", "version", "minimumUpdatableVersion",
        "sourceRevision", "rollout", "installer",
    }
    if set(manifest) != expected_keys or manifest.get("schemaVersion") != 1:
        raise ManifestError("Windows update manifest has an unsupported shape")
    if manifest.get("product") != "chat-room-windows-client":
        raise ManifestError("Windows update manifest product is invalid")
    if manifest.get("architecture") != "x86_64":
        raise ManifestError("Windows update manifest architecture is invalid")
    if manifest.get("channel") not in CHANNELS:
        raise ManifestError("Windows update channel is invalid")
    sequence = manifest.get("manifestSequence")
    if not isinstance(sequence, int) or isinstance(sequence, bool) or not 1 <= sequence <= 2**53 - 1:
        raise ManifestError("Windows update manifest sequence is invalid")
    key_id = manifest.get("signingKeyId")
    if not isinstance(key_id, str) or not KEY_ID.fullmatch(key_id):
        raise ManifestError("Windows update signing key ID is invalid")

    target = _parse_version(manifest.get("version"), "version")
    minimum = _parse_version(manifest.get("minimumUpdatableVersion"), "minimumUpdatableVersion")
    if minimum > target:
        raise ManifestError("minimum updatable version exceeds target version")
    revision = manifest.get("sourceRevision")
    if not isinstance(revision, str):
        raise ManifestError("Windows update source revision is missing")
    validate_revision(revision)

    published = _parse_timestamp(manifest.get("publishedAt"), "publishedAt")
    expires = _parse_timestamp(manifest.get("expiresAt"), "expiresAt")
    if expires <= published or expires - published > MAX_VALIDITY:
        raise ManifestError("Windows update manifest validity window is invalid")
    if observed_at is not None:
        if observed_at.tzinfo is None:
            raise ManifestError("Update observation time must include a timezone")
        now = observed_at.astimezone(timezone.utc)
        if now < published or now >= expires:
            raise ManifestError("Windows update manifest is not currently valid")

    rollout = manifest.get("rollout")
    if not isinstance(rollout, dict) or set(rollout) != {"percentage", "seed"}:
        raise ManifestError("Windows update rollout policy is malformed")
    percentage, seed = rollout.get("percentage"), rollout.get("seed")
    if (not isinstance(percentage, int) or isinstance(percentage, bool)
            or not 0 <= percentage <= 100
            or not isinstance(seed, str) or not HEX_32.fullmatch(seed)):
        raise ManifestError("Windows update rollout policy is invalid")

    installer = manifest.get("installer")
    expected_installer_keys = {
        "url", "size", "sha256", "authenticodeSha256Thumbprint",
    }
    if not isinstance(installer, dict) or set(installer) != expected_installer_keys:
        raise ManifestError("Windows update installer metadata is malformed")
    url = installer.get("url")
    if not isinstance(url, str):
        raise ManifestError("Windows update installer URL is invalid")
    parsed_url = urlsplit(url)
    expected_name = f"ChatRoom-{manifest['version']}-Setup.exe"
    try:
        parsed_url.port
    except ValueError as error:
        raise ManifestError("Windows update installer URL is invalid") from error
    path_segments = parsed_url.path.split("/")
    if (parsed_url.scheme != "https" or not parsed_url.hostname or parsed_url.username
            or parsed_url.password or parsed_url.query or parsed_url.fragment
            or not parsed_url.path.startswith("/") or "//" in parsed_url.path
            or "%" in parsed_url.path or "\\" in parsed_url.path
            or any(segment in {".", ".."} for segment in path_segments)
            or any(ord(character) < 33 or ord(character) > 126 for character in url)
            or Path(parsed_url.path).name != expected_name):
        raise ManifestError("Windows update installer URL is invalid")
    size, digest = installer.get("size"), installer.get("sha256")
    thumbprint = installer.get("authenticodeSha256Thumbprint")
    if (not isinstance(size, int) or isinstance(size, bool) or size <= 0
            or size > 2 * 1024 * 1024 * 1024
            or not isinstance(digest, str) or not HEX_32.fullmatch(digest)
            or not isinstance(thumbprint, str) or not HEX_32.fullmatch(thumbprint)):
        raise ManifestError("Windows update installer integrity metadata is invalid")
    return manifest


def build_manifest(
    installer_path: Path,
    *,
    version: str,
    channel: str,
    manifest_sequence: int,
    signing_key_id: str,
    published_at: str,
    expires_at: str,
    minimum_updatable_version: str,
    source_revision: str,
    rollout_percentage: int,
    rollout_seed: str,
    installer_url: str,
    authenticode_sha256_thumbprint: str,
) -> dict[str, object]:
    expected_name = f"ChatRoom-{version}-Setup.exe"
    if installer_path.is_symlink() or not installer_path.is_file() or installer_path.name != expected_name:
        raise ManifestError("Windows update installer path or production name is invalid")
    digest, size = sha256_file(installer_path)
    manifest: dict[str, object] = {
        "schemaVersion": 1,
        "product": "chat-room-windows-client",
        "architecture": "x86_64",
        "channel": channel,
        "manifestSequence": manifest_sequence,
        "signingKeyId": signing_key_id,
        "publishedAt": published_at,
        "expiresAt": expires_at,
        "version": version,
        "minimumUpdatableVersion": minimum_updatable_version,
        "sourceRevision": source_revision,
        "rollout": {"percentage": rollout_percentage, "seed": rollout_seed},
        "installer": {
            "url": installer_url,
            "size": size,
            "sha256": digest,
            "authenticodeSha256Thumbprint": authenticode_sha256_thumbprint,
        },
    }
    return validate_manifest(manifest)


def read_canonical_manifest(path: Path, observed_at: datetime | None = None) -> dict[str, object]:
    if not path.is_file() or path.is_symlink():
        raise ManifestError("Windows update manifest must be a regular file")
    try:
        raw = path.read_bytes()
        manifest = json.loads(raw.decode("utf-8"))
    except (UnicodeDecodeError, json.JSONDecodeError, OSError) as error:
        raise ManifestError("Windows update manifest is unreadable") from error
    if not isinstance(manifest, dict):
        raise ManifestError("Windows update manifest must be a JSON object")
    validate_manifest(manifest, observed_at)
    if raw != canonical_bytes(manifest):
        raise ManifestError("Windows update manifest is not canonical JSON")
    return manifest


def _run_openssl(arguments: list[str]) -> None:
    try:
        result = subprocess.run(
            ["openssl", *arguments],
            check=False,
            stdout=subprocess.DEVNULL,
            stderr=subprocess.DEVNULL,
        )
    except OSError as error:
        raise ManifestError("OpenSSL is unavailable for update signature operation") from error
    if result.returncode != 0:
        raise ManifestError("Windows update Ed25519 signature operation failed")


def sign_manifest(manifest_path: Path, private_key: Path, signature_path: Path) -> None:
    read_canonical_manifest(manifest_path, datetime.now(timezone.utc))
    if private_key.is_symlink() or not private_key.is_file():
        raise ManifestError("Windows update private key must be a regular external file")
    try:
        private_key.resolve().relative_to(REPOSITORY_ROOT)
    except ValueError:
        pass
    else:
        raise ManifestError("Windows update private key must remain outside the repository")
    if signature_path.resolve() in {manifest_path.resolve(), private_key.resolve()}:
        raise ManifestError("Windows update signature output path is unsafe")
    signature_path.parent.mkdir(parents=True, exist_ok=True)
    with tempfile.NamedTemporaryFile(
        dir=signature_path.parent,
        prefix=signature_path.name + ".",
        suffix=".tmp",
        delete=False,
    ) as stream:
        temporary = Path(stream.name)
    try:
        _run_openssl([
            "pkeyutl", "-sign", "-rawin", "-inkey", str(private_key),
            "-in", str(manifest_path), "-out", str(temporary),
        ])
        if temporary.stat().st_size != 64:
            raise ManifestError("Windows update Ed25519 signature has an invalid size")
        os.replace(temporary, signature_path)
    finally:
        if temporary.exists():
            temporary.unlink()


def verify_manifest_signature(
    manifest_path: Path,
    signature_path: Path,
    public_key: Path,
    observed_at: datetime | None = None,
) -> dict[str, object]:
    manifest = read_canonical_manifest(manifest_path, observed_at)
    if (signature_path.is_symlink() or not signature_path.is_file()
            or signature_path.stat().st_size != 64
            or public_key.is_symlink() or not public_key.is_file()):
        raise ManifestError("Windows update signature or public key is invalid")
    _run_openssl([
        "pkeyutl", "-verify", "-pubin", "-rawin", "-inkey", str(public_key),
        "-in", str(manifest_path), "-sigfile", str(signature_path),
    ])
    return manifest


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    subparsers = parser.add_subparsers(dest="command", required=True)
    create = subparsers.add_parser("create")
    create.add_argument("--installer", type=Path, required=True)
    create.add_argument("--version", required=True)
    create.add_argument("--channel", choices=sorted(CHANNELS), required=True)
    create.add_argument("--manifest-sequence", type=int, required=True)
    create.add_argument("--signing-key-id", required=True)
    create.add_argument("--published-at", required=True)
    create.add_argument("--expires-at", required=True)
    create.add_argument("--minimum-updatable-version", required=True)
    create.add_argument("--source-revision", required=True)
    create.add_argument("--rollout-percentage", type=int, required=True)
    create.add_argument("--rollout-seed", required=True)
    create.add_argument("--installer-url", required=True)
    create.add_argument("--authenticode-sha256-thumbprint", required=True)
    create.add_argument("--output", type=Path, required=True)
    sign = subparsers.add_parser("sign")
    sign.add_argument("--manifest", type=Path, required=True)
    sign.add_argument("--private-key", type=Path, required=True)
    sign.add_argument("--signature", type=Path, required=True)
    verify = subparsers.add_parser("verify")
    verify.add_argument("--manifest", type=Path, required=True)
    verify.add_argument("--signature", type=Path, required=True)
    verify.add_argument("--public-key", type=Path, required=True)
    verify.add_argument("--observed-at")
    return parser.parse_args()


def main() -> int:
    args = parse_args()
    try:
        if args.command == "create":
            manifest = build_manifest(
                args.installer,
                version=args.version,
                channel=args.channel,
                manifest_sequence=args.manifest_sequence,
                signing_key_id=args.signing_key_id,
                published_at=args.published_at,
                expires_at=args.expires_at,
                minimum_updatable_version=args.minimum_updatable_version,
                source_revision=args.source_revision,
                rollout_percentage=args.rollout_percentage,
                rollout_seed=args.rollout_seed,
                installer_url=args.installer_url,
                authenticode_sha256_thumbprint=args.authenticode_sha256_thumbprint,
            )
            validate_manifest(manifest, datetime.now(timezone.utc))
            atomic_write(args.output, canonical_bytes(manifest).decode("utf-8"))
            result = {"status": "created", "version": manifest["version"], "channel": manifest["channel"]}
        elif args.command == "sign":
            sign_manifest(args.manifest, args.private_key, args.signature)
            result = {"status": "signed", "signatureBytes": args.signature.stat().st_size}
        else:
            observed_at = (
                _parse_timestamp(args.observed_at, "observedAt")
                if args.observed_at else datetime.now(timezone.utc)
            )
            manifest = verify_manifest_signature(
                args.manifest, args.signature, args.public_key, observed_at,
            )
            result = {"status": "verified", "version": manifest["version"], "channel": manifest["channel"]}
    except (ManifestError, OSError) as error:
        raise SystemExit(f"Windows update manifest failed: {error}") from None
    print(json.dumps(result, ensure_ascii=True, sort_keys=True))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
