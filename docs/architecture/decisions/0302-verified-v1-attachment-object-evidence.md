# ADR-0302: Bind active V1 attachment import to sealed target-object evidence

- Status: Accepted
- Date: 2026-08-13

## Context

An internally consistent V1 file/message graph still cannot prove that bytes
exist or that a target object is safe to publish. Legacy local paths and COS URLs
are locators, not durable evidence. Active files need exact target-object facts;
cleared files must use the object-free historical state from ADR-0301.

## Decision

Introduce an immutable evidence manifest bound to the exact V1 attachment source
fingerprint. Each active file requires one typed evidence row containing the
canonical server-owned key `attachments/{deterministicAttachmentId}`, validated
MIME, exact V1 byte size, a 32-byte SHA-256, and a seal time no earlier than file
creation. Duplicate, missing, unknown, stale, or mismatched evidence blocks the
entire plan.

Cleared files reject all object evidence and plan as `UNAVAILABLE` using their
durable V1 `cleared_at`. Evidence output has its own deterministic SHA-256
fingerprint. Fixed-code issues contain typed numeric identities but never object
keys, paths, URLs, filenames, hashes, or media types.

The manifest is a capability boundary, not a trust shortcut: a later operational
producer must inspect or copy bytes into the target object store, calculate the
hash, and confirm sealed metadata before constructing it. The target importer
must reverify the source and evidence immediately before commit.

## Consequences

- Active files cannot be imported from path/URL metadata alone.
- A source or evidence change produces a different fingerprint and invalidates
  the previous preview/apply intent.
- PostgreSQL import can consume one normalized plan for both ready objects and
  unavailable history without weakening runtime upload invariants.
