# ADR-0221: Bound IndexedDB Upgrade Blocking

- Status: Accepted
- Date: 2026-08-13
- Owners: Web client and quality
- Related milestone: M4
- Supersedes: ADR-0220 evidence schema 7

## Context

IndexedDB version changes wait while another browser tab retains an older
database connection. The Web cache previously left its open promise pending
without a bound, which could indefinitely delay drafts, conversation snapshots,
or attachment commands. A compiled bundle and a successful single-tab migration
do not cover this normal browser lifecycle.

## Decision

- Reject an IndexedDB open attempt as soon as the browser reports it blocked,
  reset the cached promise, and let the affected feature degrade through its
  existing cache error boundary rather than waiting indefinitely.
- Close any connection delivered after that rejected attempt and install an
  `onversionchange` handler on every successful V1 and V2 connection so current
  clients cooperate with future upgrades.
- Keep retry explicit at the next cache operation; do not spin or reload the
  page automatically.
- Advance branded-browser host evidence to schema 8 and require an isolated
  native schema-1 connection to block login-time cache work. The authenticated
  shell must remain usable, then upgrade to schema 3 after the legacy connection
  closes and the user opens a conversation.

## Consequences

A blocked local cache temporarily loses offline/draft persistence but no longer
hangs the user path. Closing the stale tab or connection allows the next normal
cache operation to recover. The gate does not claim recovery from quota errors,
database corruption, or browser process failure.

## Verification

- `npm test` from `WebClient/`
- `npm run build` from `WebClient/`
- `npm run test:browser` from `WebClient/`
- `python3 Tests/web_browser_host_evidence_test.py`
