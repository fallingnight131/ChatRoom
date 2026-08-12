#!/usr/bin/env python3
"""Observe exact Windows update manifest, signature, and Setup bytes over HTTPS."""

from __future__ import annotations

import argparse
import hashlib
import json
import os
import ssl
import tempfile
from datetime import datetime, timezone
from pathlib import Path
from urllib.error import HTTPError, URLError
from urllib.parse import urlsplit, urlunsplit
from urllib.request import HTTPRedirectHandler, HTTPSHandler, Request, build_opener

from artifact_manifest_common import ManifestError, sha256_file
from windows_update_channel_candidate import validate_candidate
from windows_update_channel_store import validate_release_from_candidate
from windows_update_manifest import verify_manifest_signature


OBSERVATION_KEYS = {
    "schemaVersion", "evidenceType", "status", "channel", "version",
    "sourceRevision", "manifestSequence", "signingKeyId", "manifestUrl",
    "signatureUrl", "installerUrl", "manifestSha256", "signatureSha256",
    "installerSha256", "installerSize", "authenticodeSignerSha256", "observedAt",
}
SECURITY_HEADERS = {
    "Strict-Transport-Security": "max-age=31536000; includeSubDomains",
    "X-Content-Type-Options": "nosniff",
}


class RejectRedirects(HTTPRedirectHandler):
    def redirect_request(self, request, file_pointer, code, message, headers, new_url):
        raise ManifestError("Windows update probe forbids redirects")


def _url(value: str, expected_name: str) -> tuple[str, str]:
    parsed = urlsplit(value)
    try:
        parsed.port
    except ValueError as error:
        raise ManifestError("Windows update probe URL is invalid") from error
    if (parsed.scheme != "https" or not parsed.hostname or parsed.username or parsed.password
            or parsed.query or parsed.fragment or not parsed.path.startswith("/")
            or "//" in parsed.path or "%" in parsed.path or "\\" in parsed.path
            or Path(parsed.path).name != expected_name
            or any(ord(character) < 33 or ord(character) > 126 for character in value)):
        raise ManifestError("Windows update probe URL is invalid")
    origin = urlunsplit((parsed.scheme, parsed.netloc, "", "", ""))
    directory = parsed.path.rsplit("/", 1)[0]
    return origin, directory


def _fetch(opener, url: str, expected: bytes, content_type: str, cache_control: str) -> None:
    request = Request(url, headers={"Accept": "*/*", "Accept-Encoding": "identity"}, method="GET")
    try:
        with opener.open(request, timeout=30) as response:
            if response.status != 200 or response.url != url:
                raise ManifestError("Windows update response status or URL is unexpected")
            expected_headers = {
                **SECURITY_HEADERS,
                "Content-Type": content_type,
                "Cache-Control": cache_control,
                "Content-Length": str(len(expected)),
            }
            for name, value in expected_headers.items():
                if (response.headers.get_all(name) or []) != [value]:
                    raise ManifestError(f"Windows update response header mismatch: {name}")
            for forbidden in ("Content-Encoding", "Set-Cookie", "Access-Control-Allow-Origin"):
                if response.headers.get_all(forbidden):
                    raise ManifestError(f"Windows update response must not include {forbidden}")
            body = response.read(len(expected) + 1)
            if body != expected:
                raise ManifestError("Windows update HTTPS bytes do not match candidate")
    except ManifestError:
        raise
    except (HTTPError, URLError, OSError) as error:
        raise ManifestError("Windows update HTTPS request failed") from error


def probe_release(
    manifest_url: str,
    candidate_root: Path,
    version_file: Path,
    source_revision: str,
    channel: str,
    qt_version: str,
    authenticode_signer_sha256: str,
    public_key_file_sha256: str,
    ca_certificate: Path | None = None,
    now_utc: datetime | None = None,
) -> dict[str, object]:
    now = now_utc or datetime.now(timezone.utc).replace(microsecond=0)
    if now.tzinfo != timezone.utc or now.microsecond:
        raise ManifestError("Windows update probe clock must be an exact UTC second")
    identity = validate_candidate(
        candidate_root, version_file, source_revision, channel, qt_version,
        authenticode_signer_sha256, public_key_file_sha256, now,
    )
    manifest_path = candidate_root / "update/manifest.json"
    signature_path = candidate_root / "update/manifest.json.sig"
    public_key = candidate_root / "evidence/update-public-key.pem"
    update = verify_manifest_signature(manifest_path, signature_path, public_key, now)
    installer_path = candidate_root / f"windows/installer/ChatRoom-{identity['version']}-Setup.exe"
    installer_url = str(update["installer"]["url"])
    origin, directory = _url(manifest_url, "manifest.json")
    installer_origin, installer_directory = _url(
        installer_url, f"ChatRoom-{identity['version']}-Setup.exe")
    if origin != installer_origin or directory != installer_directory:
        raise ManifestError("Windows update manifest and installer URLs are not co-located")
    signature_url = f"{origin}{directory}/manifest.json.sig"
    manifest_bytes = manifest_path.read_bytes()
    signature_bytes = signature_path.read_bytes()
    installer_bytes = installer_path.read_bytes()
    context = ssl.create_default_context(cafile=str(ca_certificate) if ca_certificate else None)
    context.minimum_version = ssl.TLSVersion.TLSv1_2
    opener = build_opener(RejectRedirects(), HTTPSHandler(context=context))
    _fetch(opener, manifest_url, manifest_bytes, "application/json", "no-store")
    _fetch(opener, signature_url, signature_bytes, "application/octet-stream", "no-store")
    _fetch(
        opener, installer_url, installer_bytes,
        "application/vnd.microsoft.portable-executable",
        "public, max-age=31536000, immutable",
    )
    manifest_digest, _ = sha256_file(manifest_path)
    signature_digest, _ = sha256_file(signature_path)
    installer_digest, installer_size = sha256_file(installer_path)
    return {
        "schemaVersion": 1,
        "evidenceType": "windows-update-https-observation",
        "status": "healthy",
        "channel": channel,
        "version": identity["version"],
        "sourceRevision": source_revision,
        "manifestSequence": update["manifestSequence"],
        "signingKeyId": update["signingKeyId"],
        "manifestUrl": manifest_url,
        "signatureUrl": signature_url,
        "installerUrl": installer_url,
        "manifestSha256": manifest_digest,
        "signatureSha256": signature_digest,
        "installerSha256": installer_digest,
        "installerSize": installer_size,
        "authenticodeSignerSha256": authenticode_signer_sha256,
        "observedAt": now.strftime("%Y-%m-%dT%H:%M:%SZ"),
    }


