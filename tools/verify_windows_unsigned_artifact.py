#!/usr/bin/env python3
"""Independently verify a complete unsigned Windows artifact before signing."""

from __future__ import annotations

import argparse
import json
from pathlib import Path
import re

from artifact_manifest_common import ManifestError, read_version, sha256_file, validate_revision
from windows_artifact_manifest import product_trust_bundle


MANIFEST = "artifact-manifest.json"
CHECKSUMS = "SHA256SUMS"
EXPECTED_KEYS = {
    "schemaVersion", "product", "version", "channel", "platform",
    "architecture", "toolchain", "qtVersion", "sourceRevision", "buildSystem",
    "signatureStatus", "files", "installer", "cmakePayloadParity",
    "productUpdateTrust",
}
FILE_KEYS = {"path", "sha256", "size"}
INSTALLER_KEYS = FILE_KEYS | {"format", "signatureStatus"}
PARITY_KEYS = FILE_KEYS | {"runtimeBytesEquivalent"}
TRUST_KEYS = {
    "status", "channel", "manifestUrl", "keyIds", "intent", "diagnostic",
    "evidence", "primaryPublicKey", "secondaryPublicKey",
}
HEX64 = re.compile(r"^[0-9a-f]{64}$")
QT_VERSION = re.compile(r"^[0-9]+\.[0-9]+\.[0-9]+$")


def strict_json(path: Path) -> dict:
    if path.is_symlink() or not path.is_file() or path.stat().st_size > 4 * 1024 * 1024:
        raise ManifestError("Windows unsigned artifact manifest is unsafe")

    def unique(pairs):
        value = {}
        for key, item in pairs:
            if key in value:
                raise ManifestError("Windows unsigned artifact JSON has duplicate keys")
            value[key] = item
        return value

    try:
        value = json.loads(path.read_text(encoding="utf-8"), object_pairs_hook=unique)
    except (UnicodeError, json.JSONDecodeError) as error:
        raise ManifestError("Windows unsigned artifact manifest is unreadable") from error
    if not isinstance(value, dict):
        raise ManifestError("Windows unsigned artifact manifest must be an object")
    return value


def safe_entry(entry: object, keys: set[str], prefix: str) -> tuple[str, str, int]:
    if not isinstance(entry, dict) or set(entry) != keys:
        raise ManifestError("Windows unsigned artifact file entry is malformed")
    path = entry.get("path")
    digest = entry.get("sha256")
    size = entry.get("size")
    if (not isinstance(path, str) or not path.startswith(prefix)
            or path.startswith("/") or "\\" in path or ".." in Path(path).parts
            or not isinstance(digest, str) or not HEX64.fullmatch(digest)
            or not isinstance(size, int) or isinstance(size, bool) or size <= 0):
        raise ManifestError("Windows unsigned artifact file entry policy rejected a value")
    return path, digest, size


def checksums(path: Path) -> dict[str, str]:
    if path.is_symlink() or not path.is_file() or path.stat().st_size > 4 * 1024 * 1024:
        raise ManifestError("Windows unsigned artifact checksums are unsafe")
    result = {}
    try:
        lines = path.read_text(encoding="ascii").splitlines()
    except UnicodeError as error:
        raise ManifestError("Windows unsigned artifact checksums are malformed") from error
    for line in lines:
        if "  " not in line:
            raise ManifestError("Windows unsigned artifact checksums are malformed")
        digest, relative = line.split("  ", 1)
        if not HEX64.fullmatch(digest) or not relative or relative in result:
            raise ManifestError("Windows unsigned artifact checksums are malformed")
        result[relative] = digest
    if not result:
        raise ManifestError("Windows unsigned artifact checksums are empty")
    return result


