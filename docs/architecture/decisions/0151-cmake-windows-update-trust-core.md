# ADR-0151: CMake Windows Update Trust Core

- Status: Accepted
- Date: 2026-08-12
- Owners: project maintainers
- Related milestone: M4

## Context

The Windows updater's security model requires a fixed order: canonical Ed25519
verification, semantic eligibility, then atomic replay-state acceptance. Those
portable components were compiled repeatedly by qmake tests and directly into
the Widgets product. Migrating download, WinTrust, launch, lifecycle, and UI at
the same time would blur trust stages and make build equivalence harder to
review.

## Decision

- Build `UpdateManifestSignatureVerifier`, `UpdateManifestDecisionPolicy`,
  `UpdateStateRepository`, and `UpdateManifestApplicationService` as the static
  CMake target `chatroom_windows_update_trust_core`.
- Limit dependencies to Qt Core and libsodium. The library performs no network
  I/O, Authenticode verification, process launch, UI, or installer mutation.
- Compile the four existing focused test sources unchanged and register them as
  bounded `m4_update_*` CTests.
- Extend the unified CMake gate to execute both `v1_*` and `m4_*`; building a
  test without executing it is not release evidence.
- Keep qmake and the native Windows product target through later equivalence.
  Keep private Ed25519 keys outside source and build inputs.

## Consequences

The updater's portable root of trust now has a reusable link boundary below
transport and WinTrust. Tests cover canonical bytes, key IDs and tampering;
version/time/channel/architecture/rollout policy; owner-only atomic device and
per-channel high-watermarks; and the non-bypassable signature/decision/state
order. This does not activate an update channel or provide production keys.

## Migration and Rollback

Later CMake libraries may consume trust-core for discovery/preparation and
lifecycle orchestration without moving platform code into it. Reverting the
CMake target leaves qmake behavior and persisted update state unchanged. Do not
remove qmake coverage until the native Windows product target is equivalent.

## Verification

- Release-build trust-core on macOS and Ubuntu portability hosts;
- execute signature-verifier, decision-policy, state-repository, and complete
  manifest-application tests through CTest;
- retain TLS, reconnect, local-data, persistence, and real server-health gates;
- current unified result: 16/16 CTests pass on the macOS development host.
