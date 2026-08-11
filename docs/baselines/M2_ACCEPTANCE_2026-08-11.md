# M2 Acceptance Record — 2026-08-11

## Decision

M2, **Client Data and Experience Foundation**, is complete for the supported
Web and Windows product clients, within the documented V1 compatibility and
M4 distribution boundaries.

The clients now have account-scoped bounded conversation caches, sequence
cursors, drafts, durable optimistic text/emoji outboxes, restartable attachment
commands, cached-first rendering, incremental synchronization, explicit
accepted/read presentation, bounded timelines, and initial accessibility
semantics.

## Reproducible verification

Executed from the repository root on a macOS development host:

```sh
python3 tools/verify_m0.py --web
python3 tools/verify_m0.py --qt
```

Result: **PASS**.

The Web gate installed locked dependencies, ran 45 tests, and produced the Vite
production build. Coverage includes IndexedDB restart round trips, account and
conversation isolation, bounded snapshots/drafts, optimistic reconciliation,
cursor monotonicity, attachment command persistence and recovery, permission
handling, access revocation, virtualization, read watermarks, accessibility
source policy, and credential non-persistence.

The Qt gate compiled the current server and client and passed the transport,
reconnect, message-model, SQLite repository, text outbox, attachment outbox,
conversation synchronization, and V1 history adapter tests. Local-loop
transport tests were run outside the filesystem/network sandbox because they
bind ephemeral localhost ports.

## Exit criteria evidence

- Recent Web and Windows room/direct conversations render from local
  IndexedDB/SQLite snapshots before server history returns, then synchronize
  forward from a monotonic cursor.
- Stable client message IDs reconcile optimistic text, emoji, and attachment
  submissions with authoritative responses without duplicate rows. Failed
  commands remain explicit and retryable.
- Restart tests preserve bounded drafts and unresolved sends. Web attachment
  recovery deliberately preserves intent rather than file bytes; when the
  browser cannot reopen an authorized handle, the user must reselect the exact
  source revision before a fresh upload starts at byte zero.
- Web rendering windows long histories and Windows uses model/view rendering;
  both retain at most 500 resolved messages per conversation while preserving
  unresolved user work.

## Explicit boundaries carried forward

- `已发送` means durable server acceptance. Private read watermarks are
  available, but device-aware delivery/read aggregation remains M6/V2 work.
- IndexedDB stores zero media bytes. A future thumbnail cache needs an explicit
  global byte budget and eviction policy.
- Browser interaction and native Windows memory measurements remain release
  evidence to collect before making user-visible latency or memory claims.
- Windows clean-host install, signing, upgrade, uninstall, and Web deployment
  rollback are M4 gates; a macOS development build is not Windows support
  evidence.
- Java V2 and PostgreSQL are M3. M2 does not change the authoritative V1 server.

## Known verification notes

The macOS Qt toolchain reports its existing platform-SDK compatibility warnings.
They did not fail compilation or tests. The sandboxed combined run initially
could not bind an ephemeral loopback port; rerunning the Qt gate with local
loopback permission passed. No Windows runtime or installer claim is made here.
