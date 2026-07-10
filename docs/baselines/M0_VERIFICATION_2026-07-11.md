# M0 Verification Record — 2026-07-11

This record covers the deterministic SQLite initialization slice. It is not a
performance benchmark.

## Source State

- Branch: `main`
- Fix commit: `238927e`
- Host: Apple Silicon arm64, macOS 26.5.1
- Qt/qmake: Homebrew Qt 6.9.0
- Test dependencies: Qt Core, Qt SQL, SQLite driver

## Reproduction Before the Fix

`DatabaseSchemaTest` built successfully without Qt GUI dependencies and failed
on the clean first initialization with:

```text
table friendships is missing columns: user1_last_read_msg_id, user2_last_read_msg_id
```

This confirmed that the defect was in initialization ordering rather than a
documentation-only discrepancy.

## Result After the Fix

Command:

```bash
python3 tools/verify_m0.py --db-schema
```

Expected result:

```text
PASS: clean and restarted schemas are complete and identical
```

The test verifies:

- all 13 required V1 tables exist;
- migrated columns exist on the first initialization;
- friendship read pointers exist without a second process start;
- `PRAGMA integrity_check` returns `ok`;
- the first-start and simulated-restart schema snapshots are identical.

## CI

The M0 workflow now runs the same test on Ubuntu 24.04 with `qt6-base-dev` and
`libqt6sql6-sqlite`. Full server/client Qt builds remain separate because the
current macOS Qt distribution has an unrelated AGL toolchain incompatibility.

## V1 Network Smoke Result

Command:

```bash
python3 tools/verify_m0.py --v1-smoke
```

Result:

```text
PASS: register, login, room join, message fan-out, history, file metadata,
reconnect, recall, friendship, and direct chat
```

The headless server compiled and linked without QtGui, then the smoke client
completed every flow against a temporary SQLite database and file directory.
Ubuntu CI installs `qt6-websockets-dev` and runs the same command after the
schema regression test.
