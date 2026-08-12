#!/usr/bin/env python3
"""Create and verify the closed intent approved for protected Windows signing."""

from __future__ import annotations

import argparse
from datetime import datetime, timedelta, timezone
import json
import os
from pathlib import Path
import re
import tempfile
from urllib.parse import urlsplit

from artifact_manifest_common import ManifestError, read_version, validate_revision


STATUS = "protected-signing-approved-not-published"
ENVIRONMENT = "windows-production-signing"
RUNNER_CLASS = "self-hosted-windows-signing"
KEYS = {
    "schemaVersion", "product", "status", "version", "sourceRevision",
    "channel", "buildSystem", "unsignedArtifactRunId", "unsignedArtifactName",
    "expectedSignerCertificateSha1", "expectedSignerCertificateSha256",
    "timestampUrl", "environment", "runnerClass", "recordedAt",
}
HEX40 = re.compile(r"^[0-9a-f]{40}$")
HEX64 = re.compile(r"^[0-9a-f]{64}$")
RUN_ID = re.compile(r"^[1-9][0-9]{0,19}$")


def timestamp_url(value: str) -> bool:
    try:
        parsed = urlsplit(value)
    except ValueError:
        return False
    return (parsed.scheme == "https" and bool(parsed.hostname)
            and not parsed.username and not parsed.password
            and not parsed.query and not parsed.fragment
            and parsed.path not in {"", "/"}
            and parsed.geturl() == value)


def exact_utc(value: object) -> datetime:
    if not isinstance(value, str) or not value.endswith("Z"):
        raise ManifestError("Windows protected signing intent time is invalid")
    try:
        parsed = datetime.fromisoformat(value[:-1] + "+00:00")
    except ValueError as error:
        raise ManifestError("Windows protected signing intent time is invalid") from error
    if parsed.microsecond or parsed.tzinfo != timezone.utc:
        raise ManifestError("Windows protected signing intent time is invalid")
    return parsed


def validate_values(version: str, source_revision: str, channel: str,
                    run_id: str, signer_sha1: str, signer_sha256: str,
                    timestamp: str) -> None:
    validate_revision(source_revision)
    if (not re.fullmatch(r"(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)", version)
            or channel not in {"stable", "beta"} or not RUN_ID.fullmatch(run_id)
            or not HEX40.fullmatch(signer_sha1)
            or not HEX64.fullmatch(signer_sha256)
            or not timestamp_url(timestamp)):
        raise ManifestError("Windows protected signing intent input is invalid")


def create(version_file: Path, source_revision: str, channel: str,
           run_id: str, signer_sha1: str, signer_sha256: str,
           timestamp: str, now_utc: datetime) -> dict[str, object]:
    version = read_version(version_file)
    validate_values(version, source_revision, channel, run_id,
                    signer_sha1, signer_sha256, timestamp)
    if now_utc.tzinfo != timezone.utc or now_utc.microsecond:
        raise ManifestError("Windows protected signing intent clock is invalid")
    return {
        "schemaVersion": 1,
        "product": "chat-room-windows-client",
        "status": STATUS,
        "version": version,
        "sourceRevision": source_revision,
        "channel": channel,
        "buildSystem": "cmake",
        "unsignedArtifactRunId": run_id,
        "unsignedArtifactName": (
            f"windows-{channel}-{version}-unsigned-product-trust-{source_revision}"),
        "expectedSignerCertificateSha1": signer_sha1,
        "expectedSignerCertificateSha256": signer_sha256,
        "timestampUrl": timestamp,
        "environment": ENVIRONMENT,
        "runnerClass": RUNNER_CLASS,
        "recordedAt": now_utc.strftime("%Y-%m-%dT%H:%M:%SZ"),
    }


def strict_read(path: Path) -> dict:
    if path.is_symlink() or not path.is_file() or path.stat().st_size > 64 * 1024:
        raise ManifestError("Windows protected signing intent file is unsafe")

    def unique(pairs):
        result = {}
        for key, value in pairs:
            if key in result:
                raise ManifestError("Windows protected signing intent has duplicate keys")
            result[key] = value
        return result

    try:
        value = json.loads(path.read_text(encoding="utf-8"), object_pairs_hook=unique)
    except (UnicodeError, json.JSONDecodeError) as error:
        raise ManifestError("Windows protected signing intent is unreadable") from error
    if not isinstance(value, dict):
        raise ManifestError("Windows protected signing intent must be an object")
    return value


