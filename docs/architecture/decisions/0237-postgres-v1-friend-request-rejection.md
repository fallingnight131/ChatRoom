# ADR-0237: Serialize Recipient-Authorized V1 Request Rejection

- Status: Accepted
- Date: 2026-08-13
- Related milestone: M3

## Decision

Implement the detached V1 friend-request rejection port with PostgreSQL. Run
each decision in a `SERIALIZABLE` transaction, resolve the positive legacy
request ID through its compatibility mapping, lock the canonical request row,
and require the authenticated account to be its enabled recipient.

Only `PENDING` may transition to `REJECTED`; PostgreSQL supplies `resolved_at`
from the transaction clock. An already `REJECTED` row for the same recipient is
the one accepted duplicate. Missing mappings, wrong recipients, disabled
recipients, accepted requests, and any other state return the same generic
rejection. The adapter exposes no canonical identifier and no route is enabled.

## Verification

Disposable PostgreSQL tests prove wrong-recipient denial, the first transition,
the exact idempotent retry, durable state/time, and disappearance from the
pending projection. The complete PostgreSQL gate covers migration, restart,
constraints, persistence, migration CLI, and real-database gateway tests.
