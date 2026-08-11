# ADR-0083: Isolated Web V2 Preview Route

- Status: Accepted
- Date: 2026-08-12
- Related milestone: M3

## Context

ADR-0082 composes V2 without starting traffic. The transport and application
boundaries still need an end-to-end browser surface before production migration
can be evaluated. Replacing the V1 login/chat routes would remove the rollback
path and expose incomplete V2 capabilities such as attachments, account
registration, moderation, and read state.

## Decision

- Register `/preview/v2` and its lazy Vue view only in an exact V2 preview build.
  The stable build has neither the route nor its view chunk.
- Keep the V1 login and chat routes unchanged. An enabled preview build shows a
  clearly labelled link back and forth; it does not silently redirect users.
- Let the preview view explicitly start and stop the route-owned connection. It
  subscribes to detached application snapshots and clears in-memory session
  state when leaving the route while retaining the isolated durable cache.
- Permit authentication only after protocol negotiation reaches `connected`.
  Clear the password field before submission and overwrite its transient UTF-8
  byte array immediately after the call.
- Expose the currently implemented V2 vertical slice only: connection status,
  authentication, conversation directory paging, cache-first history, text
  submission, durable acceptance state, and explicit retry of failed messages.
- Label this surface as an engineering preview. Missing V1 capabilities are
  migration blockers rather than implicit fallbacks inside V2 state.

## Consequences

The project now has a manually reachable end-to-end V2 Web surface for
controlled environments without changing default product behavior. Route exit
does not leave an invisible socket running. The preview is intentionally not a
feature-complete replacement and should not be linked from public stable assets.

## Verification

Tests cover detachable snapshot observers, route-owned stop/restart state,
build-gated lazy routing, explicit start, password-byte clearing, conversation
open, text send, retry, and accessibility source invariants. The complete Web
test gate and both default and preview production builds remain required.

## Rollback

Build or deploy without `VITE_CHAT_V2_PREVIEW=true`. The route and view are then
absent, V1 behavior remains unchanged, and isolated V2 browser state may remain
for a later preview or be removed as ordinary site data.
