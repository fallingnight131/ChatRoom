# ADR-0032: Java V2 Workspace Boundaries

- Status: Accepted
- Date: 2026-08-11
- Related milestone: M3

## Context

M0-M2 stabilized V1 and established durable Web/Windows client foundations.
M3 must introduce Java without turning the migration into an all-at-once server
replacement or recreating transport and persistence coupling inside a new
language.

## Decision

- Add an independently buildable Gradle workspace under `Backend/` using the
  checked-in Gradle 8.14.3 wrapper and a Java 21 toolchain/release target.
- Start with three dependency-direction boundaries:
  - `protocol-v2` owns the V2 wire-schema generation and compatibility surface;
  - `application` owns transport- and persistence-neutral use cases and grows
    feature packages for identity, conversations, messaging, contacts, groups,
    attachments, notifications, and administration;
  - `im-gateway` is the independently runnable long-lived connection adapter
    and may depend on `protocol-v2` and `application`, never the reverse.
- Add Spring Boot/HTTP, Netty, PostgreSQL, and generated Protobuf code only in
  vertical slices that use them. Empty infrastructure dependencies are not a
  substitute for an executable feature.
- Keep the C++ V1 server authoritative until a documented slice-specific
  compatibility and data cutover gate passes. The Java workspace does not yet
  receive production traffic or own durable data.
- Treat compiler warnings as errors and run all module tests in CI on JDK 21.

## Compatibility and Rollback

This workspace is additive and has no runtime route from existing clients.
Rollback is deletion or disabling of the Java build job; the C++ V1 server and
SQLite data remain unchanged. A later slice that introduces traffic or durable
PostgreSQL state requires its own migration and rollback decision.

## Consequences

- Module dependency direction is visible before framework code arrives.
- The gateway can scale independently later without forcing in-process domain
  modules into premature network services.
- Java 21 is the reproducible production baseline even when a developer host
  also has a newer JDK installed.
- More domain modules may be extracted from `application` when ownership or
  dependency evidence justifies a Gradle boundary.

## Verification

- `Backend/gradlew -p Backend check` compiles every module with Java 21 and runs
  the initial boundary smoke tests.
- CI runs the same command on Ubuntu with a Temurin 21 toolchain.
- Existing V1/Web/Qt verification remains unchanged and authoritative.
