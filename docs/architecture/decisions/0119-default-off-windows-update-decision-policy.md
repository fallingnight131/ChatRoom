# ADR-0119: Default-Off Windows Update Decision Policy

- Status: Accepted
- Date: 2026-08-12
- Owners: project maintainers
- Related milestone: M4

## Context

A valid Ed25519 signature proves which key authorized bytes, but does not decide
whether those bytes describe a safe update for this client. ADR-0118 deliberately
stops before version, time, replay, rollout, and installer semantics. Mixing
those decisions into a future network callback would make security behavior hard
to test and couple it to Qt transport/UI state.

## Decision

- Add a transport- and persistence-neutral decision policy that accepts only an
  already signature-verified manifest object plus its canonical bytes. Reject
  any object that is not exactly the object parsed from those signed bytes.
- Require the exact schema-1 field set, `chat-room-windows-client`, `x86_64`, the
  selected stable/beta channel, bounded key ID, 40-character source revision,
  and numeric Windows version components from 0 through 65535.
- Require explicit UTC context and the signed whole-second UTC window
  `publishedAt <= now < expiresAt`, with no more than 31 days validity.
- Maintain one highest accepted sequence and canonical-manifest SHA-256 per
  channel across key rotations. Reject lower sequences and same-sequence
  conflicts; permit the identical sequence/digest idempotently. Return new
  accepted state to the caller, which must later persist it atomically.
- Route target versions at/below the local version to `NoUpdate`. Route clients
  below `minimumUpdatableVersion` to `ManualUpdateRequired`; never attempt a
  chain-skipping automatic install.
- Derive the rollout bucket as SHA-256 of UTF-8 stable device ID, one NUL byte,
  and the raw 32-byte signed seed; interpret the first eight digest bytes as
  unsigned big-endian and take modulo 100. Never use account identity. A bucket
  below signed percentage is eligible; otherwise defer.
- Require one canonical credential-free HTTPS Setup URL matching the target
  version, positive bounded size, 32-byte payload digest, and 32-byte
  Authenticode certificate thumbprint. Reject unknown fields and path traversal.
- Return only `Rejected`, `NoUpdate`, `ManualUpdateRequired`,
  `DeferredByRollout`, or `Eligible` plus bounded installer/acceptance metadata.
  Do not fetch, persist, launch, or display from this class.
- Keep the feature inactive: no product key, settings repository, scheduler,
  downloader, Authenticode adapter, or UI invokes this policy.

## Consequences

Version and rollout behavior are deterministic across clients and testable
without a server. Key rotation cannot reset the channel sequence. Same-manifest
retries are safe, while equivocation at one sequence fails closed.

The policy trusts that signature verification happened immediately before it;
the future application service must make that ordering impossible to bypass.
Clock rollback beyond the signed window, durable replay state, device-ID storage,
download integrity, Authenticode/revocation, and installer launch remain future
M4 boundaries.

## Migration and Rollback

No active settings or network path exists. Rollback removes the policy/test and
restores the authoring schema without `architecture` only while no signed channel
has shipped. After activation, schema/sequence changes require a compatibility
ADR and overlapping client rollout.

## Verification

- Qt tests cover eligible, same-version, below-minimum, zero-percent rollout,
  deterministic bucket 5, idempotent duplicate, sequence conflict/replay,
  future/expired windows, traversal URL, unknown fields, and signed-byte/object
  mismatch;
- Python author tests align `x86_64`, 53-bit sequence precision, Windows version
  component bounds, and all existing signature/schema rejection paths;
- the full Qt gate compiles the policy into the default-off client.
