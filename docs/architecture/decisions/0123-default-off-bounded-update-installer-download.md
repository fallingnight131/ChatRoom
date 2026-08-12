# ADR-0123: Default-Off Bounded Update Installer Download

- Status: Accepted
- Date: 2026-08-12
- Owners: project maintainers
- Related milestone: M4

## Context

An eligible signed manifest contains a credential-free installer URL and exact
size, but the existing attachment downloader is not an update trust boundary.
It carries chat tokens in query parameters, permits a redirect policy, and has
no signed-byte limit. Reusing it could disclose credentials or retain an
unbounded/untrusted partial executable.

## Decision

- Add a dedicated, inactive installer download transport. Accept only an exact
  HTTPS URL without user information, query, or fragment, an expected size from
  1 byte through 2 GiB, and an absolute private staging directory.
- Align the schema author, client semantic policy, and transport on the same
  2 GiB maximum so an accepted manifest is locally representable.
- Use Qt's normal platform TLS validation. Never suppress certificate or
  hostname errors. Set a two-minute transfer timeout, manual redirect policy,
  identity content encoding, no-store request policy, and a 256 KiB read buffer.
- Stream into a random owner-only `.part` file. Reject a redirect, any status
  other than exact 200, conflicting Content-Length, an early/late body, a body
  exceeding signed size, network/timeout/write/flush failure, or an unsafe
  staging directory.
- Delete every partial file after rejection, cancellation, start failure, or
  object destruction. Return the random path only after the exact byte count is
  durably flushed.
- Do not hash or trust the result here. The future orchestrator must immediately
  pass the returned path and signed metadata to ADR-0120, then delete it after
  verification/use. Do not enable a key, endpoint, scheduler, or launch path.

## Consequences

Installer transfer behavior is isolated from authenticated chat attachments and
bounded before Authenticode parsing. A server cannot redirect the updater to a
different origin or exhaust disk beyond the signed allowance. Size equality is
not trust; ADR-0120 remains mandatory.

The deterministic network test uses an injected Qt network manager and therefore
proves request policy, streaming bounds, cancellation, and cleanup without
weakening production TLS. A real public HTTPS origin, slow/offline behavior,
proxy/captive-portal behavior, and Windows end-to-end download plus trust remain
release evidence.

## Migration and Rollback

No product code invokes the transport. Rollback removes it, its test, and the
2 GiB authoring constraint while no signed release channel exists. After
activation, increasing the limit or changing redirect/TLS policy requires a
security ADR and compatible client rollout.

## Verification

- plain HTTP and unsafe request metadata are rejected before a request;
- exact 200 bytes succeed with redirects disabled and identity encoding;
- mismatched Content-Length, an overlong stream, and redirect are rejected;
- cancellation and every rejection leave no partial file;
- authoring and decision tests reject installer metadata above 2 GiB.
