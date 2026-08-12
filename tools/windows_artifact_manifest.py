#!/usr/bin/env python3
"""Create a deterministic manifest for an unsigned Windows client payload."""

from __future__ import annotations

import argparse
import json
import re
from pathlib import Path
from typing import Iterable

from artifact_manifest_common import (
    ManifestError,
    atomic_write,
    payload_files,
    read_version,
    sha256_file,
    validate_revision,
)

QT_VERSION = re.compile(r"^[0-9]+\.[0-9]+\.[0-9]+$")
SHA256 = re.compile(r"^[0-9a-f]{64}$")
PARITY_EXECUTABLES = {"ChatClient.exe", "ChatRoomUpdateLauncher.exe"}


def valid_parity_inventory(value: object) -> bool:
    if not isinstance(value, dict) or not PARITY_EXECUTABLES.issubset(value):
        return False
    if "sqldrivers/qsqlite.dll" not in value:
        return False
    if len({name.casefold() for name in value}) != len(value):
        return False
    sodium = [name for name in value
              if "/" not in name and "sodium" in name.casefold()
              and name.casefold().endswith(".dll")]
    if len(sodium) != 1:
        return False
    for name, entry in value.items():
        if (not isinstance(name, str) or not isinstance(entry, dict)
                or set(entry) != {"size", "sha256"}
                or not isinstance(entry["size"], int) or entry["size"] <= 0
                or not isinstance(entry["sha256"], str)
                or not SHA256.fullmatch(entry["sha256"])):
            return False
    return True


def build_manifest(
    payload_root: Path,
    version_file: Path,
    source_revision: str,
    qt_version: str,
    installer: Path | None = None,
    cmake_payload_parity: Path | None = None,
    build_system: str = "cmake",
) -> tuple[dict[str, object], list[str]]:
    validate_revision(source_revision)
    if not QT_VERSION.fullmatch(qt_version):
        raise ManifestError("Qt version must use major.minor.patch")
    if build_system not in {"cmake", "qmake"}:
        raise ManifestError("Windows artifact build system is invalid")
    version = read_version(version_file)

    entries: list[dict[str, object]] = []
    checksum_lines: list[str] = []
    for path in payload_files(payload_root):
        relative = path.relative_to(payload_root).as_posix()
        digest, size = sha256_file(path)
        artifact_path = f"client/{relative}"
        entries.append({"path": artifact_path, "sha256": digest, "size": size})
        checksum_lines.append(f"{digest}  {artifact_path}")

    manifest: dict[str, object] = {
        "schemaVersion": 3,
        "product": "chat-room-windows-client",
        "version": version,
        "channel": "verification",
        "platform": "windows",
        "architecture": "x86_64",
        "toolchain": "msvc2022",
        "qtVersion": qt_version,
        "sourceRevision": source_revision,
        "buildSystem": build_system,
        "signatureStatus": "unsigned-verification-only",
        "files": entries,
    }
    if installer is not None:
        expected_name = f"ChatRoom-{version}-unsigned-verification-Setup.exe"
        if installer.is_symlink() or not installer.is_file() or installer.name != expected_name:
            raise ManifestError("installer path or name is invalid")
        digest, size = sha256_file(installer)
        artifact_path = f"installer/{installer.name}"
        manifest["installer"] = {
            "path": artifact_path,
            "format": "nsis",
            "sha256": digest,
            "size": size,
            "signatureStatus": "unsigned-verification-only",
        }
        checksum_lines.append(f"{digest}  {artifact_path}")
    if cmake_payload_parity is not None:
        if (cmake_payload_parity.is_symlink()
                or not cmake_payload_parity.is_file()
                or cmake_payload_parity.name != "cmake-payload-parity.json"):
            raise ManifestError("CMake payload parity evidence path is invalid")
        try:
            evidence = json.loads(cmake_payload_parity.read_text(encoding="utf-8"))
        except (UnicodeError, json.JSONDecodeError) as error:
            raise ManifestError("CMake payload parity evidence JSON is invalid") from error
        expected_keys = {
            "schemaVersion", "version", "sourceRevision",
            "baselineBuildSystem", "candidateBuildSystem",
            "runtimeBytesEquivalent", "executableByteDifferencesAllowed",
            "baseline", "candidate",
        }
        if (not isinstance(evidence, dict) or set(evidence) != expected_keys
                or evidence.get("schemaVersion") != 1
                or evidence.get("version") != version
                or evidence.get("sourceRevision") != source_revision
                or evidence.get("baselineBuildSystem") != "qmake"
                or evidence.get("candidateBuildSystem") != "cmake"
                or evidence.get("runtimeBytesEquivalent") is not True
                or evidence.get("executableByteDifferencesAllowed")
                    != ["ChatClient.exe", "ChatRoomUpdateLauncher.exe"]
                or not valid_parity_inventory(evidence.get("baseline"))
                or not valid_parity_inventory(evidence.get("candidate"))
                or set(evidence["baseline"]) != set(evidence["candidate"])):
            raise ManifestError("CMake payload parity evidence policy rejected the record")
        for name in set(evidence["baseline"]) - PARITY_EXECUTABLES:
            if evidence["baseline"][name] != evidence["candidate"][name]:
                raise ManifestError("CMake payload parity runtime evidence differs")
        canonical_inventory = {
            entry["path"].removeprefix("client/"): {
                "size": entry["size"], "sha256": entry["sha256"]}
            for entry in entries
        }
        if evidence["candidate"] != canonical_inventory:
            raise ManifestError("canonical payload does not match CMake parity evidence")
        digest, size = sha256_file(cmake_payload_parity)
        artifact_path = "cmake-payload-parity.json"
        manifest["cmakePayloadParity"] = {
            "path": artifact_path,
            "sha256": digest,
            "size": size,
            "runtimeBytesEquivalent": True,
        }
        checksum_lines.append(f"{digest}  {artifact_path}")
    return manifest, checksum_lines


def write_manifest(output_dir: Path, manifest: dict[str, object], checksums: Iterable[str]) -> None:
    atomic_write(
        output_dir / "artifact-manifest.json",
        json.dumps(manifest, ensure_ascii=True, indent=2, sort_keys=True) + "\n",
    )
    atomic_write(output_dir / "SHA256SUMS", "\n".join(checksums) + "\n")


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--payload-root", type=Path, required=True)
    parser.add_argument("--version-file", type=Path, required=True)
    parser.add_argument("--source-revision", required=True)
    parser.add_argument("--qt-version", required=True)
    parser.add_argument("--installer", type=Path)
    parser.add_argument("--cmake-payload-parity", type=Path)
    parser.add_argument("--build-system", choices=("cmake", "qmake"), required=True)
    parser.add_argument("--output-dir", type=Path, required=True)
    return parser.parse_args()


def main() -> int:
    args = parse_args()
    try:
        manifest, checksums = build_manifest(
            args.payload_root,
            args.version_file,
            args.source_revision,
            args.qt_version,
            args.installer,
            args.cmake_payload_parity,
            args.build_system,
        )
        write_manifest(args.output_dir, manifest, checksums)
    except (ManifestError, OSError) as error:
        raise SystemExit(f"windows artifact manifest failed: {error}") from None
    print(
        "windows artifact manifest: "
        f"version={manifest['version']} files={len(manifest['files'])} "
        "signature=unsigned-verification-only"
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