def write_observation(path: Path, value: dict[str, object]) -> None:
    if path.exists() or path.is_symlink() or not path.is_absolute():
        raise ManifestError("Windows update observation output is unsafe or already exists")
    path.parent.mkdir(parents=True, exist_ok=True)
    if path.parent.is_symlink() or not path.parent.is_dir():
        raise ManifestError("Windows update observation output directory is unsafe")
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
            raise ManifestError("Windows update observation output already exists") from error
    finally:
        if temporary is not None:
            temporary.unlink(missing_ok=True)


def read_observation(path: Path, candidate_root: Path) -> dict[str, object]:
    if path.is_symlink() or not path.is_file():
        raise ManifestError("Windows update observation must be a regular file")

    def unique(pairs: list[tuple[str, object]]) -> dict[str, object]:
        result: dict[str, object] = {}
        for key, value in pairs:
            if key in result:
                raise ManifestError("Windows update observation has duplicate keys")
            result[key] = value
        return result

    try:
        value = json.loads(path.read_text(encoding="utf-8"), object_pairs_hook=unique)
    except (OSError, UnicodeDecodeError, json.JSONDecodeError) as error:
        raise ManifestError("Windows update observation is unreadable") from error
    if (not isinstance(value, dict) or set(value) != OBSERVATION_KEYS
            or value.get("schemaVersion") != 1
            or value.get("evidenceType") != "windows-update-https-observation"
            or value.get("status") != "healthy"):
        raise ManifestError("Windows update observation has an unsupported shape")
    try:
        observed = datetime.strptime(
            str(value["observedAt"]), "%Y-%m-%dT%H:%M:%SZ").replace(tzinfo=timezone.utc)
    except ValueError as error:
        raise ManifestError("Windows update observation time is invalid") from error
    identity = validate_release_from_candidate(candidate_root, observed)
    update = verify_manifest_signature(
        candidate_root / "update/manifest.json",
        candidate_root / "update/manifest.json.sig",
        candidate_root / "evidence/update-public-key.pem",
        observed,
    )
    manifest_digest, _ = sha256_file(candidate_root / "update/manifest.json")
    signature_digest, _ = sha256_file(candidate_root / "update/manifest.json.sig")
    installer = candidate_root / f"windows/installer/ChatRoom-{value['version']}-Setup.exe"
    installer_digest, installer_size = sha256_file(installer)
    expected_identity = {
        "channel": identity["channel"],
        "version": identity["version"],
        "sourceRevision": identity["sourceRevision"],
        "manifestSequence": identity["manifestSequence"],
        "signingKeyId": update["signingKeyId"],
        "installerUrl": update["installer"]["url"],
        "authenticodeSignerSha256": update["installer"]["authenticodeSha256Thumbprint"],
    }
    if (any(value.get(key) != expected for key, expected in expected_identity.items())
            or value["manifestSha256"] != manifest_digest
            or value["signatureSha256"] != signature_digest
            or value["installerSha256"] != installer_digest
            or value["installerSize"] != installer_size):
        raise ManifestError("Windows update observation does not match candidate bytes")
    origin, directory = _url(str(value["manifestUrl"]), "manifest.json")
    installer_origin, installer_directory = _url(str(value["installerUrl"]), installer.name)
    if (origin != installer_origin or directory != installer_directory
            or value["signatureUrl"] != f"{origin}{directory}/manifest.json.sig"):
        raise ManifestError("Windows update observation URL identity is invalid")
    return value


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--manifest-url", required=True)
    parser.add_argument("--candidate-root", type=Path, required=True)
    parser.add_argument("--version-file", type=Path, required=True)
    parser.add_argument("--source-revision", required=True)
    parser.add_argument("--channel", choices=("stable", "beta"), required=True)
    parser.add_argument("--qt-version", required=True)
    parser.add_argument("--authenticode-signer-sha256", required=True)
    parser.add_argument("--public-key-file-sha256", required=True)
    parser.add_argument("--ca-certificate", type=Path)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    try:
        value = probe_release(
            args.manifest_url, args.candidate_root, args.version_file,
            args.source_revision, args.channel, args.qt_version,
            args.authenticode_signer_sha256, args.public_key_file_sha256,
            args.ca_certificate,
        )
        write_observation(args.output, value)
    except (ManifestError, OSError) as error:
        raise SystemExit(f"Windows update probe failed: {error}") from None
    print(json.dumps(value, ensure_ascii=True, sort_keys=True))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
