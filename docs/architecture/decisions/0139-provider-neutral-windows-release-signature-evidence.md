# ADR-0139: Provider-Neutral Windows Release Signature Evidence

- Status: Accepted; four-subject schema amended by ADR-0167
- Date: 2026-08-12
- Owners: project maintainers
- Related milestone: M4

## Context

The native Windows job deliberately builds and uploads an unsigned verification
installer. That is useful for install mechanics, but a future signing step could
check only Setup, accept the wrong publisher, omit timestamping, or publish
payload executables signed by a different identity. A signing-provider-specific
action would also couple the repository's acceptance policy to secret custody.

## Decision

- Add a Windows-only, provider-neutral PowerShell verifier that accepts only the
  final `ChatClient.exe`, `ChatRoomUpdateLauncher.exe`, and canonical
  `ChatRoom-<version>-Setup.exe` names plus version, Git revision, expected
  publisher-certificate SHA-256, and a new evidence path.
- Reject missing, renamed, directory, or reparse-point inputs. Require all three
  signatures to have Windows `Valid` status, the exact reviewed SHA-256 signer
  certificate, and a validated timestamp certificate. Record each final file's
  SHA-256 and size only after every artifact passes.
- Write schema-1 evidence through a same-directory temporary file and refuse to
  overwrite prior evidence. A rejected artifact produces no evidence.
- Accept no certificate, PFX, password, private key, token, or signing-provider
  input. Signing happens before this boundary under separately governed key
  custody; this verifier observes only public results.
- Run a cross-platform source-policy test in baseline CI. In native Windows CI,
  copy the deliberately unsigned Setup to the canonical release name and prove
  that the verifier rejects the unsigned payload and creates no evidence.
- Do not convert the unsigned job into a release job. Positive evidence requires
  future release credentials and a genuinely signed/timestamped candidate.

## Consequences

The project now has one reusable post-signing acceptance contract independent of
Azure Trusted Signing, a hardware-backed service, or another provider. Merely
renaming the unsigned verification Setup cannot satisfy it. Positive signature
evidence will bind public publisher identity and final bytes, while source
revision remains traceability metadata supplied by the protected release job.

PowerShell's Authenticode API establishes a valid timestamp certificate but is
not the only release check: the client update verifier still repeats WinTrust,
counter-signature, integrity, and signed-publisher checks before launch.

## Migration and Rollback

No production workflow or secret changes. Rollback removes the verifier,
negative CI check, and policy test. It must not be removed from a future signed
workflow without a replacement evidence contract and ADR.

## Verification

- policy tests require the three exact artifacts, SHA-256 publisher binding,
  timestamp certificate, link rejection, atomic no-overwrite evidence, and
  absence of private-key inputs;
- workflow parsing remains valid;
- native Windows CI is configured to require rejection of the unsigned client,
  helper, and renamed Setup with no evidence file;
- positive Authenticode acceptance is explicitly pending a protected signed
  release candidate and is not claimed locally.
