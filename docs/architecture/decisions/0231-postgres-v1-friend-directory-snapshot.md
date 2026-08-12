# ADR-0231: Read V1 Friend State from One PostgreSQL Snapshot

- Status: Accepted
- Date: 2026-08-13
- Related milestone: M3

## Decision

Implement the durable V1 friend-directory port as one bounded, read-only,
repeatable-read PostgreSQL transaction. It selects only active DIRECT
memberships with enabled owner/peer accounts, counts message rows after the
caller's canonical read sequence, translates the peer read sequence back to the
largest imported V1 message ID, and counts incoming PENDING contact requests.

Read at most 1,001 rows for the application's 1,000-friend bound and fail rather
than truncate. Batch V1 account mapping uses one array query and omits disabled
or unmapped accounts so the application service can reject incomplete output.
Presence remains outside PostgreSQL and no route is activated.

## Verification

Disposable PostgreSQL tests cover migrated schema, enabled account filtering,
two unread messages, peer read-watermark translation, pending count, batch
mapping completeness, and invalid bounds.
