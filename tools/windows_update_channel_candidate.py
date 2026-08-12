#!/usr/bin/env python3
"""Assemble and verify an unpublished signed Windows update-channel candidate."""

from __future__ import annotations

import argparse
import hashlib
import json
import os
import re
import shutil
import tempfile
from datetime import datetime, timedelta, timezone
from pathlib import Path, PurePosixPath

from artifact_manifest_common import ManifestError, atomic_write, payload_files, sha256_file
from windows_release_candidate import validate_candidate as validate_windows_candidate
from windows_update_manifest import verify_manifest_signature


MANIFEST_NAME = "windows-update-channel-candidate.json"
CHECKSUMS_NAME = "SHA256SUMS"
STATUS = "signed-update-channel-not-published-candidate"
HEX64 = re.compile(r"^[0-9a-f]{64}$")
ROOT_KEYS = {
    "schemaVersion", "product", "status", "channel", "version",
    "sourceRevision", "manifestSequence", "signingKeyId", "installerUrl",
    "expectedAuthenticodeSignerSha256", "updatePublicKeyFileSha256",
    "windowsCandidateManifestSha256", "assembledAt", "files",
}
FILE_KEYS = {"path", "sha256", "size"}


def _safe(value: object) -> str:
    if not isinstance(value, str) or not value or "\\" in value:
        raise ManifestError("Windows update channel candidate path is unsafe")
    path = PurePosixPath(value)
    if (path.is_absolute() or path.as_posix() != value
            or any(part in {"", ".", ".."} for part in path.parts)):
        raise ManifestError("Windows update channel candidate path is unsafe")
    return value


def _time(value: object) -> datetime:
    if not isinstance(value, str) or not value.endswith("Z"):
        raise ManifestError("Windows update channel candidate assembly time is invalid")
    try:
        parsed = datetime.fromisoformat(value[:-1] + "+00:00")
    except ValueError as error:
        raise ManifestError("Windows update channel candidate assembly time is invalid") from error
    if parsed.tzinfo != timezone.utc or parsed.microsecond:
        raise ManifestError("Windows update channel candidate assembly time is invalid")
    return parsed


def _read(path: Path) -> dict[str, object]:
    if path.is_symlink() or not path.is_file():
        raise ManifestError("Windows update channel candidate manifest must be a regular file")

    def unique(pairs: list[tuple[str, object]]) -> dict[str, object]:
        result: dict[str, object] = {}
        for key, value in pairs:
            if key in result:
                raise ManifestError("Windows update channel candidate has duplicate keys")
            result[key] = value
        return result

    try:
        value = json.loads(path.read_text(encoding="utf-8"), object_pairs_hook=unique)
    except (OSError, UnicodeDecodeError, json.JSONDecodeError) as error:
        raise ManifestError("Windows update channel candidate manifest is unreadable") from error
    if not isinstance(value, dict) or set(value) != ROOT_KEYS:
        raise ManifestError("Windows update channel candidate manifest has an unsupported shape")
    return value


def _checksums(path: Path) -> dict[str, str]:
    try:
        lines = path.read_text(encoding="utf-8").splitlines()
    except OSError as error:
        raise ManifestError("Windows update channel candidate checksums are unreadable") from error
    result: dict[str, str] = {}
    for line in lines:
        parts = line.split("  ", 1)
        if len(parts) != 2 or not HEX64.fullmatch(parts[0]):
            raise ManifestError("Windows update channel candidate checksums are malformed")
        relative = _safe(parts[1])
        if relative in result:
            raise ManifestError("Windows update channel candidate checksums are malformed")
        result[relative] = parts[0]
    return result


