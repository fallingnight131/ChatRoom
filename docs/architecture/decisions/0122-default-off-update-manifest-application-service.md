# ADR-0122: Default-Off Update Manifest Application Service

- Status: Accepted
- Date: 2026-08-12
- Owners: project maintainers
- Related milestone: M4

## Context

The signature verifier, semantic policy, and durable replay repository are
individually fail closed, but exposing them separately permits future transport
code to call semantic decisions with an object that was not just verified or to
forget the atomic replay acceptance. ADR-0119 explicitly required a future
application boundary to make verification order impossible to bypass.

## Decision

- Add one inactive application service whose only operation performs, in order:
  canonical Ed25519 verification, durable state load/create, semantic decision,
  then locked atomic sequence/digest acceptance.
- Do not create device/replay state for an untrusted or invalid signature.
- Return rejection for signature, state, semantic, concurrent replay, or
  persistence failure. Preserve all non-rejected policy outcomes and bounded
  installer metadata without starting any action.
- Inject the trusted key ring and state directory. Keep both empty/unselected in
  the product; no global fallback key, path, endpoint, or feature flag exists.
- Keep download, payload trust, UI, scheduling, telemetry, and installer launch
  outside this service.

## Consequences

Future manifest transport has one reviewed entry point and cannot accidentally
split trust establishment from replay acceptance. Identical requests remain
idempotent; a concurrent higher sequence causes the older decision to fail
closed at atomic acceptance.

The service still does not enable updates. Production key custody and embedding,
Windows AppData selection, HTTPS retrieval limits, installer verification,
launch/rollback UX, and operational evidence remain M4 release decisions.

## Migration and Rollback

No product path invokes the service, so rollback removes it and its tests. Once
activated, callers must not bypass this boundary; any change to ordering or
acceptance semantics requires a security ADR.

## Verification

- an ephemeral trusted key produces an eligible decision and durable watermark;
- identical signed input is idempotent with stable device identity;
- a valid lower-sequence manifest is rejected after acceptance;
- tampered and empty-trust signatures are rejected before durable state creation.
