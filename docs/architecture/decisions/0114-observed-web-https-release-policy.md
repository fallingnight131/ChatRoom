# ADR-0114: Observed HTTPS Web Release Policy

- Status: Accepted
- Date: 2026-08-12
- Owners: project maintainers
- Related milestone: M4

## Context

ADR-0112 binds intended response policy to an artifact, and ADR-0113 proves
immutable activation/rollback on disk. Neither proves that a reverse proxy, CDN,
or object host serves the selected bytes and headers. Release health needs an
external HTTPS observation that fails on a missing CSP directive, wrong cache
class, stale release identity, altered byte, redirect, or untrusted certificate.

## Decision

- Add a provider-neutral HTTPS probe that accepts one credential-free origin,
  the selected immutable release directory, and an optional CA certificate for
  isolated acceptance environments.
- Require TLS verification with a minimum TLS 1.2 client context. Do not expose
  an insecure certificate-skip option. Redirects are failures so a probe cannot
  silently leave the release authority.
- Fetch every static file declared by the selected manifest, not only
  `index.html`. Request identity encoding and verify the exact response bytes,
  size, and SHA-256 against the immutable artifact.
- Observe every bound security header, release version/source header, and the
  correct entrypoint/hashed/other cache class exactly once on every response.
- Send `Accept-Encoding: identity` and reject a `Content-Encoding` response so
  immutable bytes can be compared directly. Also reject `Set-Cookie` and
  permissive CORS on static release responses.
- Emit bounded JSON evidence containing the observed origin, release identity,
  policy digest, file count, paths, status, and observation time. Never include
  bearer credentials or response bodies.
- Exercise the probe against an ephemeral trusted localhost HTTPS server whose
  certificate/key are generated at test time and never committed. Negative
  tests omit CSP, change cache policy, use HTTP, and withhold trust.

## Consequences

The project can now distinguish a policy bundled with an artifact from the same
policy observed over HTTPS. The isolated server is only a deterministic test
fixture, not the production hosting implementation. Public release still needs
a real provider adapter, production certificate/DNS, `/ws` and `/api/` health,
monitoring, staged promotion, and the supported-browser matrix.

The probe verifies the identity representation. A production adapter may still
compress normal browser responses, but it must honor the probe's explicit
identity request. Compression behavior and performance need separate browser
and delivery tests.

## Migration and Rollback

No application protocol or data changes. Run the probe against the candidate
before pointer promotion and again after promotion. On failure, stop rollout,
reactivate the retained previous release per ADR-0113, and probe it. Removing the
probe changes no deployed bytes or pointer.

## Verification

- positive isolation verifies TLS trust, all headers, both cache classes,
  identity values, all declared response bytes, and evidence fields;
- negative isolation rejects missing CSP, wrong caching, HTTP, and an untrusted
  certificate;
- CI generates its localhost certificate/key ephemerally and runs the probe
  tests before artifact assembly.
