# ADR-0220: Run the V1 Cache Migration in Every Branded Browser Slot

- Status: Superseded by ADR-0221
- Date: 2026-08-13
- Owners: Web client and quality
- Related milestone: M4
- Supersedes: ADR-0219 evidence schema 6

## Context

Node tests use a deterministic IndexedDB substitute and prove sanitization
logic, but they cannot prove a real browser's version-change transaction,
cursor updates, object-store creation, or structured-clone behavior. The
supported Web release must not retain legacy media bytes or temporary secrets.

## Decision

- Advance branded-browser host evidence to schema 7.
- Before login, create a native `chat-room-client` schema-1 database with one
  account/room record containing 510 messages, oversized draft, Base64 media,
  a temporary URL, and a token.
- Trigger the normal production login and directory path, which opens the real
  cache at schema 3. Require the attachment-command store, the newest 500
  messages, a 10,000-character draft, and absence of every seeded media/secret
  field in the persisted record.
- Add mandatory `nativeIndexedDbMigration` to every branded host record. The
  fixture uses an isolated browser context and synthetic non-secret values.

## Consequences

Browser releases now exercise the actual V1 upgrade transaction instead of
relying only on a fake database. This covers schema 1 to 3; future schema
versions and failure/blocked-upgrade UX need their own migration cases.

## Verification

- `npm run test:browser` from `WebClient/`
- `python3 Tests/web_browser_host_evidence_test.py`
- `python3 Tests/web_browser_matrix_completion_test.py`
