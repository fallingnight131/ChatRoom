# ADR-0280: Define Idempotent V1 Room Creation

- Status: Accepted
- Date: 2026-08-13
- Related milestone: M3

## Decision

Define transport-independent V1 room creation from a server-bound authenticated
actor, the bounded outer-envelope request ID, a trimmed title of 1–100 Unicode
code points without control characters, and an optional password of 4–1024
valid UTF-8 code points. The command owns copied password bytes and destroys
them on every success, rejection, and exception path.

Hash an accepted password through an application output port before persistence;
the persistence intent contains only the slow encoded result plus the
server-keyed stable idempotency tag defined by ADR-0281, never plaintext or an
unkeyed fast password digest.
The future PostgreSQL adapter must atomically create one GROUP conversation,
one active OWNER membership for the actor, and one positive V1 ROOM mapping.
Treat `(actor_account_id, client_request_id)` as the idempotency scope: exact
retry returns the same canonical conversation and V1 room ID with
`duplicate=true`; conflicting reuse is a typed rejection. Runtime V1 room IDs
must use a collision-safe allocator that coexists with imported IDs.

Persistence results carry canonical creator identity only for server-side
substitution checks; a future V1 codec must not expose UUIDs. Missing/disabled/
unmapped actors share one creation denial. This slice adds no schema, adapter,
handler, password verification, membership notification, or product route.
Rollback removes the unused application boundary.

## Verification

Application tests prove actor and request-ID propagation, title normalization,
password hashing before persistence, no plaintext persistence intent, secret
closure on success/rejection/exception, invalid-input short circuit, and
persistence identity-substitution rejection.
