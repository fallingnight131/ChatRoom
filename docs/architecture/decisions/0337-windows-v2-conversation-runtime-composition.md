# ADR-0337: Windows V2 Conversation Runtime Composition

- Status: Accepted
- Date: 2026-08-13
- Owners: project maintainers
- Related milestone: M6

## Context

The detached directory codec alone does not make conversations discoverable in
the supported Windows product. The existing product controller already owns the
single V2 transport and device-management lifecycle; creating a parallel owner
would duplicate authentication and reconnect policy.

## Decision

- Make the product controller compose one messaging runtime beside device
  management over its existing transport. After session establishment, request
  both the device directory and the first conversation-directory page.
- Add a Qt directory ViewModel that exposes display name, direct/group label,
  role label, and bounded unread count. It owns loading/failure/has-more view
  state but no authoritative membership or sequence decisions.
- Route directory pages and correlated directory protocol errors to the
  directory codec before ordinary messaging decoding. Convert only validated
  records into Qt rows and open messages using the server-authorized canonical
  conversation ID hidden behind the selected row.
- Refresh replaces the directory projection; continuation appends unique rows
  and uses the exact validated server cursor. Only one UI directory request can
  be in flight; all volatile request/cursor state is abandoned on disconnect.
- Use the account-isolated SQLite repository in production and inject it in
  tests. Failure to initialize durable messaging state rejects the V2 session
  instead of exposing a partially functional authenticated product.
- Move the device controller out of the low-level transport library into the
  higher product-composition library so dependencies remain
  `product -> transport`, never circular.

## Consequences

The real default-off Windows V2 product object now exposes both a conversation
directory ViewModel and the selected conversation's message ViewModel. No
mapping from V1 usernames/room integers to V2 UUIDs is invented.

The final user-visible step still needs a Windows Widgets surface that binds
the directory and message ViewModels and is reachable from `ChatWindow` only
when the compiled V2 product gate authenticates successfully.

## Verification

The integration tests authenticate the product transport, prove simultaneous
device and conversation requests, project a named unread conversation, select
it without exposing UUID entry, request history from durable cursor zero,
commit the response, refresh messages, and clear volatile state on stop. The
transport and standalone messaging-controller tests remain separate regression
gates.

## Rollback

Remove the messaging controller member/accessors from the product controller
and restore the device controller to the transport target. Detached directory,
messaging, and local-data layers remain available; no server or durable schema
rollback is needed.
