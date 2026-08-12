# ADR-0153: CMake Windows Update Orchestration

- Status: Accepted
- Date: 2026-08-12
- Owners: project maintainers
- Related milestone: M4

## Context

ADRs 0151 and 0152 established separate CMake targets for signed-manifest trust,
network transport, and installer trust. The existing preparation and complete
check services composed those stages, but still depended on qmake source lists.
Leaving that orchestration outside the executed CMake graph made it easier for a
future product target to reorder or bypass a trust stage accidentally.

## Decision

- Build `UpdatePreparationApplicationService` and
  `UpdateCheckApplicationService` as
  `chatroom_windows_update_orchestration`.
- Depend on the trust-core, update-transport, and installer-trust libraries
  instead of copying their source files into consumers.
- Preserve the order boundary: fetch returns untrusted manifest bytes; signature,
  semantic policy, and atomic replay acceptance precede installer download;
  background installer trust precedes a typed `PreparedInstaller` handoff.
- Register the existing preparation and complete-check suites as bounded
  `m4_update_*` CTests.
- Permit injected trust functions only as a test seam. Production construction
  retains the platform verifier; a non-Windows injected success is sequencing
  evidence, not Authenticode evidence.
- Keep UI, compiled product keys/channels, helper process launch, lifecycle
  persistence, and normal client quit outside this target.

## Consequences

The portable CMake graph can now verify the complete asynchronous decision and
preparation pipeline without adding Windows mutation or UI dependencies. Invalid
signatures and zero-rollout decisions cannot reach Setup download. Trust failure,
cancellation, destruction, or competing work cannot expose a prepared installer
and must clean any private staging file.

Native Windows signature, timestamp, publisher, final-byte, helper launch,
install, upgrade, and clean-host evidence remain separate product gates.

## Migration and Rollback

The next migration slice may link lifecycle and helper-handoff services to this
library while retaining their separate persistence and process boundaries.
Reverting this CMake target changes no qmake product output, database state,
update configuration, or runtime behavior.

## Verification

- build both application-service tests through the root CMake graph;
- run them as 30-second-bounded CTests;
- cover valid readiness, invalid signature, zero rollout, trust rejection,
  parallel refusal, cancellation, destruction, and staging cleanup;
- current macOS focused result: 2/2 orchestration CTests pass;
- full native Windows release evidence remains an M4 gate.
