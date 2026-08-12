#!/usr/bin/env python3
"""Bind final ChatClient bytes and compiled update trust to a reviewed intent."""

from __future__ import annotations

import argparse
import json
import os
import tempfile
from datetime import datetime, timezone
from pathlib import Path

from artifact_manifest_common import ManifestError, sha256_file
from windows_update_product_trust_intent import verify_intent


DIAGNOSTIC_KEYS = {
    "schemaVersion", "product", "enabled", "channel", "manifestUrl",
    "signatureUrl", "trustedKeys", "error",
}
EVIDENCE_KEYS = {
    "schemaVersion", "evidenceType", "status", "version", "sourceRevision",
    "channel", "manifestUrl", "keyIds", "clientSha256", "intentSha256",
    "diagnosticSha256", "capturedAt",
}


def _strict(path: Path, label: str) -> dict[str, object]:
    if path.is_symlink() or not path.is_file() or path.stat().st_size > 1024 * 1024:
        raise ManifestError(f"Windows update trust {label} is unsafe")

    def unique(pairs):
        result = {}
        for key, value in pairs:
            if key in result:
                raise ManifestError(f"Windows update trust {label} has duplicate keys")
            result[key] = value
        return result

    try:
        value = json.loads(path.read_text(encoding="utf-8"), object_pairs_hook=unique)
    except (OSError, UnicodeDecodeError, json.JSONDecodeError) as error:
        raise ManifestError(f"Windows update trust {label} is unreadable") from error
    if not isinstance(value, dict):
        raise ManifestError(f"Windows update trust {label} must be an object")
    return value


def _digest(path: Path) -> str:
    digest, _ = sha256_file(path)
    return digest


def _expected_diagnostic(intent: dict[str, object]) -> dict[str, object]:
    public_keys = [intent["primaryKey"]]
    if intent["secondaryKey"] is not None:
        public_keys.append(intent["secondaryKey"])
    keys = sorted(
        ({"keyId": key["keyId"], "publicKeyHex": key["publicKeyHex"]}
         for key in public_keys),
        key=lambda value: value["keyId"],
    )
    return {
        "schemaVersion": 1,
        "product": "chat-room-windows-client",
        "enabled": True,
        "channel": intent["channel"],
        "manifestUrl": intent["manifestUrl"],
        "signatureUrl": str(intent["manifestUrl"]) + ".sig",
        "trustedKeys": keys,
        "error": "",
    }


def build_evidence(
    client_path: Path,
    diagnostic_path: Path,
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
    if now_utc.tzinfo != timezone.utc or now_utc.microsecond:
        raise ManifestError("Windows update trust evidence clock must be exact UTC")
    if client_path.is_symlink() or not client_path.is_file() or client_path.name != "ChatClient.exe":
        raise ManifestError("Windows update trust evidence client is invalid")
    intent = verify_intent(
        intent_path, version_file, source_revision, channel, manifest_url,
        primary_key_id, primary_public_key, now_utc, secondary_key_id,
        secondary_public_key,
    )
    diagnostic = _strict(diagnostic_path, "diagnostic")
    if set(diagnostic) != DIAGNOSTIC_KEYS or diagnostic != _expected_diagnostic(intent):
        raise ManifestError("Final Windows client trust does not match reviewed intent")
    client_digest, _ = sha256_file(client_path)
    return {
        "schemaVersion": 1,
        "evidenceType": "windows-update-product-trust-binary",
        "status": "compiled-product-update-trust-verified",
        "version": intent["version"],
        "sourceRevision": source_revision,
        "channel": channel,
        "manifestUrl": manifest_url,
        "keyIds": [value["keyId"] for value in diagnostic["trustedKeys"]],
        "clientSha256": client_digest,
        "intentSha256": _digest(intent_path),
        "diagnosticSha256": _digest(diagnostic_path),
        "capturedAt": now_utc.strftime("%Y-%m-%dT%H:%M:%SZ"),
    }


def write_once(path: Path, value: dict[str, object]) -> None:
    if path.exists() or path.is_symlink() or not path.is_absolute():
        raise ManifestError("Windows update trust evidence output is unsafe or already exists")
    path.parent.mkdir(parents=True, exist_ok=True)
    if path.parent.is_symlink() or not path.parent.is_dir():
        raise ManifestError("Windows update trust evidence output directory is unsafe")
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
            raise ManifestError("Windows update trust evidence output already exists") from error
    finally:
        if temporary is not None:
            temporary.unlink(missing_ok=True)


def verify_evidence(
    evidence_path: Path,
    client_path: Path,
    diagnostic_path: Path,
    intent_path: Path,
    version_file: Path,
    source_revision: str,
    channel: str,
    manifest_url: str,
    primary_key_id: str,
    primary_public_key: Path,
    secondary_key_id: str | None = None,
    secondary_public_key: Path | None = None,
) -> dict[str, object]:
    recorded = _strict(evidence_path, "evidence")
    if set(recorded) != EVIDENCE_KEYS:
        raise ManifestError("Windows update trust evidence has an unsupported shape")
    try:
        captured = datetime.strptime(
            str(recorded["capturedAt"]), "%Y-%m-%dT%H:%M:%SZ").replace(
                tzinfo=timezone.utc)
    except ValueError as error:
        raise ManifestError("Windows update trust evidence time is invalid") from error
    expected = build_evidence(
        client_path, diagnostic_path, intent_path, version_file, source_revision,
        channel, manifest_url, primary_key_id, primary_public_key, captured,
        secondary_key_id, secondary_public_key,
    )
    if recorded != expected:
        raise ManifestError("Windows update trust evidence does not match final inputs")
    return recorded


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("command", choices=("create", "verify"))
    parser.add_argument("--evidence", type=Path, required=True)
    parser.add_argument("--client", type=Path, required=True)
    parser.add_argument("--diagnostic", type=Path, required=True)
    parser.add_argument("--intent", type=Path, required=True)
    parser.add_argument("--version-file", type=Path, required=True)
    parser.add_argument("--source-revision", required=True)
    parser.add_argument("--channel", choices=("stable", "beta"), required=True)
    parser.add_argument("--manifest-url", required=True)
    parser.add_argument("--primary-key-id", required=True)
    parser.add_argument("--primary-public-key", type=Path, required=True)
    parser.add_argument("--secondary-key-id")
    parser.add_argument("--secondary-public-key", type=Path)
    args = parser.parse_args()
    values = (
        args.client, args.diagnostic, args.intent, args.version_file,
        args.source_revision, args.channel, args.manifest_url,
        args.primary_key_id, args.primary_public_key,
    )
    try:
        if args.command == "create":
            result = build_evidence(
                *values, datetime.now(timezone.utc).replace(microsecond=0),
                args.secondary_key_id, args.secondary_public_key)
            write_once(args.evidence, result)
        else:
            result = verify_evidence(
                args.evidence, *values, args.secondary_key_id,
                args.secondary_public_key)
    except (ManifestError, OSError) as error:
        raise SystemExit(f"Windows update trust evidence failed: {error}") from None
    print(json.dumps(result, ensure_ascii=True, sort_keys=True))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
