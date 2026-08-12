# ADR-0104: Guarded Attachment Object-Store Capability Probe

- Status: Accepted
- Date: 2026-08-12
- Owners: project maintainers
- Related milestone: M3

## Context

ADR-0099 requires evidence from the actual Amazon S3, Tencent COS, MinIO, or
other selected bucket before attachment upload or cleanup is composed into the
gateway. Presigner unit tests cannot prove bucket policy, conditional-write
behavior, checksum storage, CORS, or deletion. A manual collection of unrelated
commands is difficult to repeat and risks leaving objects or signed URLs in
logs.

This is a critical file-security boundary. The acceptance mechanism must not run
during an ordinary build, silently select a bucket, persist a signed URL, create
attachment metadata, or leave a test object after a failed assertion.

## Decision

- Add an operator-only capability probe to the isolated `object-storage-s3`
  module. It is not called by `GatewayMain`, tests, `check`, or startup.
- Require the exact `CREATE_AND_DELETE_TEST_OBJECT` confirmation, an exact
  HTTPS Web origin, complete non-secret bucket configuration, and an explicit
  `default-chain` credential-provider choice before constructing SDK resources.
  This does not make ambient credentials acceptable for production runtime
  composition; it makes their use for this deliberate operator command visible.
- Generate a random 256-byte payload and UUID key under
  `attachments/capability-probe-*`. Do not create an attachment database row or
  chat message.
- Preflight the signed URL using the configured Web origin and all signed
  request-header names. Require a successful response, matching origin (or `*`
  for this non-credentialed direct request), PUT permission, and every requested
  header.
- Execute the signed create-only PUT with the exact returned headers and origin.
  Require 2xx plus browser-readable CORS, then repeat the identical PUT and
  require provider conflict/precondition failure (`409` or `412`).
- Inspect the object using checksum-enabled HEAD and compare exact key, length,
  and SHA-256 with constant-time hash comparison.
- Always delete the generated key and require a following HEAD to report it
  absent. Cleanup runs after both success and failure; cleanup failure is added
  to, rather than replacing, the primary failure.
- Never print or retain bucket, object key, endpoint, signed URL, credential,
  response body, or provider exception. The success report contains fixed
  booleans only and the failure report contains a bounded local reason only.
- Treat a probe PASS as evidence for create-only PUT, checksum HEAD, Web CORS,
  and deletion on that provider configuration. It does **not** prove signed-URL
  expiration, least-privilege policy, lifecycle configuration, production
  capacity, or readiness to activate the gateway path. Those remain separate
  acceptance checks from ADR-0099.

## Alternatives Considered

- Run provider CLI commands manually: rejected as the primary mechanism because
  signed headers and CORS differ from the browser path and cleanup is easy to
  omit on failure.
- Run the probe at gateway startup: rejected because startup would create an
  external object and require credentials before the feature is activated.
- Add the probe to CI: rejected because ordinary verification must not depend on
  a mutable external bucket or long-lived cloud credentials.
- Print the provider response for diagnosis: rejected because signed URLs and
  endpoint details are security-sensitive. Provider-side audit logs are the
  diagnostic source when fixed local outcomes are insufficient.

## Consequences

Operators have one reproducible, auto-cleaning command for the riskiest S3/COS
compatibility assumptions. The command deliberately requires stronger human
intent than ordinary configuration and can run from a macOS development host
against a dedicated non-production bucket.

The probe sends two small PUT requests, OPTIONS, HEAD, DELETE, and a final HEAD.
Its identity therefore needs narrowly scoped temporary permissions. Network
failure after a successful PUT can still prevent immediate cleanup, so the
dedicated test prefix also requires an independent short lifecycle safety net.

## Migration and Rollback

The module remains inactive and no data, protocol, runtime-composition, or
credential contract changes. Rollback removes the command, HTTP adapter, tests,
task, ADR, and runbook. An interrupted real run may leave only a random object in
the documented probe prefix; the bucket lifecycle rule is the recovery path.

## Verification

No-network tests prove the complete success path, exact signed headers and
payload hash, unsafe-origin rejection, missing CORS, initial PUT denial, replay
acceptance failure, checksum mismatch, cleanup on every started run, primary-
failure preservation, and signed-URL suppression. The full Java `check` remains
required.

Real-provider evidence is valid only when the operator follows
`docs/deployment/ATTACHMENT_OBJECT_STORAGE_ACCEPTANCE.md`, retains the fixed PASS
line plus provider/policy review evidence, completes the remaining expiry check,
and records no secret. No real-provider probe was executed by this change.
