# ADR-0331: Windows V2 Messaging Protocol Boundary

- Status: Accepted
- Date: 2026-08-13
- Owners: project maintainers
- Related milestone: M6

## Context

The supported Windows client still uses the V1 JSON chat path. Its default-off
V2 WSS composition currently owns session and device management only. Adding
reply controls directly to the V1 path would silently lose the distinct type-105
semantics, while coupling generated Protobuf objects to Widgets or SQLite would
make reconnect, caching, and presentation behavior difficult to test separately.

## Decision

- Add a transport-independent C++ Windows messaging protocol client over the
  reviewed generated V2 bindings. It owns authenticated session binding,
  request/client-message correlation, bounded pending commands, and exact wire
  encoding for text submit, reply submit, and sequence-based history reads.
- Decode server acceptance, history, and live publication into owned C++ value
  objects. Reply projections contain only authoritative target message ID,
  target sequence, and target sender account ID; no copied quote body enters the
  protocol boundary.
- Validate canonical durable UUIDs, bounded UTF-8 identifiers/content, signed
  server sequence ranges, positive server timestamps, ordered history and
  mutation cursors, and reply targets that precede their reply. Unknown,
  mismatched, stale, or malformed responses fail without consuming the pending
  legitimate request.
- Abandon all pending state when the authenticated session changes or
  disconnects. Retry policy and durable optimistic state belong to the later
  application/local-repository slice, not this in-memory codec.
- Keep the client detached from the product transport. No type-105 command is
  sent until the Windows V2 local repository, application service, UI, and
  default-off product composition are complete.

## Consequences

Windows can build reply composition on a strict V2 boundary without changing or
reinterpreting the V1 JSON contract. The isolated codec is reusable by the Qt
WSS adapter and testable on the macOS development host, but this does not count
as Windows product enablement or Windows release evidence.

The initial projection exposes text messages and validates ordered mutation
entries but leaves recall/deletion application to the synchronization slice.

## Verification

The C++ protocol test covers distinct type-105 encoding, stable client-message
identity, acceptance reconciliation, authoritative reply references, live
publication, mutation-only cursor advancement, invalid UTF-8, non-preceding
reply rejection, cross-message ACK denial, correlated opaque errors, and
disconnect abandonment. The target is part of the pinned M0 three-language
protocol gate.

## Rollback

Remove the detached client and its isolated test target. No product wiring,
database, V1 protocol, or server state changes require rollback.
