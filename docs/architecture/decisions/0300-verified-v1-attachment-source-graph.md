# ADR-0300: Verify the complete V1 file/message graph before object migration

- Status: Accepted
- Date: 2026-08-13

## Context

V028 provides target attachment-message and compatibility identities, but the
V1 source represents one attachment twice: a `files`/`friend_files` row and a
`messages`/`friend_messages` row. Either side may be missing or may disagree on
conversation, uploader, filename, size, cleared state, or reason. Local paths
and legacy COS URLs may also contain host details or transient authorization and
must never become target metadata or safe CLI output.

## Decision

Add a pure deterministic source planner that accepts the two typed V1 file
namespaces and their attachment-message links. It requires exactly one retained
message per file, one typed message identity, matching conversation/uploader and
metadata, supported `file`/`image`/`video` type, a safe bounded basename, a
1..10 GiB size, and consistent created/cleared timestamps.

The source fingerprint includes every row field, including the local locator
and legacy object URL, so either can be reverified for drift. Planned candidates
and fixed-code issues exclude both locators. Canonical conversation, account,
legacy-device, message, and attachment UUIDs are deterministic and separated by
typed source namespaces.

Passing this planner means only that the SQLite graph is internally complete.
It does not claim the bytes exist, identify MIME, prove SHA-256, authorize an
object key, or prove a target object was sealed. A separate evidence capability
must establish those facts before PostgreSQL writes.

## Consequences

- Orphan files, missing file rows, duplicate identities, unsafe names, and
  authority/metadata drift block migration instead of being guessed.
- Source paths and legacy URLs remain re-verifiable input but cannot leak into
  canonical candidates, issue reports, or audit rows.
- Cleared files still need an explicit unavailable-history lifecycle design;
  active files need independently verified target-object evidence.
