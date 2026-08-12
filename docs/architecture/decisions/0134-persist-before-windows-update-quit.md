# ADR-0134: Persist Before Windows Update Quit

- Status: Amended by ADR-0137
- Date: 2026-08-12
- Owners: project maintainers
- Related milestone: M4

## Context

ADR-0131 proves the helper owns the parent wait, while ADR-0133 persists the
request that will bind a result after restart. UI code must not independently
combine those operations or authorize exit when persistence failed.

## Decision

- Add an inactive install coordinator around the handoff service and lifecycle
  repository.
- Reject parallel installs. After a successful helper handshake, atomically
  record the generated request UUID, target version, and UTC creation time.
- Emit `quitAuthorized=true` only when both handshake and pending persistence
  succeed. Preserve the request UUID for diagnostics.
- On validation, handshake, or persistence failure, return an error and keep the
  client running. Do not terminate the helper or client.
- Keep user consent, network disconnect, window shutdown, and application quit
  outside this coordinator and inactive in this change.

## Consequences

The future UI receives one narrow fail-closed decision and cannot mistake a
detached process or volatile state for safe shutdown. If persistence fails after
handshake, the helper naturally reaches its bounded parent timeout while the
client remains available.

## Migration and Rollback

No product path is active. Rollback removes the coordinator and leaves handoff
and lifecycle primitives available independently.

## Verification

The coordinator test uses real private staging/lifecycle directories and an
injected platform handshake. It proves parallel refusal, UUID continuity into
pending state, quit authorization after durable persistence, and refusal when an
existing pending record prevents persistence. Full Qt verification builds it
into the client.
