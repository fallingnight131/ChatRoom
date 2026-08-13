# ADR-0338: Windows V2 Conversation Product Surface

- Status: Accepted
- Date: 2026-08-13
- Owners: project maintainers
- Related milestone: M6

## Context

The Windows V2 runtime can authenticate, list authorized conversations, hydrate
SQLite, synchronize ordered history, and submit idempotent replies, but those
capabilities are not a product path until a user can discover and operate them
without entering internal UUIDs. The V1 Widgets chat must remain the default
while V2 is still a reviewed preview.

## Decision

- Compile the existing reply panel and a new conversation-directory dialog into
  the canonical Windows CMake product only. Keep the qmake rollback V1-only.
- Forward messaging readiness through the product controller. Keep the settings
  action hidden until the default-off V2 preview authenticates successfully;
  after that first success, keep cached conversations reachable during a
  disconnect while the directory reports reconnect state.
- Render only validated server-projected names, kind, role, and unread count.
  Store the canonical conversation UUID as non-editable item data and open it
  from selection, so the user never types or translates protocol identities.
- Reuse the account-isolated SQLite-backed messaging ViewModel for cached-first
  history, optimistic/retry state, reply selection, cancellation, and quote
  rendering. Keep the message surface disabled until an authorized conversation
  has opened.
- Use native Qt list, button, splitter, focus, and accessible-name semantics.
  Expose refresh and exact-cursor continuation explicitly and retain visible
  reconnect/failure state.
- Close V2 dialogs before the product controller is stopped on logout, so no
  widget retains a pointer to destroyed runtime state.

## Consequences

The supported Windows product now has a complete, user-reachable default-off V2
reply slice alongside the Web preview. This is implementation evidence, not a
release or clean-host support claim; Windows installer/signature/upgrade gates
remain governed by M4.

The surface remains a focused dialog rather than a second copy of the large V1
`ChatWindow` layout. A later cutover can promote these application/ViewModel
boundaries into the main shell without changing protocol or SQLite ownership.

## Verification

The offscreen Widgets regression proves accessible controls, user-facing
directory/unread rendering, selection by hidden authorized identity,
cached-message projection, and refresh/continuation behavior. The product
controller regression proves readiness/unavailability forwarding across
authenticate and stop. Protocol integration tests continue to prove WSS routing,
SQLite reconciliation, and reply semantics.

## Rollback

Remove the V2 settings action and conversation dialog sources from `ChatClient`.
The default-off controller, protocol, and local database remain dormant, and the
V1 product path is unchanged.
