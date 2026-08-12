# ADR-0164: Explicit Windows Release Installer Mode

- Status: Accepted
- Date: 2026-08-12
- Owners: project maintainers
- Related milestone: M4

## Context

The shared NSIS script previously emitted only an explicitly unsigned
verification filename. A protected workflow could rename that file before
signing, but a rename obscures whether the package was intentionally assembled
as a release and weakens exact-name policy at the signing boundary.

Embedding a signing command through NSIS `!finalize` would couple packaging to
certificate custody and hide a security-sensitive mutation inside compilation.

## Decision

- Add one explicit `RELEASE_BUILD` NSIS preprocessor mode.
- When present, emit exactly `ChatRoom-<version>-Setup.exe`.
- When absent, retain exactly
  `ChatRoom-<version>-unsigned-verification-Setup.exe`.
- Keep all payload, install, upgrade, rollback, running-client, downgrade, and
  uninstall logic shared between both modes.
- Continue prohibiting `!finalize` and `!uninstfinalize`; NSIS never receives a
  certificate, private key, password, token, timestamp URL, or signing command.
- Require the protected workflow to sign client/helper explicitly, compile
  release-mode Setup, and then explicitly sign Setup.

## Consequences

Installer identity records build intent without pretending the unsigned release-
mode bytes are already trusted. Signing remains observable and provider-neutral,
and ordinary CI naming/negative gates do not change.

The release-mode Setup is still untrusted until post-signing evidence verifies
its final Authenticode and timestamp.

## Migration and Rollback

The protected workflow is the only authorized caller of `/DRELEASE_BUILD=1`.
Rollback omits that define and produces only the unsigned verification identity;
no installer behavior or persisted format changes.

## Verification

- require exactly one release and one verification `OutFile` branch;
- require release mode to use the canonical candidate filename;
- retain installer policy tests and ordinary native CI output expectations;
- reject any NSIS finalize signing hook.
