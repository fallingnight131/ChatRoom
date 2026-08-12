#!/usr/bin/env python3
"""Assemble or verify an immutable signed Windows release candidate."""

from __future__ import annotations

import argparse
import json
import os
import re
import shutil
import tempfile
from datetime import datetime, timedelta, timezone
from pathlib import Path, PurePosixPath

from artifact_manifest_common import (
    ManifestError,
    atomic_write,
    payload_files,
    read_version,
    sha256_file,
    validate_revision,
)
from windows_release_evidence import HEX64, verify_evidence
from windows_install_evidence import verify_install_evidence
from windows_protected_release_intent import verify as verify_signing_intent
from windows_update_product_trust_evidence import (
    verify_evidence as verify_product_trust_evidence,
)


QT_VERSION = re.compile(r"^[0-9]+\.[0-9]+\.[0-9]+$")
CHANNELS = {"stable", "beta"}
RELEASE_STATUS = "signed-timestamped-not-published-candidate"
MANIFEST_NAME = "windows-release-candidate.json"
CHECKSUMS_NAME = "SHA256SUMS"
EVIDENCE_PATH = "evidence/windows-release-signatures.json"
INTENT_PATH = "evidence/protected-signing-intent.json"
INSTALL_EVIDENCE_PATH = "evidence/windows-install-acceptance.json"
PRODUCT_TRUST_INTENT_PATH = "evidence/product-update-trust-intent.json"
PRODUCT_TRUST_DIAGNOSTIC_PATH = "evidence/signed-product-update-trust-diagnostic.json"
PRODUCT_TRUST_EVIDENCE_PATH = "evidence/signed-product-update-trust-evidence.json"
PRODUCT_TRUST_PRIMARY_KEY_PATH = "evidence/product-update-primary-public.pem"
PRODUCT_TRUST_SECONDARY_KEY_PATH = "evidence/product-update-secondary-public.pem"
FORBIDDEN_SUFFIXES = {
    ".env", ".exp", ".ilk", ".key", ".lib", ".obj", ".pdb", ".pem", ".pfx",
}
REQUIRED_PATHS = {
    "client/ChatClient.exe",
    "client/ChatRoomUpdateLauncher.exe",
    "client/Qt6Core.dll",
    "client/platforms/qwindows.dll",
    "client/sqldrivers/qsqlite.dll",
}
ROOT_KEYS = {
    "schemaVersion", "product", "releaseStatus", "channel", "version",
    "sourceRevision", "platform", "architecture", "toolchain", "qtVersion",
    "expectedSignerCertificateSha256", "signatureEvidencePath", "installerPath",
    "uninstallerPath", "protectedSigningIntentPath", "installAcceptanceEvidencePath",
    "productUpdateTrust", "assembledAt", "files",
}
FILE_KEYS = {"path", "sha256", "size"}
PRODUCT_TRUST_KEYS = {
    "intentPath", "diagnosticPath", "evidencePath", "primaryPublicKeyPath",
    "secondaryPublicKeyPath", "manifestUrl", "keyIds",
}


def _exact_utc(value: object) -> datetime:
    if not isinstance(value, str) or not value.endswith("Z"):
        raise ManifestError("Windows release candidate assembly time is invalid")
    try:
        parsed = datetime.fromisoformat(value[:-1] + "+00:00")
    except ValueError as error:
        raise ManifestError("Windows release candidate assembly time is invalid") from error
    if parsed.microsecond or parsed.tzinfo != timezone.utc:
        raise ManifestError("Windows release candidate assembly time is invalid")
    return parsed


def _strict_json(path: Path, label: str) -> dict[str, object]:
    if path.is_symlink() or not path.is_file():
        raise ManifestError(f"{label} must be a regular file")

    def strict_object(pairs: list[tuple[str, object]]) -> dict[str, object]:
        result: dict[str, object] = {}
        for key, value in pairs:
            if key in result:
                raise ManifestError(f"{label} has duplicate keys")
            result[key] = value
        return result

    try:
        value = json.loads(
            path.read_text(encoding="utf-8"), object_pairs_hook=strict_object)
    except (OSError, UnicodeDecodeError, json.JSONDecodeError) as error:
        raise ManifestError(f"{label} is unreadable") from error
    if not isinstance(value, dict):
        raise ManifestError(f"{label} must be a JSON object")
    return value


