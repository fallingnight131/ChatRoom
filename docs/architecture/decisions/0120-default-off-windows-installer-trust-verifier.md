# ADR-0120: Default-Off Windows Installer Trust Verifier

- Status: Accepted
- Date: 2026-08-12
- Owners: project maintainers
- Related milestone: M4

## Context

An eligible, signed update manifest authorizes exact installer metadata, but a
successful HTTP response does not prove that the received file is those bytes or
that Windows trusts its publisher. Checking only Authenticode permits a validly
signed different release; checking only SHA-256 ignores publisher trust,
certificate revocation, and timestamp durability.

The verifier must remain independently testable and inactive until protected
release keys, durable updater state, a bounded downloader, and installer
orchestration exist. The macOS development host cannot claim native Windows
trust evidence.

## Decision

- Add a client-side verifier with separate integrity and full Windows trust
  operations. It receives only a local absolute path and the size, SHA-256, and
  signer-certificate SHA-256 thumbprint already accepted from the signed
  manifest.
- Reject missing, non-regular, symbolic-link/reparse-point, zero-sized,
  oversized, unreadable, size-mismatched, or SHA-256-mismatched payloads.
- On Windows, acquire a read-only handle that denies write and delete sharing
  before hashing, then retain it through Authenticode inspection. The future
  downloader must use a private staging directory; the lock prevents path
  replacement during this verification call.
- Use `WINTRUST_ACTION_GENERIC_VERIFY_V2` without UI and require whole-chain
  revocation checking excluding the root. Keep provider state until extracting
  the primary signer and counter-signer, then close that state on every trust
  result.
- Require a successfully validated Authenticode counter-signature, representing
  the release-time RFC 3161 timestamp, and require the SHA-256 hash of the leaf
  signing certificate DER to equal the thumbprint in the Ed25519-signed
  manifest.
- Return only `Verified`, `IntegrityRejected`, `AuthenticodeRejected`, or
  `UnsupportedPlatform`. Never launch, schedule, persist, download, or display
  from this class.
- Compile the native verifier into the Windows client but keep it unreachable:
  no updater service, trusted product key, or product download path invokes it.

## Consequences

The future updater has a single fail-closed boundary for payload identity and
Windows publisher trust. A compromised download origin cannot substitute bytes
or another valid publisher when the signed manifest remains trustworthy.
Windows trust evaluation may perform revocation network access when this
currently inactive method is eventually called; updater UI and retry policy must
account for offline/revocation failures without bypassing them.

The portable test proves file integrity and non-Windows refusal. Native Windows
CI is configured to compile the WinTrust path and require unsigned test-payload
rejection. It does not yet prove acceptance of the project's real signed and
RFC 3161-timestamped Setup, certificate rollover, revoked/offline behavior, or
launch.

## Migration and Rollback

No settings, network, or installer behavior changes. Rollback removes the
verifier and its test. After a production updater invokes this boundary,
loosening trust, timestamp, revocation, or thumbprint rules requires a new
security ADR and an overlapping compatible release.

## Verification

- portable Qt tests accept exact size/SHA-256 and reject wrong size, hash, or
  metadata before reporting Authenticode unsupported;
- Windows Qt CI is configured to reject the deliberately unsigned temporary
  payload through WinVerifyTrust;
- the client Release build links `wintrust` and `crypt32` only on Windows;
- no trusted key, downloader, scheduler, launcher, or update UI is added.
