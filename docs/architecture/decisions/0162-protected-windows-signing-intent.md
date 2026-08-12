# ADR-0162: Protected Windows Signing Intent

- Status: Accepted
- Date: 2026-08-12
- Owners: project maintainers
- Related milestone: M4

## Context

ADR-0161 verifies the unsigned artifact entering a protected runner. A GitHub
environment approval alone does not leave a repository-defined record of the
exact artifact run, commit, channel, certificate identity, timestamp service,
or runner class that reviewers approved. Passing those independently to later
steps also permits accidental identity drift.

Private certificate material and credentials must never be written into such a
record.

## Decision

- Create a closed schema-1 protected-signing intent after environment approval.
- Bind canonical version and source revision, stable/beta channel, unsigned
  artifact run ID, and the exact derived unsigned artifact name.
- Bind a lowercase SHA-1 certificate-store selector and independently reviewed
  lowercase SHA-256 signer-certificate identity.
- Bind a credential-free HTTPS RFC 3161 timestamp URL with no query or fragment.
- Fix environment to `windows-production-signing` and runner class to
  `self-hosted-windows-signing`.
- Record whole-second UTC time and reject intents older than two hours or more
  than five minutes in the future.
- Write once atomically to a new absolute path and reject links, large files,
  duplicate/unknown fields, malformed identity, and mismatch on re-verification.
- Prohibit certificate bytes, private keys, passwords, tokens, and publication
  authority from the schema.
- Interpret status only as `protected-signing-approved-not-published`.

## Consequences

Every future protected signing candidate can retain a precise, reviewable record
of what was authorized without leaking secret material. The signing runner can
use the SHA-1 selector for Windows certificate-store lookup while all post-sign
checks remain bound to the stronger expected SHA-256 identity.

The intent is not proof that the certificate exists, is private-key capable, or
signed anything. It is also not permission to publish.

## Migration and Rollback

The protected workflow must create and verify the intent before accessing the
certificate store, then include its bytes/hash in the immutable candidate.
Rollback disables the protected workflow; no unsigned artifact or release state
changes.

## Verification

- accept a fresh exact stable/beta intent with a canonical artifact identity;
- reject invalid run IDs, fingerprint casing/length, unsafe timestamp URLs,
  changed version/revision/channel/build system/environment/signer/artifact,
  unknown fields, and stale/future/malformed time;
- verify the schema contains no password/private-key/token concepts;
- run the policy in ordinary CI before adding the protected workflow.
