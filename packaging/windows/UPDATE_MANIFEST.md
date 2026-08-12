# Windows Signed Update Manifest

The compiled-trust Windows updater uses two independent trust checks:

1. the canonical update manifest has a detached 64-byte Ed25519 signature from
   a dedicated offline update key;
2. the downloaded `Setup.exe` matches the signed size/SHA-256 metadata and has a
   valid, timestamped Authenticode signature from the signed certificate
   thumbprint.

`tools/windows_update_manifest.py` implements schema-1 creation and offline
OpenSSL Ed25519 signing/verification. It deliberately refuses the current
`unsigned-verification-Setup.exe` name. No production update private/public key
is committed. Ordinary builds remain disabled; a release build must provide a
reviewed fixed-public-key ring while release owners provision protected private
keys outside the repository and build command.
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
ADR-0135 activates only startup reconciliation under the owner-local update
root. Recent pending work exits before login, success must match the running
version, and failure/rejection is presented without enabling discovery or
installation.
ADR-0136 makes activation an explicit compiled release input: exact channel and
HTTPS manifest URL plus one/two reviewed public keys. Ordinary builds remain
disabled, writable settings cannot redirect trust, and private keys are never
accepted by the client build.
ADR-0137 adds a second UUID commit event: helper-ready is insufficient, pending
lifecycle state must be durable before the client commits and may exit. Missing
commit records `handoff-aborted` and never starts Setup.
ADR-0138 instantiates the chain only in an explicitly compiled-trust build. It
checks once after first login and on manual request, offers cancellation while
preparing, requires default-No install consent after trust succeeds, removes a
declined/failed prepared file, and uses the normal draft/disconnect quit path
only after two-phase handoff authorization.
ADR-0139 defines provider-neutral post-signing evidence for the exact client,
update helper, and final Setup. All three must have valid timestamped
Authenticode from the reviewed SHA-256 publisher certificate before evidence is
written. Current CI proves only unsigned rejection and supplies no private key.
ADR-0140 independently validates that evidence and recomputes the final
client/helper/Setup hashes before any future upload or manifest signing. Test
fixtures exercise rejection semantics and are not positive signature evidence.
ADR-0141 then assembles the evidence, Setup, and complete Qt/SQLite/libsodium
client directory into one immutable candidate with a strict full-file manifest.
Only the candidate's Setup bytes may feed later update-manifest authoring.

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
ADR-0119 implements these rules locally; ADR-0138 invokes them only when the
client contains a valid compiled product-trust configuration.

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
Manifest parsing rejects duplicate keys at every JSON object level before
canonical comparison. Detached signature output is write-once: an existing
file, link, or unsafe parent is rejected rather than replaced. This preserves a
stable audit input and prevents a second signing operation from silently
changing previously reviewed evidence.

Never echo, archive, or upload the private key. The public key can be committed
only in a future key-enablement ADR after fingerprint review and two-person key
custody. A key rotation publishes a client that trusts both old and new key IDs
before signing any manifest exclusively with the new key.

Production automation must not use the fixture-oriented `--private-key` path.
`sign_windows_update_manifest_protected.ps1` instead accepts only a preconfigured
`CHATROOM_UPDATE_SIGNING_KEY_URI` beginning with `pkcs11:`. PIN values/sources,
passwords, secrets, private-key files, and provider installation are not inputs;
HSM authentication belongs to the protected runner service and provider setup.
The adapter requires preinstalled OpenSSL 3, a reviewed regular public-key PEM
whose file SHA-256 and manifest key ID are explicit public inputs, then verifies
the 64-byte signature with that public key before atomically creating a
previously absent output. This is a signing primitive, not channel publication.

## Closed update-channel candidate

After protected Authenticode acceptance and protected update-manifest signing,
assemble their exact outputs into one immutable unpublished candidate:

```bash
python3 tools/windows_update_channel_candidate.py assemble \
  --windows-candidate-root /release/windows-candidate \
  --update-manifest /release/manifest.json \
  --signature /release/manifest.json.sig \
  --public-key /reviewed/windows-update-public.pem \
  --output-root /release/windows-update-channel-candidate \
  --version-file packaging/windows/VERSION \
  --source-revision <full-git-revision> \
  --channel stable \
  --qt-version <exact-version> \
  --authenticode-signer-sha256 <publisher-certificate-sha256> \
  --public-key-file-sha256 <reviewed-public-pem-sha256>
```

The assembler independently verifies the complete schema-5 Windows candidate
and the detached Ed25519 signature, then requires the update manifest to name
the exact Setup size, SHA-256, publisher, version, revision, and channel. It
copies the reviewed public PEM but never private key material, closes every
file in a sorted manifest and `SHA256SUMS`, records the assembly instant, and
renames atomically into a previously absent destination. `verify` can later
audit the retained candidate against that immutable instant after the live
manifest expires. The status remains
`signed-update-channel-not-published-candidate`; upload, client trust
provisioning, stable/beta mutation, rollout, and rollback are separate gates.

The manual `m4-windows-protected-update-signing.yml` workflow orchestrates this
boundary on a dedicated protected Windows update-signing runner. It downloads
one exact prior signed Windows candidate, creates a seven-day canonical
manifest, invokes only the PKCS#11 signer, verifies and closes the result, then
uploads one seven-day evidence artifact. Its inputs contain public release
policy only; the key URI and public PEM path are runner configuration. The
workflow intentionally has read-only repository permissions and no release,
endpoint upload, client-trust provisioning, or channel pointer operation.

Existing-channel promotion requires a separate short-lived authorization from
`windows_update_release_authorization.py`. It independently verifies the closed
candidate and derives the exact expected current sequence/SHA-256 from a
canonical current-manifest snapshot. The target sequence must advance, the
candidate may be at most 24 hours old, and approval lasts 60–900 seconds. The
record contains no credentials and performs no network operation; a future
executor must compare the live channel bytes with that approved digest before
switching. This path deliberately does not bootstrap an empty channel.

Before authorization execution, `windows_update_channel_store.py stage` can
copy the complete candidate into an immutable release directory addressed by
the canonical manifest SHA-256. It revalidates after copying and atomically
renames, but contains no active pointer or network operation. Thus a provider
can preposition every byte before a later compare-and-swap activation.

The local reference executor consumes one ADR-0178 authorization, verifies the
active immutable release against the approved current manifest, records
consumption before mutation, and atomically switches one `active-channel.json`
pointer. Its evidence remains `awaiting-external-observation`; failed evidence
persistence restores the old pointer without making the authorization reusable.
Provider implementations must preserve those compare-and-swap semantics.

Post-switch completion requires `windows_update_release_probe.py` to observe
the exact manifest, adjacent signature, and Setup bytes through trusted HTTPS.
The three URLs must share an origin/directory; redirects, credentials,
transformation, cookies, CORS, wrong length/type/cache/security headers, or any
byte mismatch fail. Manifest/signature use `no-store`; content-addressed release
Setup uses `public, max-age=31536000, immutable`.
