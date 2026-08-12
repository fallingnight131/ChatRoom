# ADR-0138: Compiled-Trust Windows Update User Flow

- Status: Accepted
- Date: 2026-08-12
- Owners: project maintainers
- Related milestone: M4
- Amends: ADR-0127, ADR-0129, ADR-0131, ADR-0134, ADR-0136

## Context

The client already had fail-closed discovery, replay policy, bounded download,
installer trust, two-phase helper handoff, lifecycle persistence, and startup
reconciliation. None of those pre-launch services were instantiated by the
product, so even a release build with reviewed compiled trust could not check or
install an update. Wiring the chain directly in `main.cpp` would mix security,
presentation, transport, and shutdown decisions and make cancellation cleanup
easy to omit.

## Decision

- Add one Windows update presentation controller that is enabled only when
  ADR-0136 returns a valid compiled channel, canonical HTTPS manifest URL, and
  reviewed public-key ring. Ordinary builds construct no update network or
  install service and show no update menu item.
- After the first successful login, perform one automatic check per process.
  Expose a manual **Help > Check for updates** action only in enabled builds.
- Preserve ADR-0127 ordering: fetch and verify the manifest, accept signed
  eligibility/replay state, download exact bounded Setup bytes, and verify
  Authenticode before offering installation. Show progress and permit the user
  to cancel network preparation. Automatic rejection is logged without an
  intrusive dialog; an explicit manual check receives a friendly result.
- Require explicit, default-No user consent after a prepared installer is
  trusted and before helper staging, lifecycle persistence, commit, shutdown,
  or process launch. Declining or a failed handoff deletes the prepared Setup
  and leaves the chat session running.
- On two-phase handoff success, request the existing chat window's normal quit
  path. That path flushes the current draft, disables reconnect, disconnects the
  server, and exits normally; the helper never terminates the process.
- Derive separate owner-local manifest replay state, lifecycle, results, runs,
  and staging directories from AppLocalData. Derive the installed helper,
  matching Qt Core DLL, and restart executable only from the running program
  directory, never writable settings.
- Keep production values, signed-Setup acceptance, and Windows 10/11 end-to-end
  behavior as release gates. This ADR activates code paths only for explicitly
  configured builds; it is not evidence that a release channel exists.

## Consequences

The Windows release build now has a complete user-reachable update path without
weakening the compiled trust boundary. Users are not asked to approve an
unverified download, and verified executable bytes are not retained after they
decline installation. Normal builds and unsigned verification artifacts remain
network-inactive.

The current schema has no signed release-notes field, so the consent dialog can
show the trusted target version and restart impact but not remote rich text.
Adding release notes later requires a versioned manifest decision and review of
rendering/link safety.

## Migration and Rollback

No chat protocol or account schema changes. Rollback removes the controller and
menu wiring while retaining startup reconciliation for already-pending updates.
A release rollback may also omit `CHAT_UPDATE_ENABLED=1`; this disables new
checks without changing durable evidence from an already committed handoff.

## Verification

- ordinary client compilation keeps the configuration default-off and hides the
  menu action;
- enabled fixture compilation exercises the same controller composition with a
  non-production HTTPS URL and public key;
- runtime-path coverage proves manifest replay state and install lifecycle state
  are distinct owner-local directories;
- existing check, cancellation, trust, coordinator, two-phase handoff, result,
  and startup tests remain mandatory in the full Qt gate;
- native consent, normal disconnect, signed install, and restart remain pending
  Windows release evidence and are not claimed from macOS portability builds.
