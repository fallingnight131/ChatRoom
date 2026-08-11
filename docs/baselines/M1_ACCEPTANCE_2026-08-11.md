# M1 Acceptance Record — 2026-08-11

## Decision

M1, **Secure and Reliable V1**, is complete within the supported Web and Windows
product scope and the documented V1 compatibility boundary.

This milestone establishes server-authoritative authentication/authorization,
bounded transports and inputs, idempotent durable room/direct submission,
stable conversation sequences, replayable recall and administrative deletion,
authorized HTTP attachment transfer for upgraded clients, and active room/direct
recovery after reconnect authentication.

## Reproducible verification

Executed from the repository root on a macOS development host:

```sh
python3 tools/verify_m0.py --db-schema --v1-smoke --web
```

Result: **PASS**.

The verifier checked the 125-message/16-table inventory; Web dependency install,
14 tests, and production build; clean/restarted SQLite schema; Qt transport,
message-reconciliation, and reconnect unit tests; and the complete V1 integration
suite for smoke behavior, authorization, transport/input/authentication limits,
room/direct idempotency and sequence resume, recall replay, administrative
deletion replay, HTTP upload, and server-side forwarding.

An additional current-client portability gate also passed:

```sh
python3 tools/verify_m0.py --qt
```

This compiled the server and Qt client and ran the Qt unit tests on the macOS
development host. It is not evidence of Windows clean-host installation or
runtime support; those product gates remain M4 work on Windows.

## Exit criteria evidence

- Exact retries for room/direct text, emoji, upgraded attachments, recall, and
  administrative deletion are idempotent; conflicting key reuse is rejected.
- Active Web/Windows room and direct views recover bounded missing sequence pages
  after authenticated reconnect. Page/process refresh still starts from server
  history because durable local repositories are M2 work.
- Supported upgraded Web/Windows attachment upload and Windows download paths use
  authorized streaming HTTP. Legacy JSON/Base64 handlers remain only for the
  documented V1 compatibility window and are not the normal upgraded path.
- Authentication, resource authorization, frame/backpressure/input limits,
  abuse controls, attachment ownership/integrity, interruption, restart, and
  negative paths have automated regression coverage.

## Explicit non-goals carried forward

- IndexedDB/SQLite client repositories, durable cursors, outboxes, offline cache,
  and optimistic delivery UI: M2.
- Revocable device sessions, delivered/read acknowledgements, multi-device merge,
  and broader non-message synchronization: later protocol slices, primarily M6.
- TLS edge termination, Windows signed installers/updates, and Web production
  rollout/rollback gates: M4.
- Retiring legacy V1 inline/chunk/forward fallbacks requires an observed
  compatibility window and is not achieved by deleting handlers prematurely.

## Known verification notes

The macOS Qt build reports the documented SDK compatibility, existing deprecated
Qt API, and libsodium deployment-target warnings. They did not fail compilation
or tests. No Windows clean-host install, signature, upgrade, or uninstall claim
is made by this acceptance record.