def verify(path: Path, version_file: Path, source_revision: str, channel: str,
           signer_sha256: str, now_utc: datetime) -> dict[str, object]:
    value = strict_read(path)
    version = read_version(version_file)
    if (set(value) != KEYS or value.get("schemaVersion") != 1
            or value.get("product") != "chat-room-windows-client"
            or value.get("status") != STATUS or value.get("version") != version
            or value.get("sourceRevision") != source_revision
            or value.get("channel") != channel or value.get("buildSystem") != "cmake"
            or value.get("expectedSignerCertificateSha256") != signer_sha256
            or value.get("environment") != ENVIRONMENT
            or value.get("runnerClass") != RUNNER_CLASS):
        raise ManifestError("Windows protected signing intent identity is invalid")
    validate_values(
        version, source_revision, channel, value.get("unsignedArtifactRunId", ""),
        value.get("expectedSignerCertificateSha1", ""), signer_sha256,
        value.get("timestampUrl", ""))
    expected_name = (
        f"windows-{channel}-{version}-unsigned-product-trust-{source_revision}")
    if value.get("unsignedArtifactName") != expected_name:
        raise ManifestError("Windows protected signing artifact identity is invalid")
    recorded = exact_utc(value.get("recordedAt"))
    if now_utc.tzinfo != timezone.utc or now_utc.microsecond:
        raise ManifestError("Windows protected signing verification clock is invalid")
    if recorded > now_utc + timedelta(minutes=5) or now_utc - recorded > timedelta(hours=2):
        raise ManifestError("Windows protected signing intent is stale or from the future")
    return value


def atomic_write(path: Path, value: dict[str, object]) -> None:
    if not path.is_absolute() or path.exists() or path.is_symlink():
        raise ManifestError("Windows protected signing intent output is unsafe")
    path.parent.mkdir(parents=True, exist_ok=True)
    descriptor, temporary = tempfile.mkstemp(prefix=".windows-signing-intent-", dir=path.parent)
    try:
        with os.fdopen(descriptor, "w", encoding="utf-8", newline="\n") as handle:
            json.dump(value, handle, ensure_ascii=True, indent=2, sort_keys=True)
            handle.write("\n")
            handle.flush()
            os.fsync(handle.fileno())
        os.replace(temporary, path)
    finally:
        if os.path.exists(temporary):
            os.unlink(temporary)


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    commands = parser.add_subparsers(dest="command", required=True)
    create_parser = commands.add_parser("create")
    create_parser.add_argument("--version-file", type=Path, required=True)
    create_parser.add_argument("--source-revision", required=True)
    create_parser.add_argument("--channel", choices=("stable", "beta"), required=True)
    create_parser.add_argument("--unsigned-artifact-run-id", required=True)
    create_parser.add_argument("--signer-sha1", required=True)
    create_parser.add_argument("--signer-sha256", required=True)
    create_parser.add_argument("--timestamp-url", required=True)
    create_parser.add_argument("--output", type=Path, required=True)
    verify_parser = commands.add_parser("verify")
    verify_parser.add_argument("--intent", type=Path, required=True)
    verify_parser.add_argument("--version-file", type=Path, required=True)
    verify_parser.add_argument("--source-revision", required=True)
    verify_parser.add_argument("--channel", choices=("stable", "beta"), required=True)
    verify_parser.add_argument("--signer-sha256", required=True)
    args = parser.parse_args()
    now = datetime.now(timezone.utc).replace(microsecond=0)
    try:
        if args.command == "create":
            value = create(
                args.version_file, args.source_revision, args.channel,
                args.unsigned_artifact_run_id, args.signer_sha1,
                args.signer_sha256, args.timestamp_url, now)
            atomic_write(args.output.resolve(strict=False), value)
        else:
            value = verify(
                args.intent, args.version_file, args.source_revision,
                args.channel, args.signer_sha256, now)
    except (ManifestError, OSError) as error:
        raise SystemExit(f"Windows protected signing intent failed: {error}") from None
    print(json.dumps({
        "status": value["status"], "version": value["version"],
        "sourceRevision": value["sourceRevision"],
        "unsignedArtifactRunId": value["unsignedArtifactRunId"],
    }, ensure_ascii=True, sort_keys=True))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
