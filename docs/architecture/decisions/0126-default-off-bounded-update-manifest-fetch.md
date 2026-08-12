# ADR-0126: Default-Off Bounded Update Manifest Fetch

- Status: Accepted
- Date: 2026-08-12
- Owners: project maintainers
- Related milestone: M4

## Context

ADR-0122 accepts exact signed manifest bytes, but no network boundary retrieves
them. Reusing chat HTTP traffic would mix authenticated attachment behavior with
the update trust root. A discovery endpoint can also attack the client before
signature verification through redirects, oversized responses, ambiguous
signature locations, or retained unverified bytes.

## Decision

- Add a dedicated, inactive Qt network transport. It accepts only a
  credential-free HTTPS URL whose final filename is exactly `manifest.json` and
  an exact same-origin, same-path detached-signature URL formed by appending
  `.sig`.
- Reject user information, query, fragment, encoded/backslash/dot-segment paths,
  and explicit port zero before network I/O. Use the platform TLS stack without
  suppressing certificate or hostname failures.
- Fetch the manifest first and its signature second. Use a 15-second transfer
  timeout, manual redirect policy, identity encoding, no-store request policy,
  exact HTTP 200, and a 16 KiB read buffer.
- Bound the manifest to 1 through 64 KiB and the Ed25519 detached signature to
  exactly 64 bytes. Enforce declared Content-Length when present and streaming
  bounds regardless of the header.
- After rejection, cancellation, start failure, or destruction, expose no
  manifest or signature bytes. On success return exact bytes without parsing or
  normalizing them.
- Keep the transport disconnected from product startup. A future coordinator
  must pass the pair directly into ADR-0122, and only an `Eligible` result may
  reach ADR-0124. No product origin, trusted key, scheduler, UI, or launcher is
  introduced here.

## Consequences

Network discovery is isolated from chat authentication and resource-bounded
before cryptographic parsing. The signature location cannot silently move to a
different host or path, and a redirect or error response never enters the trust
pipeline. HTTPS transport is not authenticity by itself: only the pinned
Ed25519 key and exact canonical-byte verification authorize a manifest.

The injected-manager test proves request policy and failure behavior without a
public endpoint. Public certificate/proxy/captive-portal behavior, production
origin operations, key provisioning, scheduled checks, and end-to-end Windows
update evidence remain M4 gates.

## Migration and Rollback

No product path invokes this transport and no persistent/protocol data changes.
Rollback removes the transport, test, and this decision. After activation,
changing the origin/path rule, redirect behavior, or response bounds requires a
security ADR and compatible client rollout.

## Verification

- unsafe, cross-origin, or mismatched URL pairs fail before a request;
- an exact 200 manifest/signature pair is fetched sequentially without byte
  changes and with redirects disabled;
- oversized or invalid Content-Length responses, short signatures, and
  redirects fail without exposing response bytes;
- cancellation produces no response bytes;
- the full Qt gate compiles the transport into the Windows client target.