def verify(root: Path, version_file: Path, source_revision: str,
           qt_version: str, require_product_update_trust: bool = False) -> dict[str, object]:
    validate_revision(source_revision)
    if not QT_VERSION.fullmatch(qt_version):
        raise ManifestError("Qt version must use major.minor.patch")
    version = read_version(version_file)
    if root.is_symlink() or not root.is_dir():
        raise ManifestError("Windows unsigned artifact root must be a real directory")
    manifest = strict_json(root / MANIFEST)
    if (set(manifest) != EXPECTED_KEYS or manifest.get("schemaVersion") != 4
            or manifest.get("product") != "chat-room-windows-client"
            or manifest.get("version") != version
            or manifest.get("channel") != "verification"
            or manifest.get("platform") != "windows"
            or manifest.get("architecture") != "x86_64"
            or manifest.get("toolchain") != "msvc2022"
            or manifest.get("qtVersion") != qt_version
            or manifest.get("sourceRevision") != source_revision
            or manifest.get("buildSystem") != "cmake"
            or manifest.get("signatureStatus") != "unsigned-verification-only"):
        raise ManifestError("Windows unsigned artifact identity is invalid")

    declared: dict[str, tuple[str, int]] = {}
    files = manifest.get("files")
    if not isinstance(files, list) or not files:
        raise ManifestError("Windows unsigned artifact file list is invalid")
    ordered_paths = []
    for entry in files:
        path, digest, size = safe_entry(entry, FILE_KEYS, "client/")
        if path in declared:
            raise ManifestError("Windows unsigned artifact has duplicate file entries")
        declared[path] = (digest, size)
        ordered_paths.append(path)
    if ordered_paths != sorted(ordered_paths):
        raise ManifestError("Windows unsigned artifact file entries are not sorted")
    required_payload = {
        "client/ChatClient.exe", "client/ChatRoomUpdateLauncher.exe",
        "client/Qt6Core.dll", "client/platforms/qwindows.dll",
        "client/sqldrivers/qsqlite.dll",
    }
    folded = [path.casefold() for path in declared]
    sodium = [path for path in declared
              if path.count("/") == 1 and "sodium" in path.casefold()
              and path.casefold().endswith(".dll")]
    forbidden_suffixes = (".pdb", ".lib", ".exp", ".ilk", ".obj", ".pem", ".key", ".pfx")
    if (not required_payload.issubset(declared) or len(sodium) != 1
            or len(set(folded)) != len(folded)
            or any(path.casefold().endswith(forbidden_suffixes) for path in declared)
            or any(Path(path).name.casefold() == "chatserver.exe" for path in declared)):
        raise ManifestError("Windows unsigned artifact payload policy rejected the inventory")

    installer = manifest.get("installer")
    installer_path, installer_digest, installer_size = safe_entry(
        installer, INSTALLER_KEYS, "installer/")
    expected_installer = f"installer/ChatRoom-{version}-unsigned-verification-Setup.exe"
    if (installer_path != expected_installer or installer.get("format") != "nsis"
            or installer.get("signatureStatus") != "unsigned-verification-only"):
        raise ManifestError("Windows unsigned installer identity is invalid")
    declared[installer_path] = (installer_digest, installer_size)

    parity = manifest.get("cmakePayloadParity")
    parity_path, parity_digest, parity_size = safe_entry(parity, PARITY_KEYS, "")
    if parity_path != "cmake-payload-parity.json" or parity.get("runtimeBytesEquivalent") is not True:
        raise ManifestError("Windows CMake parity evidence identity is invalid")
    declared[parity_path] = (parity_digest, parity_size)

    trust = manifest.get("productUpdateTrust")
    trust_enabled = trust is not None
    if require_product_update_trust and not trust_enabled:
        raise ManifestError("Windows unsigned artifact lacks required product update trust")
    if trust_enabled:
        if not isinstance(trust, dict) or set(trust) != TRUST_KEYS:
            raise ManifestError("Windows product update trust metadata is malformed")
        entries = {}
        for field in ("intent", "diagnostic", "evidence", "primaryPublicKey"):
            entries[field] = safe_entry(trust.get(field), FILE_KEYS, "product-update-")
        secondary_entry = trust.get("secondaryPublicKey")
        if secondary_entry is not None:
            entries["secondaryPublicKey"] = safe_entry(
                secondary_entry, FILE_KEYS, "product-update-")
        expected_paths = {
            "intent": "product-update-trust-intent.json",
            "diagnostic": "product-update-trust-diagnostic.json",
            "evidence": "product-update-trust-evidence.json",
            "primaryPublicKey": "product-update-primary-public.pem",
            "secondaryPublicKey": "product-update-secondary-public.pem",
        }
        for field, (path, digest, size) in entries.items():
            if path != expected_paths[field] or path in declared:
                raise ManifestError("Windows product update trust file identity is invalid")
            declared[path] = (digest, size)
        expected_metadata, _ = product_trust_bundle(
            root / "client/ChatClient.exe", version_file, source_revision,
            root / expected_paths["intent"], root / expected_paths["diagnostic"],
            root / expected_paths["evidence"],
            root / expected_paths["primaryPublicKey"],
            (root / expected_paths["secondaryPublicKey"]
             if secondary_entry is not None else None),
        )
        if trust != expected_metadata:
            raise ManifestError("Windows product update trust metadata changed")

    actual = set()
    for path in root.rglob("*"):
        if path.is_symlink():
            raise ManifestError("Windows unsigned artifact contains a symbolic link")
        if path.is_dir():
            continue
        if not path.is_file():
            raise ManifestError("Windows unsigned artifact contains a non-regular entry")
        actual.add(path.relative_to(root).as_posix())
    expected = set(declared) | {MANIFEST, CHECKSUMS}
    if actual != expected:
        raise ManifestError("Windows unsigned artifact has undeclared or missing files")

    recorded_checksums = checksums(root / CHECKSUMS)
    if set(recorded_checksums) != set(declared):
        raise ManifestError("Windows unsigned artifact checksum paths do not match")
    for relative, (expected_digest, expected_size) in declared.items():
        digest, size = sha256_file(root / relative)
        if (digest != expected_digest or size != expected_size
                or recorded_checksums[relative] != digest):
            raise ManifestError("Windows unsigned artifact final bytes changed")
    return {
        "version": version,
        "sourceRevision": source_revision,
        "buildSystem": "cmake",
        "files": len(declared),
        "productUpdateTrust": trust_enabled,
        "verificationStatus": "unsigned-artifact-verified-for-protected-signing",
    }


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--artifact-root", type=Path, required=True)
    parser.add_argument("--version-file", type=Path, required=True)
    parser.add_argument("--source-revision", required=True)
    parser.add_argument("--qt-version", required=True)
    parser.add_argument("--require-product-update-trust", action="store_true")
    args = parser.parse_args()
    try:
        result = verify(
            args.artifact_root, args.version_file, args.source_revision,
            args.qt_version, args.require_product_update_trust)
    except (ManifestError, OSError) as error:
        raise SystemExit(f"Windows unsigned artifact verification failed: {error}") from None
    print(json.dumps(result, ensure_ascii=True, sort_keys=True))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
