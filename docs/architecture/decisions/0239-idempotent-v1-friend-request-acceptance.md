# ADR-0239: Define Recipient-Bound V1 Friend-Request Acceptance

- Status: Accepted
- Date: 2026-08-13
- Related milestone: M3

## Decision

Define a transport-independent V1 acceptance use case that accepts only a
positive 32-bit-compatible legacy request ID and always supplies the recipient
UUID from authenticated server connection state. The persistence boundary must
atomically terminate the request and establish canonical friendship as a DIRECT
conversation with active memberships and one V1 FRIENDSHIP mapping.

The result distinguishes first acceptance, exact duplicate, and generic
rejection. Accepted results retain the requester UUID only as internal routing
context for the existing online notification; it is never a wire field. An
exact retry by the same recipient returns V1-compatible success but must not
repeat the requester notification. Missing mappings, wrong recipient, another
terminal request state, or incomplete conversation/mapping state fail closed.
An impossible self-request result is rejected at the application boundary.
This slice adds no database adapter or route.

## Verification

Application tests prove authenticated recipient propagation, invalid-ID denial
before persistence, internal requester routing context, and self-result failure.