def _safe_relative(value: object) -> str:
    if not isinstance(value, str) or not value or "\\" in value:
        raise ManifestError("Windows release candidate contains an unsafe path")
    path = PurePosixPath(value)
    if (path.is_absolute() or path.as_posix() != value
            or any(part in {"", ".", ".."} for part in path.parts)):
        raise ManifestError("Windows release candidate contains an unsafe path")
    return value


def _validate_payload_policy(paths: set[str]) -> None:
    if not REQUIRED_PATHS.issubset(paths):
        raise ManifestError("Windows release candidate is missing a required runtime")
    if not any(
        PurePosixPath(path).parent == PurePosixPath("client")
        and "sodium" in PurePosixPath(path).name.lower()
        and PurePosixPath(path).suffix.lower() == ".dll"
        for path in paths
    ):
        raise ManifestError("Windows release candidate is missing the libsodium runtime")
    for relative in paths:
        path = PurePosixPath(relative)
        lowered = path.name.lower()
        if (not relative.startswith("client/")
                or lowered == "chatserver.exe"
                or lowered.startswith(".env")
                or path.suffix.lower() in FORBIDDEN_SUFFIXES):
            raise ManifestError("Windows release candidate contains a forbidden payload file")


def _read_checksums(path: Path) -> dict[str, str]:
    try:
        lines = path.read_text(encoding="utf-8").splitlines()
    except OSError as error:
        raise ManifestError("Windows release candidate checksums are unreadable") from error
    result: dict[str, str] = {}
    for line in lines:
        parts = line.split("  ", 1)
        if (len(parts) != 2 or not HEX64.fullmatch(parts[0])
                or parts[1] in result):
            raise ManifestError("Windows release candidate checksums are malformed")
        result[_safe_relative(parts[1])] = parts[0]
    if not result:
        raise ManifestError("Windows release candidate checksums are empty")
    return result


def _verify_product_trust(
    client_path: Path,
    intent_path: Path,
    diagnostic_path: Path,
    evidence_path: Path,
    primary_public_key: Path,
    secondary_public_key: Path | None,
    version_file: Path,
    source_revision: str,
    channel: str,
) -> dict[str, object]:
    intent = _strict_json(intent_path, "Windows product trust intent")
    primary = intent.get("primaryKey")
    secondary = intent.get("secondaryKey")
    if (not isinstance(primary, dict) or not isinstance(primary.get("keyId"), str)
            or (secondary is not None and
                (not isinstance(secondary, dict)
                 or not isinstance(secondary.get("keyId"), str)))):
        raise ManifestError("Windows product trust intent key identity is invalid")
    if (secondary is None) != (secondary_public_key is None):
        raise ManifestError("Windows product trust secondary key closure is inconsistent")
    manifest_url = intent.get("manifestUrl")
    if not isinstance(manifest_url, str):
        raise ManifestError("Windows product trust manifest URL is invalid")
    result = verify_product_trust_evidence(
        evidence_path, client_path, diagnostic_path, intent_path, version_file,
        source_revision, channel, manifest_url, primary["keyId"],
        primary_public_key,
        secondary["keyId"] if isinstance(secondary, dict) else None,
        secondary_public_key,
    )
    return {
        "intentPath": PRODUCT_TRUST_INTENT_PATH,
        "diagnosticPath": PRODUCT_TRUST_DIAGNOSTIC_PATH,
        "evidencePath": PRODUCT_TRUST_EVIDENCE_PATH,
        "primaryPublicKeyPath": PRODUCT_TRUST_PRIMARY_KEY_PATH,
        "secondaryPublicKeyPath": (
            PRODUCT_TRUST_SECONDARY_KEY_PATH if secondary_public_key else None),
        "manifestUrl": manifest_url,
        "keyIds": result["keyIds"],
    }


def _identity(
    version_file: Path,
    source_revision: str,
    channel: str,
    qt_version: str,
    expected_signer_sha256: str,
) -> tuple[str, str, str]:
    version = read_version(version_file)
    validate_revision(source_revision)
    if channel not in CHANNELS:
        raise ManifestError("Windows release channel must be stable or beta")
    if not QT_VERSION.fullmatch(qt_version):
        raise ManifestError("Windows release Qt version must use major.minor.patch")
    if not HEX64.fullmatch(expected_signer_sha256):
        raise ManifestError("Windows release publisher SHA-256 is invalid")
    return (
        version,
        f"ChatRoom-{version}-Setup.exe",
        f"ChatRoom-{version}-Uninstall.exe",
    )


