# ADR-0219: Gate the Branded Client Journey Under Fixed Response Latency

- Status: Accepted
- Date: 2026-08-13
- Owners: Web client and quality
- Related milestone: M4
- Supersedes: ADR-0218 evidence schema 5

## Context

All existing browser journeys used loopback responses at full speed. Lazy route
chunks and application initialization can fail or race when document, script,
and stylesheet responses arrive later. A deterministic compatibility gate is
needed, but it must not be mislabeled as a bandwidth, loss, or capacity test.

## Decision

- Advance branded-browser host evidence to schema 6.
- In a fresh isolated context, delay every document, script, and stylesheet
  response by 250ms, then run the complete deterministic login, directory,
  offline visibility, and one-time recovery journey against the production
  bundle.
- Require the login and lazy chat shell to become usable without page errors.
- Add mandatory `delayedClientBoot` to all six host records.
- Describe this only as fixed response-latency compatibility. It provides no
  throughput, packet-loss, backend latency, Core Web Vitals, or capacity claim.

## Consequences

The browser matrix now detects timing assumptions in production asset loading
and lazy navigation. True low-bandwidth and lossy-network UX still requires an
owned scenario with measured budgets and backend participation.

## Verification

- `npm run test:browser` from `WebClient/`
- `python3 Tests/web_browser_host_evidence_test.py`
- `python3 Tests/web_browser_matrix_completion_test.py`
