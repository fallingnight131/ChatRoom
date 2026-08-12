#!/usr/bin/env python3
"""Bind three strict HTTPS observations into independently verifiable Web rollback evidence."""

from __future__ import annotations

import argparse
import hashlib
import json
import os
import tempfile
from datetime import datetime
from pathlib import Path

from artifact_manifest_common import ManifestError
from web_release_probe import read_observation


ROLLBACK_KEYS = {
    "schemaVersion", "evidenceType", "status", "baseUrl", "fromReleaseId",
    "toReleaseId", "priorObservationSha256", "currentObservationSha256",
    "restoredObservationSha256", "priorObservedAt", "currentObservedAt",
    "restoredObservedAt",
}


def _digest(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def _time(value: object) -> datetime:
    try:
        parsed = datetime.fromisoformat(str(value))
    except ValueError as error:
        raise ManifestError("Web rollback observation time is malformed") from error
    if parsed.tzinfo is None:
        raise ManifestError("Web rollback observation time must include a timezone")
    return parsed


def build_rollback_evidence(prior_path: Path, current_path: Path, restored_path: Path) -> dict[str, object]:
    prior = read_observation(prior_path)
    current = read_observation(current_path)
    restored = read_observation(restored_path)
    if len({prior["baseUrl"], current["baseUrl"], restored["baseUrl"]}) != 1:
        raise ManifestError("Web rollback observations must use one HTTPS origin")
    if current["releaseId"] == prior["releaseId"]:
        raise ManifestError("Web rollback must move away from a different current release")
    restored_identity = {
        key: restored[key] for key in (
            "releaseId", "version", "sourceRevision", "artifactManifestSha256",
            "responsePolicySha256", "observedFileCount", "observedPaths",
        )
    }
    if any(prior[key] != value for key, value in restored_identity.items()):
        raise ManifestError("Restored Web release does not match the prior verified release")
    prior_time, current_time, restored_time = map(
        _time, (prior["observedAt"], current["observedAt"], restored["observedAt"]),
    )
    if not prior_time < current_time < restored_time:
        raise ManifestError("Web rollback observations are not in prior-current-restored order")
    return {
        "schemaVersion": 1,
        "evidenceType": "web-release-rollback",
        "status": "rollback-observed",
        "baseUrl": prior["baseUrl"],
        "fromReleaseId": current["releaseId"],
        "toReleaseId": prior["releaseId"],
        "priorObservationSha256": _digest(prior_path),
        "currentObservationSha256": _digest(current_path),
        "restoredObservationSha256": _digest(restored_path),
        "priorObservedAt": prior["observedAt"],
        "currentObservedAt": current["observedAt"],
        "restoredObservedAt": restored["observedAt"],
    }


def write_once(path: Path, evidence: dict[str, object]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    rendered = json.dumps(evidence, ensure_ascii=True, indent=2, sort_keys=True) + "\n"
    temporary: Path | None = None
    try:
        with tempfile.NamedTemporaryFile(
            "w", encoding="utf-8", newline="\n", dir=path.parent, delete=False
        ) as stream:
            stream.write(rendered)
            stream.flush()
            os.fsync(stream.fileno())
            temporary = Path(stream.name)
        try:
            os.link(temporary, path)
        except FileExistsError as error:
            raise ManifestError("Web rollback evidence already exists") from error
    finally:
        if temporary is not None:
            temporary.unlink(missing_ok=True)


def verify_rollback_evidence(
    evidence_path: Path, prior_path: Path, current_path: Path, restored_path: Path,
) -> dict[str, object]:
    try:
        recorded = json.loads(evidence_path.read_text(encoding="utf-8"))
    except (json.JSONDecodeError, OSError) as error:
        raise ManifestError("Web rollback evidence is unreadable") from error
    if not isinstance(recorded, dict) or set(recorded) != ROLLBACK_KEYS:
        raise ManifestError("Web rollback evidence has an unsupported shape")
    expected = build_rollback_evidence(prior_path, current_path, restored_path)
    if recorded != expected:
        raise ManifestError("Web rollback evidence does not match its observations")
    return recorded


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("command", choices=("record", "verify"))
    parser.add_argument("--prior", type=Path, required=True)
    parser.add_argument("--current", type=Path, required=True)
    parser.add_argument("--restored", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    return parser.parse_args()


def main() -> int:
    args = parse_args()
    try:
        if args.command == "record":
            evidence = build_rollback_evidence(args.prior, args.current, args.restored)
            write_once(args.output, evidence)
        else:
            evidence = verify_rollback_evidence(
                args.output, args.prior, args.current, args.restored,
            )
    except (ManifestError, OSError) as error:
        raise SystemExit(f"Web rollback evidence failed: {error}") from None
    print(json.dumps(evidence, ensure_ascii=True, sort_keys=True))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
