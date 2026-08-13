# ADR-0336: Windows V2 Conversation Directory Protocol

- Status: Accepted
- Date: 2026-08-13
- Owners: project maintainers
- Related milestone: M6

## Context

The Windows reply runtime can open a canonical conversation UUID but the user
has no safe, understandable way to discover one. Asking users to paste internal
identifiers would expose protocol mechanics and would bypass server-authorized
conversation membership. The Java V2 gateway already defines type 110/111
conversation-directory paging used by Web.

## Decision

- Add a transport-independent Windows directory codec over the existing V2
  Protobuf schema. It emits type 110 commands and accepts only correlated type
  111 pages or bounded protocol errors for the authenticated session.
- Use the server's stable descending `(updatedAt, conversationId)` cursor.
  Require 1..100 rows, strictly descending records, exact last-row next cursor,
  and strict advancement beyond the requested cursor.
- Validate canonical conversation IDs, DIRECT/GROUP kind, OWNER/ADMIN/MEMBER
  role, nonblank valid UTF-8 display names bounded by 100 Unicode scalars and
  400 bytes, signed sequence range, read cursor not exceeding latest sequence,
  and positive update time.
- Bound directory concurrency to four requests and clear all correlations when
  the session changes or disconnects. Directory state remains a presentation
  projection; PostgreSQL remains authoritative for membership and ordering.
- Extend the single Windows authenticated WSS router with type 110 commands and
  type 111 responses. No second socket or V1 room-ID inference is allowed.

## Consequences

The Windows product can now build a user-facing conversation selector with the
same authorization and composite paging semantics as Web. The codec does not
yet own a Qt model or persist directory metadata; those belong to the next
product-composition slice.

## Verification

The codec test covers initial and continuation command encoding, Unicode names,
kind/role/read projection, equal-timestamp UUID ordering, exact cursor
projection, rejection without consuming correlation, and disconnect cleanup.
The existing transport test remains green after adding the two routed message
types.

## Rollback

Remove the directory codec, its test target, and type 110/111 transport routing.
The messaging runtime and V1 product path continue unchanged; no durable data
or server migration is involved.
