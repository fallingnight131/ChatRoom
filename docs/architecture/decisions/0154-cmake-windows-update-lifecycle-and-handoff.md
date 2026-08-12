# ADR-0154: CMake Windows Update Lifecycle and Handoff

- Status: Accepted
- Date: 2026-08-12
- Owners: project maintainers
- Related milestone: M4

## Context

ADR-0153 ended at a trusted `PreparedInstaller`. The remaining update path must
survive the client exiting, transfer control to a short-lived helper, and
reconcile exact result evidence after restart. These responsibilities were
already separated in source, but qmake-only test assembly hid their dependency
direction and left the two-phase quit guarantee outside the CMake gate.

## Decision

- Build launcher-result parsing, lifecycle persistence, runtime path derivation,
  and startup reconciliation as `chatroom_windows_update_lifecycle`.
- Build the helper's strict one-shot argument parser as
  `chatroom_update_launcher_protocol` so both sides test the same closed
  contract without depending on the helper executable.
- Build helper runtime staging and install coordination as
  `chatroom_windows_update_handoff`, depending on update orchestration and
  lifecycle rather than duplicating either.
- Preserve the two-phase boundary: helper readiness comes first, durable pending
  state authorizes commit, and only successful commit authorizes normal client
  quit.
- Consume only UUID-bound, time-bounded, closed-schema launcher results once.
  Retain missing or invalid evidence and never infer successful installation.
- Confirm an `installed` result at startup only when the running client version
  exactly equals the pending target version.
- Allow injected launch/handshake functions only for portable sequencing tests.
  Native event handles, detached process launch, helper signatures, and Setup
  execution remain Windows-only release evidence.

## Consequences

The CMake graph now represents update preparation through durable handoff and
restart reconciliation while keeping persistence, protocol parsing, and process
mutation independently testable. Parallel pending updates and handoffs are
refused. Invalid runtime files cannot reach launch, persistence failure cannot
authorize quit, and failed staging/handshake paths clean their private run
directories.

This decision does not enable updates, embed product trust, build the helper
executable, or prove Windows process behavior.

## Migration and Rollback

The next slice may build the helper and Windows client product targets from
these libraries, but must retain the native unsigned-rejection and protected
signing gates. Reverting these CMake targets changes no qmake product artifact,
installed files, lifecycle schema, or runtime configuration.

## Verification

- execute six lifecycle/protocol/handoff suites through 30-second-bounded CTest;
- reject malformed/replayed/mismatched evidence and ambiguous helper commands;
- require durable pending state before quit authorization;
- reject parallel work and clean failed helper staging;
- current macOS focused result: 6/6 CTests pass;
- native Windows helper launch/install and positive signature evidence remain
  M4 product gates.
