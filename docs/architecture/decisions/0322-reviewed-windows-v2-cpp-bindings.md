# ADR-0322: Reviewed Windows V2 C++ Bindings

- Status: Accepted
- Date: 2026-08-13
- Owners: project maintainers
- Related milestone: M6

## Context

The authoritative V2 Protobuf task generated C++ only under an ignored temporary
directory. That was sufficient for cross-language golden tests but not for a
reproducible Windows client build: the Qt product could not reference reviewed
bindings without first installing Java, Node, and the TypeScript generator.

## Decision

- Continue treating `Backend/protocol-v2/src/main/proto` as the only schema
  source.
- Publish the same generated C++ output into
  `Client/protocol/v2/generated/chat/v2` and commit it as reviewed source, just
  as Web commits generated TypeScript.
- Make the protocol gate snapshot both committed output trees, regenerate once,
  and fail when either byte set changes or is absent.
- Compile the Windows committed tree in the existing pinned C++ golden-wire
  test. Product CMake/qmake integration must use this tree and a locked native
  Protobuf runtime; hand-written wire encoding is forbidden.
- Generated files are never edited manually. Schema changes regenerate and
  review Java, TypeScript, and C++ together.

## Consequences

Windows builds no longer need the JVM/Node generation toolchain merely to
consume an already reviewed protocol revision. The repository grows by the
generated C++ sources, but stale or unilateral edits fail the binding gate.
This decision does not activate Windows V2 networking; WSS/session composition
remains a separate releasable step.

## Verification

- regenerate twice and require no committed Web or Windows binding change;
- compile the committed Windows C++ sources with the pinned Protobuf runtime;
- parse and deterministically re-encode all golden payloads, including device
  revocation, in Java, TypeScript, and C++.
