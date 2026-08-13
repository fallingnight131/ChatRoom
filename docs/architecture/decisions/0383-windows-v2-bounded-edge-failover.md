# ADR-0383: Windows V2 Bounded Edge Failover

- Status: Accepted
- Date: 2026-08-14
- Owners: project maintainers
- Related milestone: M5

## Context

ADR-0382 gives the Web V2 preview a reviewed endpoint list, while the supported
Windows product still retries one compiled URL. Windows must recover from the
same edge failure without making registry/settings values or an unauthenticated
network response part of the transport trust boundary.

## Decision

- Keep the required compiled Windows V2 primary URL and permit one optional
  compiled fallback URL. Both must be distinct exact
  `wss://authority/v2/windows` values without credentials, query, or fragment.
- Validate the pair during CMake generation and again inside the final binary.
  Expose both through canonical configuration diagnostic schema 2 so protected
  Windows CI can inspect what was actually compiled.
- On socket construction failure or disconnect, advance through the bounded
  pair and retain the existing full-jitter exponential reconnect policy.
- Restart protocol negotiation on every connection and reuse only the current
  memory-held session resume proof. Do not persist or emit that proof.
- Keep the feature default-off and pass the reviewed pair through the existing
  application/controller/transport boundaries. Do not add user-editable
  endpoint selection.

## Consequences

The Windows V2 preview has behavior parity with the dual-edge requirement while
retaining Qt and the existing application layers. A single fallback is enough
for the currently proven two-edge topology and bounds reconnect amplification.

macOS can verify CMake policy and portable configuration logic, but it cannot
claim the Windows transport or product artifact passed. Native Windows Release
CI remains required for the transport test, compiled diagnostic, and final
candidate. This does not prove production certificates, cross-host discovery,
degraded-edge eviction, or Windows 10/11 clean-host support.

## Verification

- Run `python3 Tests/windows_cmake_v2_configuration_test.py` and the portable Qt
  configuration tests.
- On native Windows, build the forwarding-enabled preview gate and run the V2
  transport and configuration tests. Inspect diagnostic schema 2 for the exact
  primary and fallback pair.

## Rollback

Omit `CHATROOM_WINDOWS_V2_FALLBACK_WSS_URL` or ship the prior signed candidate.
The primary-only configuration remains valid, and no protocol or local database
migration is involved.
