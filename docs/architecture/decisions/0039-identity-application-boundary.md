# ADR-0039: Identity Authentication Application Boundary

- Status: Accepted
- Date: 2026-08-11
- Related milestone: M3

## Context

The generated authentication protocol must not drive password verification or
SQL directly from a Netty handler. Doing so would couple transport, secrets,
identity policy, and persistence, making V1 compatibility and negative-path
testing harder. Unknown-account work must also resist timing-based enumeration.

## Decision

- Put the fresh-login use case and its domain types in the `application`
  module, with no Netty, Protobuf, Flyway, JDBC, or SQL dependency.
- Define outward ports for minimal account-credential lookup, password
  verification, and device/session issuance. PostgreSQL and password-hash
  implementations will implement these ports in later adapters.
- Require the verifier port to perform equivalent dummy hash work when no stored
  account hash exists. Verify before checking enabled state, then return the same
  rejection singleton for unknown account, wrong password, and disabled account.
- Issue a durable session only after a match for an enabled account.
- Own password and raw-token data through `SecretBytes`: clone at entry, expose
  only callback-scoped copies, zero each copy after callback, and zero owned
  memory on close. AuthenticationService always closes its command, including on
  exceptions. The caller must still zero its temporary source array after
  constructing a command, and the gateway must close an established session
  after serializing its one-time token.
- Keep negotiated client metadata in a transport-independent descriptor with
  only Web and Windows application enum values.

## Consequences

- Gateway and persistence adapters depend inward on one testable use case.
- Secret lifetime is explicit, though Java heap/runtime copies cannot provide a
  formal secure-memory guarantee; logs, exceptions, heap dumps, and telemetry
  still require redaction policy.
- The use case is not usable in production until a real Argon2id verifier,
  PostgreSQL ports, token generator/digest, rate limiter, and gateway adapter are
  implemented and integration-tested.

## Verification

- Tests cover successful session issuance, generic unknown/wrong/disabled
  rejection, dummy verification for absent accounts, no issuance on rejection,
  deterministic clock flow, command closure, callback-copy zeroing, and refusal
  after secret close.
- The application module compiles with Java 21 and warnings as errors without
  infrastructure dependencies.

## Rollback

No adapter invokes the new use case and no traffic/data path changes. Remove the
unused identity package without changing V1 or PostgreSQL schema.
