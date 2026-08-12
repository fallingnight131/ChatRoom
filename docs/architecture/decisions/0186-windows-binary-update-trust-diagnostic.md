# ADR-0186: Windows Binary Update-Trust Diagnostic

- Status: Accepted
- Date: 2026-08-12
- Owners: Windows client and release engineering
- Related milestone: M4
- Extends: ADR-0160, ADR-0185

## Context

CMake cache values and an intent record do not prove the final `ChatClient.exe`
contains the reviewed endpoint and public keys. Static string search is also a
weak binary contract. Release automation needs a side-effect-free way to ask the
actual executable for its compiled public trust before packaging and signing.

## Decision

- Add `--chatroom-print-update-trust-json` to the Windows client executable.
- Handle it before `QApplication`, the single-instance lock, local-data paths,
  login UI, update controller construction, or any network operation.
- Serialize the same `WindowsUpdateProductConfiguration::fromBuild()` consumed
  by product behavior into compact schema-1 JSON containing enabled state,
  channel, manifest/signature URLs, sorted key ID/raw-public-key entries, and a
  fail-closed public error string.
- Write once to stdout and exit. Include no private key, PIN, password, token,
  credential, device state, user identity, filesystem path, or network result.
- Include the implementation in both CMake product and qmake fallback graphs.
- Require ordinary native CI to execute the final CMake client and observe
  disabled state with an empty key ring.

## Consequences

Protected release automation can prove the actual binary trust identity and
bind it to ADR-0185 before Authenticode signing. Support tooling may safely use
the diagnostic because it exposes only already-public trust material. The
command is not a runtime override and cannot enable or redirect updates.

## Migration and Rollback

Existing normal launches are unchanged. Removing the diagnostic would require
an equivalent final-binary attestation mechanism before protected product-trust
builds can continue. Ordinary builds must remain demonstrably disabled.

## Verification

- `python3 Tests/windows_update_trust_diagnostic_policy_test.py`
- native Windows CI executes the ordinary final CMake client and requires
  `enabled: false` plus an empty key ring;
- the future protected trust build must require exact intent equality.
