# ADR-0100: Inactive S3 Runtime Configuration

- Status: Accepted
- Date: 2026-08-12
- Owners: project maintainers
- Related milestone: M3

## Context

ADR-0099 adds an inactive S3 adapter, but constructing SDK clients directly in
the gateway would spread endpoint, credential, addressing, and resource-
ownership decisions into product transport code. Permissive endpoint parsing
could also send signed requests or credentials to an unintended origin.

## Decision

- Keep strict non-secret S3 configuration in `object-storage-s3`. Require an
  explicit HTTPS origin, region, and bucket. Accept path-style addressing only
  from the exact lower-case `true`/`false` switch.
- Reject endpoint user info, query, fragment, and non-root paths. Credentials
  never appear in the configuration record or environment map parser.
- Require the deployment composition root to inject an `AwsCredentialsProvider`.
  This permits workload identity, container/task roles, or externally delivered
  credentials without choosing or reading a secret source inside configuration.
- Construct both the synchronous S3 client and presigner from the same endpoint,
  region, addressing mode, and credential provider. Explicitly use the JDK
  URLConnection transport for provider calls.
- Own both SDK resources in one `AutoCloseable` runtime and close the presigner
  and S3 client deterministically. If presigner construction fails, close the
  already-created S3 client before propagating the error.
- Keep this runtime outside `im-gateway`. Current gateway configuration does not
  read the attachment variables and startup makes no provider request. Activate
  composition only with provider capability checks and attachment commands in a
  later ADR.

## Consequences

The future composition root has one validated, replaceable resource boundary,
while the default M3 gateway remains cloud-independent. Operators must choose a
credential provider explicitly; there is no silent fallback to a developer's
local profile in this module.

## Verification and Rollback

Tests prove required/default values, exact path-style parsing, rejection of
HTTP/user-info/path endpoints, independence from credential environment values,
and construction/closure of both SDK resources without network access. The
complete Backend gate includes this module.

Rollback removes the inactive configuration/runtime classes and restores the
URLConnection dependency to runtime-only. No gateway configuration, process,
credential, provider object, protocol, or database row changes.
