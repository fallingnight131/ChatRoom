# ADR-0236: Make V1 Friend-Request Rejection Recipient-Bound and Idempotent

- Status: Accepted
- Date: 2026-08-13
- Related milestone: M3

## Decision

Define a transport-independent V1 rejection use case that accepts only a
positive signed-integer legacy request ID and always supplies the authenticated
recipient UUID from server connection state. The persistence port returns first
accept, exact duplicate, or generic rejection; transport may expose first and
duplicate as the existing `success=true` without adding a V1 field.

An exact retry of an already REJECTED request for the same recipient is
idempotent. Missing mappings, wrong recipient, or any other terminal state are
generic rejection. This slice adds no database adapter or route.

## Verification

Application tests prove server-bound recipient propagation and invalid-ID
rejection before persistence.
