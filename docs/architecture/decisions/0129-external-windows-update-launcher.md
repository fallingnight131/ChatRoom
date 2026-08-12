# ADR-0129: External Windows Update Launcher

- Status: Accepted
- Date: 2026-08-12
- Owners: project maintainers
- Related milestone: M4

## Context

NSIS correctly refuses mutation while the client owns its liveness mutex. The
client cannot release that mutex and then safely continue update work in the
same process. Starting Setup before exit races `.onInit`; silently killing the
client risks unsent/local state and violates consent. A helper loaded from the
installed directory would also obstruct the transactional directory swap.

ADR-0128 provides the authoritative locked launch operation, but it needs a
small external process with an explicit handoff and recoverable result.

## Decision

- Build `ChatRoomUpdateLauncher` as a separate Qt Core Windows executable and
  include it in every installer-owned payload. The future client adapter must
  copy it and its required Qt Core runtime to an owner-only update-run directory
  outside the installed program directory before starting it.
- Accept exactly nine option/value pairs: live parent PID, absolute installer,
  signed size/SHA-256/signer thumbprint, absolute restart executable, new result
  file, canonical lowercase request UUID, and its exact local ready-event name.
  Reject duplicates, unknowns, non-regular/symlink executables, stale result
  files, non-lowercase hashes, and values outside signed bounds.
- Open the exact parent process for synchronization, then open and signal the
  UUID-bound ready event. The client must not quit until that handshake succeeds.
  Wait at most two minutes for normal parent exit; never terminate it.
- After exit, invoke ADR-0128. Atomically write an owner-only schema-1 JSON result
  containing request ID, normalized outcome, installer exit code, UTC time, and
  non-secret error. Delete Setup only after trust/start rejection or known
  process exit; retain it on wait timeout/failure because it may still execute.
- Restart the installed client only after installer exit code zero. Do not
  restart on rejection, indeterminate wait, or nonzero installer result.
- Keep product invocation absent until a client consent/shutdown adapter copies
  the helper/runtime, creates the event, waits for readiness, and exits normally.

## Consequences

The updater can cross the process-exit boundary without killing the client or
weakening final trust. A request/result UUID prevents stale evidence from being
mistaken for the current attempt, and result persistence lets the next client
launch explain failure or success.

The helper depends on Qt Core. Copying only its executable would fail outside
the installed directory, so the next adapter must stage the exact helper and Qt
Core runtime together. Real restart, timeout reconciliation, crash recovery,
and successful signed Setup behavior remain M4 evidence.

## Migration and Rollback

The installer now requires the helper in staged payloads, but the client does
not invoke it. Rollback removes the helper build/package requirement and command
test. After activation, command/result schema changes require additive versioning
because old copied helpers may survive a crash.

## Verification

- portable tests accept one exact command and reject malformed, duplicate,
  mismatched UUID, path, bound, and hash inputs;
- full Qt verification builds the helper and all existing targets;
- NSIS requires the helper before program-directory activation;
- native Windows CI is configured to create the ready event, observe handshake,
  release a fixture parent, reject/clean an unsigned Setup copy, and verify the
  atomic request-bound result;
- successful signed install, result consumption, and restart remain future M4
  product gates.
