# ADR-0206: Select Web Preview Releases Independently of Production

- Status: Accepted
- Date: 2026-08-13
- Owners: Web release engineering and operations
- Related milestone: M4
- Extends: ADR-0113, ADR-0205

## Context

Schema-2 promotion needs a real preview origin for B. Reusing the production
active pointer would recreate the pre-approval traffic mutation that ADR-0205
forbids. Copying B into a mutable preview directory would also weaken immutable
artifact verification.

## Decision

- Add a separate `preview-release.json` selector in the same immutable release
  store. It contains complete candidate identity, exact selection time, and the
  fixed purpose `non-production-candidate-preview`.
- Select only an already staged, fully validated release and atomically replace
  only the preview pointer. Re-read and validate it after selection.
- Keep `active-release.json` exclusively production-owned. Preview tests assert
  it is byte/semantically unchanged.
- Require hosting configuration to route a dedicated credential-free HTTPS
  preview origin through this selector while production continues through the
  active selector. Symlink, future-time, shape, identity, and release drift fail.

## Consequences

The same immutable B bytes can be exercised with production-equivalent headers
and application routes without serving production users. The pointer tool does
not configure DNS/TLS/proxy routing or claim the preview origin observed it.

## Verification

- `python3 Tests/web_preview_release_test.py`
- `python3 Tests/web_release_store_test.py`
