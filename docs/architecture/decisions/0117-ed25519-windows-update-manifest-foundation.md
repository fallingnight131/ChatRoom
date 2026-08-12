# ADR-0117: Ed25519 Windows Update Manifest Foundation

- Status: Accepted
- Date: 2026-08-12
- Owners: project maintainers
- Related milestone: M4

## Context

HTTPS and Authenticode are necessary but insufficient for a staged automatic
update channel. The client needs a small signed policy object that binds version,
channel, rollout, expiry, source, exact installer bytes, and expected publisher.
Using the Authenticode private key for JSON signing would couple unrelated key
operations and require a platform-specific CMS authoring path. No production
signing keys or release owner currently exist.

## Decision

- Use a dedicated Ed25519 key for detached update-manifest signatures. Keep the
  update key independent from the Authenticode certificate. The eventual Windows
  client can verify Ed25519 through libsodium, already a pinned repository
  dependency, after its packaging boundary is explicitly extended.
- Sign only exact canonical JSON: sorted compact ASCII-escaped UTF-8 plus one LF.
  Reject alternative encodings/whitespace and unknown fields before signature
  verification.
- Bind product, stable/beta channel, positive monotonic sequence, signing-key ID,
  publication/expiry, target/minimum-updatable numeric SemVer, source revision,
  rollout percentage/seed, and production installer HTTPS URL/size/SHA-256 plus
  Authenticode SHA-256 certificate thumbprint.
- Limit validity to 31 days and require whole-second UTC. The future client must
  persist the highest accepted sequence per channel/key so a still-valid older
  signed manifest cannot replace newer policy.
- Require a production `ChatRoom-<version>-Setup.exe` name. The explicitly
  unsigned verification installer is structurally ineligible for this channel.
- Use OpenSSL only in the offline authoring/independent verification tool. Never
  pass key bytes through command arguments or logs. Create a mode-private
  temporary signature file and atomically publish the 64-byte result.
- Do not commit a private key, public production key, placeholder trusted key, or
  generated manifest/signature. The product update path remains default-off.
- Require two distinct trust results before activation: manifest Ed25519
  verification, then downloaded size/hash and valid timestamped Authenticode
  whose signer thumbprint matches signed metadata.

## Consequences

The release protocol is deterministic and cryptographically exercised without
pretending a production key exists. Stable/beta staging and key rotation have
explicit signed fields. Compromise of the Web origin alone cannot authorize a
different installer once the client verifier is active.

This step does not link libsodium into the client, embed a public key, fetch a
manifest, persist sequence/rollout state, inspect Authenticode, download Setup,
or activate an update. It also does not establish key custody, certificate
purchase, timestamp authority, revocation response, or release approval. Those
remain M4 gates.

## Migration and Rollback

There is no active update channel or client state. Rollback removes the tool,
tests, and schema documentation. Once a public key ships, key removal/rotation
requires a new ADR and an overlap release; never strand clients on a removed key.

## Verification

- deterministic schema tests cover canonical bytes, installer hash/size, exact
  fields, version relation, channel, URL, timestamps, rollout, thumbprint, and
  verification-installer rejection;
- temporary Ed25519 keys generated outside the repository sign and verify a
  64-byte detached signature through OpenSSL;
- changing a signed rollout byte fails verification; expired/not-yet-valid
  manifests and non-canonical JSON fail closed;
- Linux CI is configured to run the cross-platform cryptography policy test.
