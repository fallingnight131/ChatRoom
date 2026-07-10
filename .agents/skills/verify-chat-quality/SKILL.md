---
name: verify-chat-quality
description: Define and execute risk-based quality checks for this chat system. Use for authentication, permissions, files, database or protocol changes, performance work, load testing, reconnection and failure handling, observability, release readiness, security reviews, migration verification, or any claim about reliability and scale.
---

# Verify Chat Quality

## Classify Risk

Read `/AGENTS.md` and sections 13-14 of `/docs/architecture/README.md`. Classify
the change:

- critical: credentials, authorization, message durability/order, schema
  migration, file access, signing/updater;
- high: connection lifecycle, synchronization, group broadcast, cache routing,
  installer upgrade;
- normal: isolated UI behavior or non-authoritative presentation.

Increase verification depth with risk. Do not reduce critical verification to a
successful compile.

## Build a Test Matrix

Cover applicable dimensions:

- happy path and explicit rejection;
- old/new protocol combinations;
- retry, duplicate, reorder, disconnect, reconnect, and process restart;
- unauthorized user, wrong conversation, expired token, oversized input, and
  rate limit;
- empty, boundary, Unicode, and large-history data;
- clean database, migrated database, failed migration, and restore;
- fast client, slow consumer, and unavailable dependency.

For client changes, include offline cache, optimistic reconciliation, local-data
upgrade, high-DPI/accessibility, clean install, upgrade, and uninstall.

## Measure Performance Honestly

Record hardware, build mode, dataset, connection model, duration, workload,
message/group distribution, and tool configuration. Measure P50/P95/P99 latency,
throughput, errors, CPU, memory, database latency, queue depth, reconnect rate,
and outbound socket backlog.

Include reconnect storms, large groups, slow consumers, and partial dependency
failure. Compare against a stored baseline. Never infer production capacity from
a unit benchmark or average latency.

## Verify Security

Confirm that identity, membership, role, message timestamp/sequence, and file
authorization are decided server-side. Check secret exposure, password/token
storage, TLS assumptions, upload scope, path handling, input limits, abuse
controls, and administrative audit behavior.

Use modern password hashing and migration; never retain plaintext passwords in
client storage. Treat update manifests and desktop packages as security-sensitive
artifacts requiring signature verification.

## Require Observability

At new critical boundaries, add correlation IDs, structured errors, latency
histograms, success/failure/retry/duplicate counters, saturation metrics, and
actionable logs without secrets. Ensure operators can distinguish client error,
authorization failure, dependency failure, and internal defect.

## Report Evidence

Run the relevant web, Qt, Java, protocol, migration, and packaging checks listed
in `/AGENTS.md`. Report exact commands and outcomes, state untested areas, and
separate measured behavior from design expectations.