def validate_candidate(
    candidate_root: Path,
    version_file: Path,
    source_revision: str,
    channel: str,
    qt_version: str,
    expected_signer_sha256: str,
    now_utc: datetime,
) -> dict[str, object]:
    version, installer_name, uninstaller_name = _identity(
        version_file, source_revision, channel, qt_version,
        expected_signer_sha256)
    if candidate_root.is_symlink() or not candidate_root.is_dir():
        raise ManifestError("Windows release candidate root must be a real directory")
    manifest = _strict_json(candidate_root / MANIFEST_NAME,
                            "Windows release candidate manifest")
    if set(manifest) != ROOT_KEYS:
        raise ManifestError("Windows release candidate manifest has an unsupported shape")
    expected_installer_path = f"installer/{installer_name}"
    expected_uninstaller_path = f"installer/{uninstaller_name}"
    assembled_at = _exact_utc(manifest.get("assembledAt"))
    if now_utc.tzinfo is None or now_utc.utcoffset() != timedelta(0):
        raise ManifestError("Windows release candidate verifier requires a UTC clock")
    verification_now = now_utc.replace(microsecond=0)
    if assembled_at > verification_now:
        raise ManifestError("Windows release candidate assembly time is from the future")
    if (type(manifest["schemaVersion"]) is not int
            or manifest["schemaVersion"] != 6
            or manifest["product"] != "chat-room-windows-client"
            or manifest["releaseStatus"] != RELEASE_STATUS
            or manifest["channel"] != channel
            or manifest["version"] != version
            or manifest["sourceRevision"] != source_revision
            or manifest["platform"] != "windows"
            or manifest["architecture"] != "x86_64"
            or manifest["toolchain"] != "msvc2022"
            or manifest["qtVersion"] != qt_version
            or manifest["expectedSignerCertificateSha256"] != expected_signer_sha256
            or manifest["signatureEvidencePath"] != EVIDENCE_PATH
            or manifest["protectedSigningIntentPath"] != INTENT_PATH
            or manifest["installAcceptanceEvidencePath"] != INSTALL_EVIDENCE_PATH
            or manifest["installerPath"] != expected_installer_path
            or manifest["uninstallerPath"] != expected_uninstaller_path):
        raise ManifestError("Windows release candidate identity is invalid")

    product_trust = manifest["productUpdateTrust"]
    if (not isinstance(product_trust, dict)
            or set(product_trust) != PRODUCT_TRUST_KEYS
            or product_trust.get("intentPath") != PRODUCT_TRUST_INTENT_PATH
            or product_trust.get("diagnosticPath") != PRODUCT_TRUST_DIAGNOSTIC_PATH
            or product_trust.get("evidencePath") != PRODUCT_TRUST_EVIDENCE_PATH
            or product_trust.get("primaryPublicKeyPath") != PRODUCT_TRUST_PRIMARY_KEY_PATH
            or product_trust.get("secondaryPublicKeyPath") not in {
                None, PRODUCT_TRUST_SECONDARY_KEY_PATH}
            or not isinstance(product_trust.get("manifestUrl"), str)
            or not isinstance(product_trust.get("keyIds"), list)):
        raise ManifestError("Windows release candidate product trust is malformed")

    entries = manifest["files"]
    if not isinstance(entries, list) or not entries:
        raise ManifestError("Windows release candidate file list is empty")
    declared: dict[str, tuple[str, int]] = {}
    ordered: list[str] = []
    for entry in entries:
        if not isinstance(entry, dict) or set(entry) != FILE_KEYS:
            raise ManifestError("Windows release candidate file entry is malformed")
        relative = _safe_relative(entry["path"])
        digest, size = entry["sha256"], entry["size"]
        if (relative in declared or not isinstance(digest, str)
                or not HEX64.fullmatch(digest)
                or type(size) is not int or size <= 0):
            raise ManifestError("Windows release candidate file entry is malformed")
        declared[relative] = (digest, size)
        ordered.append(relative)
    if ordered != sorted(ordered):
        raise ManifestError("Windows release candidate file entries are not sorted")

    client_paths = {path for path in declared if path.startswith("client/")}
    _validate_payload_policy(client_paths)
    trust_paths = {
        PRODUCT_TRUST_INTENT_PATH, PRODUCT_TRUST_DIAGNOSTIC_PATH,
        PRODUCT_TRUST_EVIDENCE_PATH, PRODUCT_TRUST_PRIMARY_KEY_PATH,
    }
    if product_trust["secondaryPublicKeyPath"] is not None:
        trust_paths.add(PRODUCT_TRUST_SECONDARY_KEY_PATH)
    if set(declared) != client_paths | trust_paths | {
            expected_installer_path, expected_uninstaller_path,
            EVIDENCE_PATH, INTENT_PATH, INSTALL_EVIDENCE_PATH}:
        raise ManifestError("Windows release candidate contains an unsupported file class")
    actual = {
        path.relative_to(candidate_root).as_posix()
        for path in payload_files(candidate_root)
    }
    if actual != set(declared) | {MANIFEST_NAME, CHECKSUMS_NAME}:
        raise ManifestError("Windows release candidate has undeclared or missing files")
    checksums = _read_checksums(candidate_root / CHECKSUMS_NAME)
    if set(checksums) != set(declared):
        raise ManifestError("Windows release candidate checksum paths do not match")
    for relative, (expected_digest, expected_size) in declared.items():
        digest, size = sha256_file(candidate_root / relative)
        if (digest != expected_digest or size != expected_size
                or checksums[relative] != digest):
            raise ManifestError("Windows release candidate final bytes changed")

    verify_evidence(
        candidate_root / EVIDENCE_PATH,
        candidate_root / "client/ChatClient.exe",
        candidate_root / "client/ChatRoomUpdateLauncher.exe",
        candidate_root / expected_uninstaller_path,
        candidate_root / expected_installer_path,
        version_file,
        source_revision,
        expected_signer_sha256,
        assembled_at,
    )
    verify_signing_intent(
        candidate_root / INTENT_PATH, version_file, source_revision,
        channel, expected_signer_sha256, assembled_at)
    verify_install_evidence(
        candidate_root / INSTALL_EVIDENCE_PATH,
        candidate_root / "client/ChatClient.exe",
        candidate_root / "client/ChatRoomUpdateLauncher.exe",
        candidate_root / expected_uninstaller_path,
        candidate_root / expected_installer_path,
        version_file, source_revision, expected_signer_sha256, assembled_at)
    verified_product_trust = _verify_product_trust(
        candidate_root / "client/ChatClient.exe",
        candidate_root / PRODUCT_TRUST_INTENT_PATH,
        candidate_root / PRODUCT_TRUST_DIAGNOSTIC_PATH,
        candidate_root / PRODUCT_TRUST_EVIDENCE_PATH,
        candidate_root / PRODUCT_TRUST_PRIMARY_KEY_PATH,
        (candidate_root / PRODUCT_TRUST_SECONDARY_KEY_PATH
         if product_trust["secondaryPublicKeyPath"] is not None else None),
        version_file, source_revision, channel,
    )
    if product_trust != verified_product_trust:
        raise ManifestError("Windows release candidate product trust identity differs")
    return {
        "releaseId": f"windows-{channel}-{version}-{source_revision}",
        "version": version,
        "channel": channel,
        "sourceRevision": source_revision,
        "fileCount": len(declared),
        "releaseStatus": RELEASE_STATUS,
    }


