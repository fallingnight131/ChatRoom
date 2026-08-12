#!/usr/bin/env python3
"""Observe an HTTPS Web release and verify its bound response contract."""

from __future__ import annotations

import argparse
import hashlib
import json
import ssl
from datetime import datetime, timezone
from pathlib import Path
from urllib.error import HTTPError, URLError
from urllib.parse import quote, urlsplit, urlunsplit
from urllib.request import HTTPRedirectHandler, HTTPSHandler, Request, build_opener

from artifact_manifest_common import ManifestError, atomic_write
from web_artifact_manifest import read_response_policy
from web_release_store import validate_release


class RejectRedirects(HTTPRedirectHandler):
    def redirect_request(self, request, file_pointer, code, message, headers, new_url):
        raise ManifestError("Web release probe forbids redirects")


def _origin_url(value: str) -> str:
    parsed = urlsplit(value)
    if (parsed.scheme != "https" or not parsed.hostname or parsed.username or parsed.password
            or parsed.path not in {"", "/"} or parsed.query or parsed.fragment):
        raise ManifestError("Web release probe requires one credential-free HTTPS origin")
    return urlunsplit((parsed.scheme, parsed.netloc, "", "", ""))


def _expected_headers(
    policy: dict[str, object],
    version: str,
    revision: str,
    cache_control: str,
) -> dict[str, str]:
    security_headers = policy.get("securityHeaders")
    release_headers = policy.get("releaseIdentityHeaders")
    if not isinstance(security_headers, dict) or not isinstance(release_headers, dict):
        raise ManifestError("Web response policy headers are malformed")
    expected = {str(key): str(value) for key, value in security_headers.items()}
    substitutions = {"version": version, "sourceRevision": revision}
    for key, template in release_headers.items():
        if not isinstance(key, str) or not isinstance(template, str):
            raise ManifestError("Web release identity header is malformed")
        expected[key] = template.format(**substitutions)
    expected["Cache-Control"] = cache_control
    return expected


def _fetch_and_verify(opener, url: str, entry: dict[str, object], expected_headers: dict[str, str]) -> None:
    request = Request(url, headers={"Accept": "*/*", "Accept-Encoding": "identity"}, method="GET")
    try:
        with opener.open(request, timeout=10) as response:
            if response.status != 200 or response.url != url:
                raise ManifestError("Web release response status or URL is unexpected")
            for name, expected in expected_headers.items():
                values = response.headers.get_all(name) or []
                if values != [expected]:
                    raise ManifestError(f"Web release response header mismatch: {name}")
            for forbidden in ("Content-Encoding", "Set-Cookie", "Access-Control-Allow-Origin"):
                if response.headers.get_all(forbidden):
                    raise ManifestError(f"Web static response must not include {forbidden}")

            expected_size = entry.get("size")
            expected_digest = entry.get("sha256")
            if not isinstance(expected_size, int) or not isinstance(expected_digest, str):
                raise ManifestError("Web release manifest entry is malformed")
            body = response.read(expected_size + 1)
            if len(body) != expected_size or hashlib.sha256(body).hexdigest() != expected_digest:
                raise ManifestError("Web release HTTPS bytes do not match the immutable artifact")
    except ManifestError:
        raise
    except (HTTPError, URLError, OSError) as error:
        raise ManifestError("Web release HTTPS request failed") from error


def probe_release(
    base_url: str,
    release_root: Path,
    ca_certificate: Path | None = None,
) -> dict[str, object]:
    origin = _origin_url(base_url)
    identity = validate_release(release_root)
    policy = read_response_policy(release_root / "response-policy.json")
    try:
        manifest = json.loads((release_root / "web-artifact-manifest.json").read_text(encoding="utf-8"))
    except (json.JSONDecodeError, OSError) as error:
        raise ManifestError("Web artifact manifest is unreadable") from error
    entries = manifest.get("files")
    if not isinstance(entries, list) or not entries:
        raise ManifestError("Web release has no HTTPS payload entries")
    cache_policy = policy.get("cacheControl")
    if not isinstance(cache_policy, dict):
        raise ManifestError("Web response cache policy is malformed")

    context = ssl.create_default_context(cafile=str(ca_certificate) if ca_certificate else None)
    context.minimum_version = ssl.TLSVersion.TLSv1_2
    opener = build_opener(RejectRedirects(), HTTPSHandler(context=context))
    observed_paths: list[str] = []
    for entry in entries:
        if not isinstance(entry, dict) or not isinstance(entry.get("path"), str):
            raise ManifestError("Web release manifest entry is malformed")
        artifact_path = str(entry["path"])
        if not artifact_path.startswith("site/"):
            raise ManifestError("Web HTTPS payload path is outside the site root")
        if artifact_path == identity["entrypoint"]:
            expected_cache = cache_policy.get("versionEntrypoint")
        elif artifact_path.startswith("site/assets/"):
            expected_cache = cache_policy.get("hashedAssets")
        else:
            expected_cache = cache_policy.get("other")
        if not isinstance(expected_cache, str) or entry.get("cacheControl") != expected_cache:
            raise ManifestError("Web manifest cache class does not match the bound response policy")
        public_path = artifact_path.removeprefix("site/")
        encoded_path = "/" + "/".join(quote(part, safe="-._~") for part in public_path.split("/"))
        expected_headers = _expected_headers(
            policy,
            str(identity["version"]),
            str(identity["sourceRevision"]),
            expected_cache,
        )
        _fetch_and_verify(opener, origin + encoded_path, entry, expected_headers)
        observed_paths.append(encoded_path)

    return {
        "schemaVersion": 1,
        "status": "healthy",
        "baseUrl": origin,
        "releaseId": identity["releaseId"],
        "version": identity["version"],
        "sourceRevision": identity["sourceRevision"],
        "responsePolicySha256": identity["responsePolicySha256"],
        "observedFileCount": len(observed_paths),
        "observedPaths": sorted(observed_paths),
        "observedAt": datetime.now(timezone.utc).isoformat(),
    }


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--base-url", required=True)
    parser.add_argument("--release-root", type=Path, required=True)
    parser.add_argument("--ca-certificate", type=Path)
    parser.add_argument("--output", type=Path)
    return parser.parse_args()


def main() -> int:
    args = parse_args()
    try:
        evidence = probe_release(args.base_url, args.release_root, args.ca_certificate)
        rendered = json.dumps(evidence, ensure_ascii=True, indent=2, sort_keys=True) + "\n"
        if args.output:
            atomic_write(args.output, rendered)
    except (ManifestError, OSError) as error:
        raise SystemExit(f"Web release probe failed: {error}") from None
    print(rendered, end="")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
