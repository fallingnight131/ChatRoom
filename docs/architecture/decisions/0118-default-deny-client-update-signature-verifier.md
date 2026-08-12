# ADR-0118: Default-Deny Client Update Signature Verifier

- Status: Accepted
- Date: 2026-08-12
- Owners: project maintainers
- Related milestone: M4

## Context

ADR-0117 defines and signs the update manifest, but an offline verification tool
does not protect Windows users. The client needs the same canonical-byte and
Ed25519 trust boundary before any network/update code is added. There is still
no production key, so adding a placeholder trusted key would create a dangerous
accidental channel.

The client previously did not link libsodium. A verifier that works only on a
developer machine but omits the runtime DLL from the installed payload would
fail at launch on Windows.

## Decision

- Add a transport-neutral Qt verifier that accepts manifest bytes, a detached
  signature, and an injected `signingKeyId -> 32-byte Ed25519 public key` ring.
  No production/test public key is embedded in the product.
- Bound input to 64 KiB and the Ed25519 64-byte signature shape. Parse one JSON
  object, require the product/schema identity, reconstruct sorted compact JSON
  with one LF, and require byte equality before signature verification.
- Permit only finite integer JSON numbers within the interoperable `2^53-1`
  range. Align the authoring tool's manifest-sequence maximum to that limit.
- Select only the exact declared key ID. Empty rings, unknown IDs, malformed key
  lengths, invalid libsodium initialization, and bad signatures fail closed with
  generic non-secret errors.
- Compile the verifier into the client but do not call it from UI/network code.
  The update feature remains inert until a separately reviewed production key
  ring and decision/download/Authenticode pipeline exist.
- Link the client explicitly to pinned libsodium. Native Windows packaging copies
  the matching vcpkg DLL into the payload and requires it after install.

## Consequences

The product binary now contains a tested verification primitive but has no way
to trust or fetch an update. Key provisioning can later be a small explicit
change instead of mixing cryptography with networking and UI.

The Windows payload grows by the libsodium runtime. This is intentional and must
remain in manifest hashes, installer bytes, and future Authenticode signing.
Semantic version, expiry, sequence replay, rollout, download, and Authenticode
checks are not delegated to this signature class and remain the next boundary.

## Migration and Rollback

No network, settings, schema, or update behavior changes. Rollback removes the
verifier and client libsodium link/copy. Server password hashing still requires
libsodium independently.

## Verification

- a native Qt test generates an ephemeral keypair, signs canonical bytes, and
  proves successful verification and returned key identity;
- the same test rejects an empty key ring, unknown key ID, altered signature,
  and whitespace-expanded JSON;
- macOS development compilation/test passes with Homebrew libsodium;
- Windows CI is configured to copy the vcpkg runtime and verify it in the
  installed program directory.
