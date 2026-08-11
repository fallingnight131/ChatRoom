# ADR-0047: V2 Process-Local Authentication Admission

- Status: Accepted
- Date: 2026-08-11
- Related milestone: M3

## Context

A bounded worker queue limits admitted authentication work but reconnecting
clients can repeatedly compete for that queue. V1 already demonstrated the need
for cumulative account, direct-peer, and process limits. V2 must apply those
controls before copying credentials or submitting Argon2id/database work.

The V2 listener and trusted reverse-proxy boundary do not exist yet. This slice
therefore uses only Netty's direct socket peer and process-local state; it must
not trust forwarding headers or claim multi-gateway protection.

## Decision

- Add a transport admission interface and a synchronized in-memory fixed-window
  implementation with gateway, direct-peer, and normalized-account dimensions.
- Consume dimensions cumulatively in that order before copying the password to
  an application command or submitting worker work. A denial returns the
  existing generic `AuthenticationRejected(RATE_LIMITED)` with a bounded retry
  duration and closes the connection.
- Obtain peer identity only from the channel's resolved remote socket address.
  Do not accept `X-Forwarded-For`, Protobuf fields, or other client claims.
  Missing/invalid peers share a bounded `<unknown>` bucket.
- Normalize account limiter keys by Unicode-aware Java trimming and
  locale-independent lowercasing. Never expose the key through decisions,
  metrics, logs, or protocol responses.
- Bound both keyed maps by explicit `maxTrackedKeys`. Expire completed windows;
  when no expired entry frees capacity, fail closed with a non-identifying
  capacity dimension.
- On verified login, remove only that account bucket. Keep direct-peer and
  gateway consumption so known valid credentials cannot bypass aggregate work
  limits.
- Expose only totals, denial dimensions, and active key counts in a snapshot.
  The event sink receives the denial dimension but no account or address.
- Keep all limits as validated constructor configuration until listener
  bootstrap and deployment tuning are implemented. Redis replaces or
  coordinates this state before multiple gateways are treated as protected.

## Consequences

- Reconnecting clients cannot create unbounded password work in one Java
  gateway process, and limiter memory is explicitly bounded.
- NAT/proxy peers are aggregated because untrusted forwarded addresses are
  intentionally ignored. Operators must tune the direct-peer dimension after a
  trusted proxy boundary exists.
- State resets on restart and does not coordinate multiple instances. This is
  a single-process M3 control, not the M5 distributed limiter.

## Verification

Deterministic clock tests cover case/whitespace account normalization across
peers, equivalent IPv6 peer forms, cumulative gateway denial, success reset of
only the account bucket, keyed-map capacity denial, counters/key counts, and
recovery after expiry. Embedded-channel tests prove denied work never reaches
the authentication use case and returns only a generic rate-limited response.

## Rollback

Remove the inactive V2 admission adapter and inject the allow-all test adapter.
Do not enable a listener without equivalent pre-hash abuse controls. V1 remains
authoritative and unchanged.