def assemble_candidate(
    payload_root: Path,
    uninstaller_path: Path,
    installer_path: Path,
    evidence_path: Path,
    intent_path: Path,
    install_evidence_path: Path,
    product_trust_intent_path: Path,
    product_trust_diagnostic_path: Path,
    product_trust_evidence_path: Path,
    product_trust_primary_public_key: Path,
    product_trust_secondary_public_key: Path | None,
    output_root: Path,
    version_file: Path,
    source_revision: str,
    channel: str,
    qt_version: str,
    expected_signer_sha256: str,
    now_utc: datetime,
) -> dict[str, object]:
    version, installer_name, uninstaller_name = _identity(
        version_file, source_revision, channel, qt_version,
        expected_signer_sha256)
    source_files = payload_files(payload_root)
    source_relatives = {
        f"client/{path.relative_to(payload_root).as_posix()}" for path in source_files
    }
    _validate_payload_policy(source_relatives)
    verify_evidence(
        evidence_path,
        payload_root / "ChatClient.exe",
        payload_root / "ChatRoomUpdateLauncher.exe",
        uninstaller_path,
        installer_path,
        version_file,
        source_revision,
        expected_signer_sha256,
        now_utc,
    )
    verify_signing_intent(
        intent_path, version_file, source_revision, channel,
        expected_signer_sha256, now_utc)
    verify_install_evidence(
        install_evidence_path,
        payload_root / "ChatClient.exe",
        payload_root / "ChatRoomUpdateLauncher.exe",
        uninstaller_path, installer_path, version_file, source_revision,
        expected_signer_sha256, now_utc)
    product_trust = _verify_product_trust(
        payload_root / "ChatClient.exe", product_trust_intent_path,
        product_trust_diagnostic_path, product_trust_evidence_path,
        product_trust_primary_public_key, product_trust_secondary_public_key,
        version_file, source_revision, channel,
    )
    if installer_path.name != installer_name:
        raise ManifestError("Windows release installer name is invalid")
    if uninstaller_path.name != uninstaller_name:
        raise ManifestError("Windows release uninstaller name is invalid")
    resolved_payload = payload_root.resolve()
    resolved_output = output_root.resolve()
    if resolved_output == resolved_payload or resolved_payload in resolved_output.parents:
        raise ManifestError("Windows release candidate destination overlaps the source payload")
    output_parent = output_root.parent
    output_parent.mkdir(parents=True, exist_ok=True)
    if output_parent.is_symlink() or not output_parent.is_dir() or output_root.exists():
        raise ManifestError("Windows release candidate destination is unsafe or already exists")

    temporary = Path(tempfile.mkdtemp(prefix=".windows-candidate-", dir=output_parent))
    try:
        copied: list[Path] = []
        for source in source_files:
            target = temporary / "client" / source.relative_to(payload_root)
            target.parent.mkdir(parents=True, exist_ok=True)
            shutil.copyfile(source, target)
            copied.append(target)
        target_installer = temporary / "installer" / installer_name
        target_installer.parent.mkdir(parents=True, exist_ok=True)
        shutil.copyfile(installer_path, target_installer)
        copied.append(target_installer)
        target_uninstaller = temporary / "installer" / uninstaller_name
        shutil.copyfile(uninstaller_path, target_uninstaller)
        copied.append(target_uninstaller)
        target_evidence = temporary / EVIDENCE_PATH
        target_evidence.parent.mkdir(parents=True, exist_ok=True)
        shutil.copyfile(evidence_path, target_evidence)
        copied.append(target_evidence)
        target_intent = temporary / INTENT_PATH
        target_intent.parent.mkdir(parents=True, exist_ok=True)
        shutil.copyfile(intent_path, target_intent)
        copied.append(target_intent)
        target_install_evidence = temporary / INSTALL_EVIDENCE_PATH
        shutil.copyfile(install_evidence_path, target_install_evidence)
        copied.append(target_install_evidence)
        product_trust_sources = (
            (product_trust_intent_path, PRODUCT_TRUST_INTENT_PATH),
            (product_trust_diagnostic_path, PRODUCT_TRUST_DIAGNOSTIC_PATH),
            (product_trust_evidence_path, PRODUCT_TRUST_EVIDENCE_PATH),
            (product_trust_primary_public_key, PRODUCT_TRUST_PRIMARY_KEY_PATH),
        )
        for source, relative in product_trust_sources:
            target = temporary / relative
            target.parent.mkdir(parents=True, exist_ok=True)
            shutil.copyfile(source, target)
            copied.append(target)
        if product_trust_secondary_public_key is not None:
            target = temporary / PRODUCT_TRUST_SECONDARY_KEY_PATH
            shutil.copyfile(product_trust_secondary_public_key, target)
            copied.append(target)

        verify_evidence(
            target_evidence,
            temporary / "client/ChatClient.exe",
            temporary / "client/ChatRoomUpdateLauncher.exe",
            target_uninstaller,
            target_installer,
            version_file,
            source_revision,
            expected_signer_sha256,
            now_utc,
        )
        copied_product_trust = _verify_product_trust(
            temporary / "client/ChatClient.exe",
            temporary / PRODUCT_TRUST_INTENT_PATH,
            temporary / PRODUCT_TRUST_DIAGNOSTIC_PATH,
            temporary / PRODUCT_TRUST_EVIDENCE_PATH,
            temporary / PRODUCT_TRUST_PRIMARY_KEY_PATH,
            (temporary / PRODUCT_TRUST_SECONDARY_KEY_PATH
             if product_trust_secondary_public_key is not None else None),
            version_file, source_revision, channel,
        )
        if copied_product_trust != product_trust:
            raise ManifestError("Copied Windows product trust differs")
        entries: list[dict[str, object]] = []
        checksums: list[str] = []
        for path in sorted(copied, key=lambda value: value.relative_to(temporary).as_posix()):
            relative = path.relative_to(temporary).as_posix()
            digest, size = sha256_file(path)
            entries.append({"path": relative, "sha256": digest, "size": size})
            checksums.append(f"{digest}  {relative}")
        manifest = {
            "schemaVersion": 6,
            "product": "chat-room-windows-client",
            "releaseStatus": RELEASE_STATUS,
            "channel": channel,
            "version": version,
            "sourceRevision": source_revision,
            "platform": "windows",
            "architecture": "x86_64",
            "toolchain": "msvc2022",
            "qtVersion": qt_version,
            "expectedSignerCertificateSha256": expected_signer_sha256,
            "signatureEvidencePath": EVIDENCE_PATH,
            "protectedSigningIntentPath": INTENT_PATH,
            "installAcceptanceEvidencePath": INSTALL_EVIDENCE_PATH,
            "productUpdateTrust": product_trust,
            "assembledAt": now_utc.astimezone(timezone.utc).replace(
                microsecond=0).strftime("%Y-%m-%dT%H:%M:%SZ"),
            "installerPath": f"installer/{installer_name}",
            "uninstallerPath": f"installer/{uninstaller_name}",
            "files": entries,
        }
        atomic_write(
            temporary / MANIFEST_NAME,
            json.dumps(manifest, ensure_ascii=True, indent=2, sort_keys=True) + "\n",
        )
        atomic_write(temporary / CHECKSUMS_NAME, "\n".join(checksums) + "\n")
        identity = validate_candidate(
            temporary, version_file, source_revision, channel, qt_version,
            expected_signer_sha256, now_utc)
        os.rename(temporary, output_root)
        return {**identity, "assemblyStatus": "assembled"}
    except Exception:
        shutil.rmtree(temporary, ignore_errors=True)
        raise


