# ADR-0208: Bind Branded Web Browser Evidence to an Immutable Candidate

- Status: Accepted
- Date: 2026-08-13
- Owners: Web release engineering and quality
- Related milestone: M4
- Extends: ADR-0009, ADR-0205, ADR-0207

## Context

The local Playwright gate exercises pinned Chromium and Firefox engines. It does
not prove that current and previous stable branded Chrome, Edge, and Firefox
binaries can run the exact release candidate. Browser family names or user-agent
strings alone are also insufficient because they can silently select a different
binary or candidate.

## Decision

- Define six exact support slots: current and previous Chrome, Edge, and Firefox.
- Require each host record to bind the immutable Web release manifest, release
  ID, actual browser version, browser executable SHA-256, brand, slot, platform,
  architecture, user agent, and a fresh UTC observation.
- Require explicit success for the production login surface, required Web APIs,
  IndexedDB, endpoint isolation, responsive login layout, and page-error check.
- Supply the expected version and executable digest from a separately reviewed
  release workflow. A generic Playwright engine or an unreviewed runner binary
  cannot satisfy a branded slot.
- Keep authenticated, reconnect/offline, accessibility, and media journeys out
  of this smoke contract; later gates must bind those deeper results explicitly.

## Consequences

The repository has a strict per-host evidence boundary, but no branded-browser
support claim exists until all six real runner records are produced and closed
against one candidate. Browser versions are intentionally not frozen forever in
the repository; release approval must identify the exact current/previous
binary versions and hashes being accepted.

## Verification

- `python3 Tests/web_browser_host_evidence_test.py`
- `packaging/web/browser-support-policy.json`
