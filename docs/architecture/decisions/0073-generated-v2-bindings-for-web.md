# ADR-0073: Generated V2 Bindings for Web

- Status: Accepted
- Date: 2026-08-12
- Related milestone: M3

## Context

The protocol gate generated temporary TypeScript for compatibility tests, while
the supported Web product had no importable V2 schema. Hand-copying envelope or
payload codecs into Web would create a second authority and allow silent drift.
Requiring every Web-only build to install Gradle/protoc would also couple normal
frontend feedback to the backend toolchain.

## Decision

- Extend the checksum-pinned `generateClientBindings` task to publish the same
  protoc-gen-es output into `WebClient/src/protocol/v2/generated`.
- Commit that Web output as reviewed generated source. Keep the backend C++/test
  output ephemeral.
- Pin the Web runtime to `@bufbuild/protobuf` 2.13.0, matching the generator.
- Make the protocol-binding gate snapshot the committed Web files before
  generation and fail if regeneration changes, adds, or removes a binding.
- Keep application policy and connection state outside generated files; never
  edit generated files by hand.

## Consequences

- Vite/Web builds consume V2 schemas without Java/Gradle at normal frontend build
  time, while schema changes still require one authoritative regeneration.
- Generated source adds repository volume but eliminates handwritten binary
  codecs and makes protocol diffs reviewable.
- This step does not switch the live Web transport from V1; a later adapter and
  rollout decision consume these bindings.

## Verification

The protocol gate regenerates Java/C++/TypeScript, proves committed Web output is
unchanged, runs the TypeScript golden suite, and compiles/runs C++. The Web gate
runs the existing 45 tests and a production Vite build with the pinned runtime.

## Rollback

Remove the unconsumed Web generated directory/dependency and secondary output
from Gradle. The authoritative Protobuf schemas and backend compatibility tests
remain unchanged.
