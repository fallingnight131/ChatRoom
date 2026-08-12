# ADR-0214: Require Accessible Login in Branded Browser Evidence

- Status: Superseded by ADR-0216
- Date: 2026-08-13
- Owners: Web client and quality
- Related milestone: M4
- Supersedes: ADR-0208 evidence schema 1

## Context

Schema 1 proved that login controls rendered and could be filled, but not that a
keyboard user could traverse and submit the form or that validation failure was
announced. Source inspection cannot prove browser focus behavior.

## Decision

- Advance branded-browser host evidence to schema 2.
- On every branded browser slot, focus the labeled user ID input, traverse by
  Tab to password and submit, activate submit with Enter, and require the empty
  form error to appear through an alert role.
- Record `keyboardAccessibleLogin` and `announcedValidationError` as mandatory
  true checks alongside the existing candidate smoke checks.
- Reject schema-1 host records rather than silently treating the new checks as
  optional. Matrix closure continues to revalidate every host record.

## Consequences

Future real six-browser completion includes a minimal runtime keyboard and
screen-reader-semantics gate. It does not replace manual assistive-technology
testing or prove the authenticated chat surface.

## Verification

- `python3 Tests/web_browser_host_evidence_test.py`
- `npm run test:browser` from `WebClient/`