def _common(parser: argparse.ArgumentParser) -> None:
    parser.add_argument("--version-file", type=Path, required=True)
    parser.add_argument("--source-revision", required=True)
    parser.add_argument("--channel", choices=sorted(CHANNELS), required=True)
    parser.add_argument("--qt-version", required=True)
    parser.add_argument("--expected-signer-sha256", required=True)


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    commands = parser.add_subparsers(dest="command", required=True)
    assemble = commands.add_parser("assemble")
    assemble.add_argument("--payload-root", type=Path, required=True)
    assemble.add_argument("--uninstaller", type=Path, required=True)
    assemble.add_argument("--installer", type=Path, required=True)
    assemble.add_argument("--signature-evidence", type=Path, required=True)
    assemble.add_argument("--protected-signing-intent", type=Path, required=True)
    assemble.add_argument("--install-acceptance-evidence", type=Path, required=True)
    assemble.add_argument("--product-trust-intent", type=Path, required=True)
    assemble.add_argument("--product-trust-diagnostic", type=Path, required=True)
    assemble.add_argument("--product-trust-evidence", type=Path, required=True)
    assemble.add_argument("--product-trust-primary-public-key", type=Path, required=True)
    assemble.add_argument("--product-trust-secondary-public-key", type=Path)
    assemble.add_argument("--output-root", type=Path, required=True)
    _common(assemble)
    verify = commands.add_parser("verify")
    verify.add_argument("--candidate-root", type=Path, required=True)
    _common(verify)
    return parser.parse_args()


def main() -> int:
    args = parse_args()
    try:
        if args.command == "assemble":
            result = assemble_candidate(
                args.payload_root, args.uninstaller, args.installer,
                args.signature_evidence,
                args.protected_signing_intent,
                args.install_acceptance_evidence,
                args.product_trust_intent,
                args.product_trust_diagnostic,
                args.product_trust_evidence,
                args.product_trust_primary_public_key,
                args.product_trust_secondary_public_key,
                args.output_root, args.version_file, args.source_revision,
                args.channel, args.qt_version, args.expected_signer_sha256,
                datetime.now(timezone.utc).replace(microsecond=0),
            )
        else:
            result = validate_candidate(
                args.candidate_root, args.version_file, args.source_revision,
                args.channel, args.qt_version, args.expected_signer_sha256,
                datetime.now(timezone.utc).replace(microsecond=0),
            )
    except (ManifestError, OSError) as error:
        raise SystemExit(f"Windows release candidate failed: {error}") from None
    print(json.dumps(result, ensure_ascii=True, sort_keys=True))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
