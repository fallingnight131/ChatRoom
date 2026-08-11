# ADR-0080: Memory-only Web Session Auto-resume

- Status: Accepted
- Date: 2026-08-12
- Related milestone: M3

## Context

ADR-0076 supports explicit proof rotation, but ADR-0075 creates a fresh protocol
client after every transient disconnect. Requiring another password or manual
resume each time would make normal network changes user-hostile. Persisting the
bearer-like proof across reloads would materially increase browser secret
exposure and needs a separate threat model.

## Decision

- After `SessionEstablished`, the WebSocket transport copies the 32-byte rotated
  proof into transport-owned page memory. Clear the previous proof before every
  replacement.
- On a later connection, complete `ClientHello` first and automatically submit
  the retained session UUID/proof. Use the existing authentication deadline,
  generic rejection, and server-side atomic rotation semantics.
- Clear transport-owned proof bytes on rejection and explicit `stop`/logout.
  Keep them across unexpected socket closure only. Never write them to
  IndexedDB, LocalStorage, SessionStorage, URLs, logs, or application snapshots.
- Redact proof bytes from the transport's observable session-established event;
  application and view observers receive identity/session metadata only.
- When the same account/session resumes, preserve the active cached conversation
  and request missing history from its last contiguous cursor. A genuinely new
  account/session clears all prior in-memory conversation state.
- Do not automatically replay unresolved sends. History reconciliation must run
  first; replay policy remains a later application slice.

## Consequences

Transient network loss can recover without password replay or browser-persistent
session secrets. Reloading the page still requires explicit login/resume input.
The transport becomes the sole owner of the live proof and must remain excluded
from diagnostic serialization.

## Verification

Deterministic transport tests authenticate, disconnect, auto-resume with the
first proof, accept a rotated proof, disconnect again, prove the rotated proof is
used, reject it, and prove no third replay occurs. Observer tests see zero proof
bytes. Application tests preserve same-session selection/cursor state and clear
it after rejection. The complete Web test/build gate remains required.

## Rollback

Remove transport proof ownership and automatic submission. Explicit resume and
fresh login remain available; stored browser data is unaffected because this
decision creates no persistence.
