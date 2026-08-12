# ADR-0128: Locked Installer Re-verification and Launch

- Status: Accepted
- Date: 2026-08-12
- Owners: project maintainers
- Related milestone: M4

## Context

ADR-0124 returns a verified Setup path, but its verification file handle closes
before a future launcher uses that path. A same-account process could replace
the file between verification and process creation. Treating the earlier
`Ready` result as sufficient would create a time-of-check/time-of-use gap at the
most security-sensitive boundary.

The installer also refuses to run while the client liveness mutex exists, so an
external helper will eventually perform the final operation after normal client
exit. That helper must not implement weaker trust checks.

## Decision

- Add a Windows-only `verifyLaunchAndWait` operation to the installer trust
  boundary. Re-open the exact absolute regular non-reparse file with read sharing
  only, preventing write or delete replacement.
- While that handle remains open, repeat exact signed size/SHA-256, WinTrust
  chain and online revocation policy, validated RFC 3161 counter-signature, and
  signed leaf-certificate SHA-256 thumbprint checks.
- Give the locked handle to WinTrust and keep it open until `CreateProcessW`
  returns. Use the verified path as `lpApplicationName`; construct the command
  line internally and permit only NSIS silent `/S`, not caller-supplied process
  arguments.
- Close the lock only after process creation, wait for the installer with a
  positive bounded timeout, and return exact start/wait/timeout status plus the
  installer exit code. Never terminate a timed-out installer.
- Off Windows, report unsupported after the portable integrity check. Keep the
  operation inactive until a separately reviewed external helper and client
  consent/shutdown handoff exist.

## Consequences

The future helper can eliminate replacement between final trust evaluation and
Windows image creation. It cannot use this API to launch arbitrary arguments or
skip Authenticode. The earlier preparation check remains useful for avoiding
untrusted-file UI, but final locked re-verification is authoritative for launch.

Online revocation can still be slow or unavailable; this operation belongs in
the external helper, not the UI thread. Timeout does not imply the installer is
safe to kill. Product behavior must observe and reconcile an indeterminate
result on the next launch.

## Migration and Rollback

No product path or persistent data changes. Rollback removes the launch method
while preserving verification-only behavior. Once activated, weakening the
lock, signature checks, fixed arguments, or wait/result contract requires a new
security ADR and compatible updater rollout.

## Verification

- portable integrity rejection still precedes platform behavior;
- non-Windows development hosts report launch unsupported;
- native Windows tests require an unsigned fixture to be rejected before
  process creation;
- the existing full Qt gate compiles WinTrust/crypt32 code on native Windows;
- acceptance, locked launch, exit-code observation, and timeout behavior for a
  real signed/timestamped Setup remain M4 release evidence.
