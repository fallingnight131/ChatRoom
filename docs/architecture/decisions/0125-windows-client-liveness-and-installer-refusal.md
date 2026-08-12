# ADR-0125: Windows Client Liveness and Installer Refusal

- Status: Accepted
- Date: 2026-08-12
- Owners: project maintainers
- Related milestone: M4

## Context

The transactional installer renames the complete active program directory.
Windows cannot reliably rename or remove a directory containing the running
`ChatClient.exe`, so manual or future automatic upgrade while the client is
open could fail after staging and offer poor recovery UX. ADR-0116 explicitly
left running/locked-client handling open.

Process-name scanning is race-prone and can target unrelated applications.
Silently terminating the client risks unsent work and violates user consent.

## Decision

- Let the supported Windows client acquire and retain the session-local named
  mutex `Local\ChatRoom.WindowsClient.Running.v1` for its process lifetime.
  A second client instance detects the same mutex, informs the user, and exits.
- Treat inability to create the Windows liveness mutex as a startup error rather
  than running without the installer safety contract. Kernel cleanup releases
  the mutex after normal exit or crash.
- In NSIS `.onInit`, open the exact mutex before pages, staging, registry, or
  program-directory mutation. If present, close the probe handle, return exit
  code 4, show an instruction for interactive installs, and remain silent for
  `/S` automation.
- Do not kill or request shutdown from the installer. A future consent/launch
  boundary must ask the application to quit normally, wait for process exit,
  then start the already verified Setup.
- Configure native Windows CI to launch the installed client, require it to stay
  alive, run silent upgrade, require exit code 4, and prove process, installed
  executable, and account data remain unchanged before terminating the fixture.

## Consequences

The client is now single-instance within one Windows logon session, and both
manual and future automatic upgrades fail before mutation while that instance
is alive. This avoids the known executable-lock path without hidden process
termination.

A same-user client running in another Windows session is not represented by the
`Local` namespace; normal rename failure/rollback remains the secondary guard.
Other arbitrary locked files, power loss, graceful app shutdown, install launch,
and post-install restart still need separate release behavior and evidence.

## Migration and Rollback

No protocol or account data changes. Rollback removes the guard and installer
probe together; removing only one side breaks the shared contract. After public
release, changing the mutex name requires an overlap strategy so old and new
installers detect all supported running clients.

## Verification

- a portable test locks the exact shared name and native Windows CI exercises
  first-acquire, duplicate refusal, and release/reacquire behavior;
- installer policy tests require the `.onInit` probe before staging and silent
  exit code 4;
- pinned NSIS compiles the guarded installer with warnings as errors on the
  macOS development host;
- Windows CI is configured to verify running-client upgrade refusal without
  process, program, or AppData mutation.