def validate_candidate(
    root: Path,
    version_file: Path,
    source_revision: str,
    channel: str,
    qt_version: str,
    authenticode_signer_sha256: str,
    public_key_file_sha256: str,
    now_utc: datetime,
) -> dict[str, object]:
    if root.is_symlink() or not root.is_dir():
        raise ManifestError("Windows update channel candidate root is unsafe")
    manifest = _read(root / MANIFEST_NAME)
    assembled = _time(manifest["assembledAt"])
    if now_utc.tzinfo is None or now_utc.utcoffset() != timedelta(0):
        raise ManifestError("Windows update channel candidate verifier requires UTC")
    if assembled > now_utc.replace(microsecond=0):
        raise ManifestError("Windows update channel candidate assembly time is from the future")
    if (manifest["schemaVersion"] != 1
            or manifest["product"] != "chat-room-windows-update"
            or manifest["status"] != STATUS
            or manifest["channel"] != channel
            or manifest["sourceRevision"] != source_revision
            or manifest["expectedAuthenticodeSignerSha256"] != authenticode_signer_sha256
            or manifest["updatePublicKeyFileSha256"] != public_key_file_sha256):
        raise ManifestError("Windows update channel candidate identity is invalid")
    if not HEX64.fullmatch(public_key_file_sha256):
        raise ManifestError("Windows update public key file SHA-256 is invalid")

    entries = manifest["files"]
    if not isinstance(entries, list) or not entries:
        raise ManifestError("Windows update channel candidate file list is empty")
    declared: dict[str, tuple[str, int]] = {}
    order: list[str] = []
    for entry in entries:
        if (not isinstance(entry, dict) or set(entry) != FILE_KEYS
                or not isinstance(entry.get("sha256"), str)
                or not HEX64.fullmatch(entry["sha256"])
                or type(entry.get("size")) is not int or entry["size"] <= 0):
            raise ManifestError("Windows update channel candidate file entry is invalid")
        relative = _safe(entry.get("path"))
        if relative in declared:
            raise ManifestError("Windows update channel candidate file entry is duplicated")
        declared[relative] = (entry["sha256"], entry["size"])
        order.append(relative)
    if order != sorted(order):
        raise ManifestError("Windows update channel candidate files are not sorted")
    actual = {path.relative_to(root).as_posix() for path in payload_files(root)}
    if actual != set(declared) | {MANIFEST_NAME, CHECKSUMS_NAME}:
        raise ManifestError("Windows update channel candidate has undeclared or missing files")
    checksums = _checksums(root / CHECKSUMS_NAME)
    if set(checksums) != set(declared):
        raise ManifestError("Windows update channel candidate checksum closure is invalid")
    for relative, (expected_digest, expected_size) in declared.items():
        digest, size = sha256_file(root / relative)
        if digest != expected_digest or size != expected_size or checksums[relative] != digest:
            raise ManifestError("Windows update channel candidate final bytes changed")

    windows_root = root / "windows"
    windows_identity = validate_windows_candidate(
        windows_root, version_file, source_revision, channel, qt_version,
        authenticode_signer_sha256, assembled)
    windows_manifest_digest, _ = sha256_file(
        windows_root / "windows-release-candidate.json")
    if manifest["windowsCandidateManifestSha256"] != windows_manifest_digest:
        raise ManifestError("Windows update channel candidate Windows identity changed")
    public_key = root / "evidence/update-public-key.pem"
    public_digest, _ = sha256_file(public_key)
    if public_digest != public_key_file_sha256:
        raise ManifestError("Windows update channel candidate public key changed")
    update_manifest = verify_manifest_signature(
        root / "update/manifest.json", root / "update/manifest.json.sig",
        public_key, assembled)
    installer = windows_root / f"installer/ChatRoom-{windows_identity['version']}-Setup.exe"
    installer_digest, installer_size = sha256_file(installer)
    metadata = update_manifest["installer"]
    if (update_manifest["channel"] != channel
            or update_manifest["version"] != windows_identity["version"]
            or manifest["version"] != windows_identity["version"]
            or update_manifest["sourceRevision"] != source_revision
            or update_manifest["signingKeyId"] != manifest["signingKeyId"]
            or update_manifest["manifestSequence"] != manifest["manifestSequence"]
            or metadata["url"] != manifest["installerUrl"]
            or metadata["sha256"] != installer_digest
            or metadata["size"] != installer_size
            or metadata["authenticodeSha256Thumbprint"] != authenticode_signer_sha256):
        raise ManifestError("Windows update manifest does not match signed candidate")
    return {
        "status": STATUS,
        "channel": channel,
        "version": windows_identity["version"],
        "sourceRevision": source_revision,
        "manifestSequence": update_manifest["manifestSequence"],
        "fileCount": len(declared),
    }


