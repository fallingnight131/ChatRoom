# ADR-0049: PostgreSQL Session Proof Rotation

- Status: Accepted
- Date: 2026-08-11
- Related milestone: M3

## Context

V2 issues a one-time raw 32-byte resume token while PostgreSQL stores only its
SHA-256 digest. Reusing a valid token without rotation would make a copied token
replayable until expiry. Rotation must also be correct when two gateways or
threads present the same proof concurrently.

## Decision

- Keep the session UUID stable across resume and replace its token digest on
  every successful proof. Generate a fresh 32-byte `SecureRandom` token, return
  it once, and clear presented/replacement mutable bytes after the transaction.
- In one PostgreSQL transaction, select and lock the session, owning account,
  and device while requiring the presented digest, future expiry, no session or
  device revocation, enabled account, and exact negotiated client-device ID.
- After the lock is acquired, atomically replace `token_sha256`, extend expiry
  from server time, update the device platform/last-seen time, and commit before
  returning the new raw proof.
- Return no row for unknown session, wrong token, expired/revoked session,
  revoked device, disabled account, device mismatch, or replay. The application
  and protocol expose the same generic rejection for every case.
- Rely on PostgreSQL row-lock recheck semantics for concurrent old-proof use:
  after the winning transaction replaces the digest, a waiter using the old
  digest no longer matches and cannot rotate.
- Keep raw proof bytes out of database fields, logs, exceptions, and telemetry.
  A digest collision/SQL failure rolls back and surfaces only as an internal
  dependency failure at the gateway boundary.

## Consequences

- A stolen proof has a single successful-use window; a later replay is denied.
- Resume extends the existing session rather than growing one session row per
  reconnect. Multiple separately issued sessions remain independently revocable.
- The client must durably replace its cached token only after receiving the new
  `SessionEstablished`; interrupted-response recovery policy remains a client
  and multi-device design concern.

## Verification

A disposable PostgreSQL 17 test verifies digest-only storage, successful
rotation, changed raw token and digest, sequential old-proof replay denial,
device mismatch, expiry, device revocation, and exactly one success when two
threads concurrently present the same old proof. The cluster is destroyed after
the gate.

## Rollback

V1 remains authoritative and no V2 gateway route invokes resume yet. Remove the
unused adapter method; no schema rollback is required. Never replace rotation
with reusable plaintext or digest-equivalent bearer values.
