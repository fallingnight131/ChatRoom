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
  SHA-256 Authenticode signer-certificate thumbprint.

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