def assemble_candidate(
    windows_candidate_root: Path,
    update_manifest_path: Path,
    signature_path: Path,
    public_key_path: Path,
    output_root: Path,
    version_file: Path,
    source_revision: str,
    channel: str,
    qt_version: str,
    authenticode_signer_sha256: str,
    public_key_file_sha256: str,
    now_utc: datetime,
) -> dict[str, object]:
    if now_utc.tzinfo is None or now_utc.utcoffset() != timedelta(0):
        raise ManifestError("Windows update channel candidate assembler requires UTC")
    windows_identity = validate_windows_candidate(
        windows_candidate_root, version_file, source_revision, channel,
        qt_version, authenticode_signer_sha256, now_utc)
    update = verify_manifest_signature(
        update_manifest_path, signature_path, public_key_path, now_utc)
    public_digest, _ = sha256_file(public_key_path)
    if public_digest != public_key_file_sha256:
        raise ManifestError("Windows update public key does not match reviewed SHA-256")
    if output_root.exists() or output_root.is_symlink():
        raise ManifestError("Windows update channel candidate destination already exists")
    output_root.parent.mkdir(parents=True, exist_ok=True)
    if output_root.parent.is_symlink() or not output_root.parent.is_dir():
        raise ManifestError("Windows update channel candidate destination is unsafe")
    temporary = Path(tempfile.mkdtemp(prefix=".windows-update-candidate-", dir=output_root.parent))
    try:
        shutil.copytree(windows_candidate_root, temporary / "windows")
        (temporary / "update").mkdir()
        shutil.copyfile(update_manifest_path, temporary / "update/manifest.json")
        shutil.copyfile(signature_path, temporary / "update/manifest.json.sig")
        (temporary / "evidence").mkdir()
        shutil.copyfile(public_key_path, temporary / "evidence/update-public-key.pem")
        files = payload_files(temporary)
        entries: list[dict[str, object]] = []
        sums: list[str] = []
        for path in sorted(files, key=lambda item: item.relative_to(temporary).as_posix()):
            relative = path.relative_to(temporary).as_posix()
            digest, size = sha256_file(path)
            entries.append({"path": relative, "sha256": digest, "size": size})
            sums.append(f"{digest}  {relative}")
        windows_digest, _ = sha256_file(
            temporary / "windows/windows-release-candidate.json")
        manifest = {
            "schemaVersion": 1,
            "product": "chat-room-windows-update",
            "status": STATUS,
            "channel": channel,
            "version": windows_identity["version"],
            "sourceRevision": source_revision,
            "manifestSequence": update["manifestSequence"],
            "signingKeyId": update["signingKeyId"],
            "installerUrl": update["installer"]["url"],
            "expectedAuthenticodeSignerSha256": authenticode_signer_sha256,
            "updatePublicKeyFileSha256": public_key_file_sha256,
            "windowsCandidateManifestSha256": windows_digest,
            "assembledAt": now_utc.astimezone(timezone.utc).replace(
                microsecond=0).strftime("%Y-%m-%dT%H:%M:%SZ"),
            "files": entries,
        }
        atomic_write(temporary / MANIFEST_NAME,
                     json.dumps(manifest, ensure_ascii=True, indent=2, sort_keys=True) + "\n")
        atomic_write(temporary / CHECKSUMS_NAME, "\n".join(sums) + "\n")
        result = validate_candidate(
            temporary, version_file, source_revision, channel, qt_version,
            authenticode_signer_sha256, public_key_file_sha256, now_utc)
        os.rename(temporary, output_root)
        return {**result, "assemblyStatus": "assembled"}
    except Exception:
        shutil.rmtree(temporary, ignore_errors=True)
        raise


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    sub = parser.add_subparsers(dest="command", required=True)
    assemble = sub.add_parser("assemble")
    assemble.add_argument("--windows-candidate-root", type=Path, required=True)
    assemble.add_argument("--update-manifest", type=Path, required=True)
    assemble.add_argument("--signature", type=Path, required=True)
    assemble.add_argument("--public-key", type=Path, required=True)
    assemble.add_argument("--output-root", type=Path, required=True)
    verify = sub.add_parser("verify")
    verify.add_argument("--candidate-root", type=Path, required=True)
    for target in (assemble, verify):
        target.add_argument("--version-file", type=Path, required=True)
        target.add_argument("--source-revision", required=True)
        target.add_argument("--channel", choices=("stable", "beta"), required=True)
        target.add_argument("--qt-version", required=True)
        target.add_argument("--authenticode-signer-sha256", required=True)
        target.add_argument("--public-key-file-sha256", required=True)
    return parser.parse_args()


def main() -> int:
    args = parse_args()
    now = datetime.now(timezone.utc).replace(microsecond=0)
    try:
        if args.command == "assemble":
            result = assemble_candidate(
                args.windows_candidate_root, args.update_manifest, args.signature,
                args.public_key, args.output_root, args.version_file,
                args.source_revision, args.channel, args.qt_version,
                args.authenticode_signer_sha256, args.public_key_file_sha256, now)
        else:
            result = validate_candidate(
                args.candidate_root, args.version_file, args.source_revision,
                args.channel, args.qt_version, args.authenticode_signer_sha256,
                args.public_key_file_sha256, now)
    except (ManifestError, OSError) as error:
        raise SystemExit(f"Windows update channel candidate failed: {error}") from None
    print(json.dumps(result, ensure_ascii=True, sort_keys=True))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
