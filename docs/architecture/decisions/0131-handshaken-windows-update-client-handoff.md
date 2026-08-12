# ADR-0131: Handshaken Windows Update Client Handoff

- Status: Accepted
- Date: 2026-08-12
- Owners: project maintainers
- Related milestone: M4

## Context

The external helper must run outside the installed program directory so NSIS
can rename that directory. Copying only the helper omits its dynamic Qt Core
dependency. Exiting immediately after `startDetached` can race helper startup:
the parent PID may disappear or be reused before the helper opens it.

The prepared download also has a private `.part` name; the helper command policy
requires an executable path while final trust must still be repeated.

## Decision

- Download to a random owner-only `.exe.part`. Only after background trust and
  complete `PreparedInstaller` evidence, atomically rename it in place to the
  corresponding random `.exe`. Final ADR-0128 verification remains mandatory.
- Add an inactive asynchronous handoff application service. Require the
  installed `ChatRoomUpdateLauncher.exe`, `Qt6Core.dll`, and `ChatClient.exe` to
  be absolute regular non-symlink files from one program directory. Require
  separate existing absolute non-symlink run and result roots.
- In a worker, create a random owner-only `run-<uuid>` outside the program tree,
  copy the helper and matching Qt Core into it, and construct all ADR-0129
  arguments from the complete prepared value and current process PID.
- Create the exact UUID local event before starting the staged helper. Wait at
  most 15 seconds for the helper to signal that it opened the parent process.
  Return `readyToQuit=true` only after that signal. On validation, copy, start,
  or handshake failure, keep the client running and clean the private run
  directory when no process holds it.
- Reject concurrent handoffs. Do not instantiate the service, show consent UI,
  disconnect, or quit in this change.

## Consequences

The future UI receives an explicit safe-to-quit boundary rather than assuming a
detached process started correctly. The helper has its required runtime and does
not hold files in the directory NSIS must swap. A successful handshake is not
installer success; the next launch must consume ADR-0129 result evidence.

Copied run directories can survive a crash or a helper that remains alive after
handshake failure. A later startup-maintenance slice must remove only stale,
owned, non-active run directories after bounded age/process checks.

## Migration and Rollback

No product path or durable schema is active. Rollback removes the handoff and
restores path-only staging naming together; the helper remains harmless if still
packaged. After activation, event/argument/result schema changes require helper
compatibility handling.

## Verification

- real filesystem tests stage exact helper/runtime names outside the install
  directory and validate generated arguments with the helper parser;
- the injected handshake runs off the application thread and alone authorizes
  quit; parallel handoff is refused;
- missing Qt Core fails before platform launch and cleans the created run tree;
- preparation tests require `.exe` activation only after trust;
- full Qt verification compiles the Windows platform path and client Release;
- native successful handshake, normal client exit, signed Setup, result
  consumption, and restart remain M4 product evidence.
