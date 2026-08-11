# ADR-0076: Explicit Web V2 Session Resume

- Status: Accepted
- Date: 2026-08-12
- Related milestone: M3

## Context

The Java V2 gateway already rotates a valid resume proof atomically, while the
Web protocol and transport could only perform a fresh password login. Automatic
reconnect must not imply password replay, and choosing persistent browser
custody for a bearer-like proof needs a separate threat-model and logout policy.

## Decision

- Let a caller explicitly submit a canonical session UUID and exactly 32 opaque
  proof bytes after V2 negotiation.
- Copy the proof only for immediate Protobuf serialization and clear that owned
  copy. The caller remains responsible for its input buffer.
- Reuse the authentication deadline and generic rejection path. Accept success
  only through the existing session-established validation, replacing the
  in-memory proof with the server-rotated value.
- Do not read, write, or define browser persistence and do not automatically
  replay a proof on reconnect. Those behaviors require a later security decision.

## Consequences

An application orchestrator can exercise gateway session rotation without
retaining a password, while this slice does not silently increase browser secret
exposure. Reconnect remains unauthenticated unless a higher layer explicitly
chooses fresh login or resume.

## Verification

TypeScript tests decode the emitted command, prove the caller buffer is unchanged,
accept a correlated rotated session response, and exercise the transport method
after successful negotiation. The complete Web test/build gate remains required.

## Rollback

Remove the two explicit resume methods and their tests. Fresh authentication,
the live V1 path, and server-side resume support remain unchanged.
