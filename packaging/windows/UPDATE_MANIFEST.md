# Windows Signed Update Manifest

The future Windows updater uses two independent trust checks:

1. the canonical update manifest has a detached 64-byte Ed25519 signature from
   a dedicated offline update key;
2. the downloaded `Setup.exe` matches the signed size/SHA-256 metadata and has a
   valid, timestamped Authenticode signature from the signed certificate
   thumbprint.

`tools/windows_update_manifest.py` implements schema-1 creation and offline
OpenSSL Ed25519 signing/verification. It deliberately refuses the current
`unsigned-verification-Setup.exe` name. No production update private/public key
is committed, and the product updater remains disabled until the client has a
reviewed fixed-public-key ring and the release owner provisions protected keys.
ADR-0118 adds the default-deny verifier primitive, but deliberately injects an
empty trusted-key ring and adds no network/update activation.
ADR-0119 adds an inactive semantic decision policy for schema, replay, version,
validity, rollout, and installer metadata. It still has no persistent state,
download, Authenticode verification, launch, scheduler, or UI path.
ADR-0120 adds inactive local payload verification: exact size/SHA-256 followed
on Windows by WinTrust chain/revocation, a validated counter-signature, and
leaf-certificate SHA-256 thumbprint matching. No downloader invokes it.
ADR-0121 adds inactive, owner-only atomic storage for the UUIDv4 rollout
identity and stable/beta sequence-plus-digest replay watermarks. Corrupt state
blocks rather than resets; no product path creates it.
ADR-0122 composes signature, state, policy, and atomic acceptance behind one
inactive entry point so future transport cannot bypass trust ordering.
ADR-0123 adds an inactive credential-free HTTPS-only installer transport with
no redirects, a schema-aligned 2 GiB limit, bounded streaming, cancellation,
and partial-file cleanup. Returned bytes still require ADR-0120 verification.
ADR-0124 composes signed eligibility, download, and background trust into one
inactive preparation path; only verified bytes are returned and all rejected
bytes are deleted. It does not launch an installer.
ADR-0125 makes the Windows client single-instance and makes NSIS reject its
shared liveness mutex before any install mutation. A future launcher must request
normal shutdown and wait; it must never terminate the client silently.
ADR-0126 adds an inactive, bounded HTTPS fetcher for the exact
`manifest.json`/`manifest.json.sig` pair. It provides untrusted bytes only to the
check coordinator; ADR-0122 verification runs before ADR-0124 preparation.
No production update origin or key is configured.
ADR-0127 composes fetch, ADR-0122 acceptance, and ADR-0124 verified preparation
behind one inactive check entry point. Invalid signatures and deferred rollout
cannot reach the installer request; no launcher consumes its verified result.
ADR-0128 adds the final Windows primitive that repeats trust while holding Setup
against replacement through silent process creation, waits, and returns its
exit code. The external helper owns this call, but no client path invokes it.
ADR-0129 packages the external helper and gives it a strict UUID-bound parent,
installer-metadata, result-file, restart-path, and ready-event contract. It
waits for normal parent exit, records results atomically, cleans only when safe,
and restarts only on installer exit zero. Client handoff remains inactive.
ADR-0130 ensures a `Ready` update retains the signed size, digest, and publisher
thumbprint with its path through the full check service, so client handoff does
not need to reconstruct security metadata.
ADR-0131 stages the helper and matching Qt Core outside the program tree, binds
the complete prepared evidence into its one-shot command, and permits client
quit only after the helper signals that it owns the parent-process wait.
ADR-0132 makes result interpretation fail closed on schema, request UUID,
outcome/exit-code, timestamp, size, and error-text policy before a future startup
repository can expose the result to product UI.
ADR-0133 persists one pending UUID/version/time before exit and derives only its
result/run names, allowing valid startup consumption exactly once without
turning missing or malformed helper evidence into success.
ADR-0134 exposes one inactive coordinator decision: the client may begin normal
shutdown only after both helper handshake and pending lifecycle persistence;
either failure keeps the client alive.

The detached signature is served next to the canonical manifest at
`manifest.json.sig`. Both URLs must be credential-free HTTPS on the same origin
and exact directory path. The client refuses query strings, fragments,
redirects, encoded paths, manifests over 64 KiB, and signatures other than
exactly 64 bytes.

## Canonical format

The signed bytes are UTF-8, ASCII-escaped, lexicographically sorted compact JSON
with exactly one trailing LF. Alternate whitespace, duplicate/extra fields,
non-canonical SemVer, non-UTC timestamps, or a changed byte are rejected.

The manifest binds:

- fixed `x86_64` architecture, stable/beta channel, and monotonic
  `manifestSequence`;
- `signingKeyId` for explicit embedded-public-key rotation;
- whole-second UTC publication/expiry with at most 31 days validity;
- target and minimum directly updatable numeric SemVer;
- exact Git source revision;
- signed rollout percentage plus 32-byte rollout seed;
- one credential-free HTTPS production Setup URL, size, SHA-256, and lowercase
  SHA-256 Authenticode signer-certificate thumbprint; size is at most 2 GiB.

Clients must persist the highest accepted sequence and canonical-manifest digest
per channel across signing-key rotations. A lower sequence or a different
manifest at the same sequence is rejected; an identical retry is idempotent.
They must use a stable, non-secret device identifier, never account identity.
The rollout bucket is SHA-256 of device-ID UTF-8, one NUL byte, and the raw
32-byte seed; the first eight digest bytes are unsigned big-endian modulo 100.
ADR-0119 implements these rules locally, but no product path invokes them yet.

## Offline flow

Generate an Ed25519 key outside the repository and ordinary CI workspace:

```bash
openssl genpkey -algorithm Ed25519 -out /secure/windows-update-private.pem
openssl pkey -in /secure/windows-update-private.pem -pubout \
  -out /secure/windows-update-public.pem
```

After Windows release automation has verified Authenticode and its RFC 3161
timestamp, create the canonical manifest with `create`, sign it offline with
`sign`, then verify from a separate environment with `verify`. Use
`python3 tools/windows_update_manifest.py --help` for exact parameters.
Create/sign/verify CLI operations require the manifest to be currently valid;
tests may call pure validation helpers with an explicit observation time.

Never echo, archive, or upload the private key. The public key can be committed
only in a future key-enablement ADR after fingerprint review and two-person key
custody. A key rotation publishes a client that trusts both old and new key IDs
before signing any manifest exclusively with the new key.
