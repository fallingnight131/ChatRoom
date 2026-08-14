# ADR-0400: In-Window Resident-Memory Evidence

- Status: Accepted
- Date: 2026-08-14
- Owners: project maintainers
- Related milestone: M5

## Context

ADR-0399 defines a cached process-RSS boundary and fixed loopback metrics. The
dual-edge reconnect workload already samples that loopback endpoint every five
milliseconds, but schema 8 records only CPU, heap, GC, queue, PostgreSQL, and
event-loop observations. An idle RSS metric does not show provider availability
or the cached process footprint during the reconnect window.

The provider refreshes independently at a configured 250 ms interval. Repeated
five-millisecond observations may therefore contain the same cached value and
must not be described as native reads or independent RSS samples. Unsupported
hosts such as the current macOS configuration must remain valid evidence while
stating that RSS was unavailable.

## Decision

- Upgrade new raw dual-edge evidence to schema version 9 while preserving raw
  schemas 1 through 8.
- Add a dedicated `residentMemoryActivity` block containing the observation
  interval, configured provider refresh interval, shared sample count,
  unavailable sample count, cached bytes before/after/maximum, maximum sample
  age, and cumulative read failures before/after/delta.
- Require unavailable samples to report zero bytes. Fully unavailable windows
  must keep every byte field at zero; any window with an available sample must
  observe a positive maximum.
- Reconcile the read-failure counter monotonically. Do not interpret an
  unsupported provider with zero failures as a successful native read.
- Upgrade repeated aggregates to schema version 4 when all nine children use
  raw schema version 9. Preserve the earlier aggregate/raw schema pairs and
  reject mixed pairs.
- Include the RSS availability count, endpoint/maximum bytes, maximum sample
  age, and failure delta in each aggregate run summary. Do not use RSS as a
  pressure threshold in this slice.

## Consequences

Reconnect evidence can now distinguish measured RSS from an unmeasured
platform without inventing process memory values. The same evidence contract
works before a macOS native bridge exists and can later carry positive Linux or
reviewed macOS observations without another shape change.

The five-millisecond loop observes a 250 ms cache, so it cannot reconstruct
short RSS peaks or claim 200 independent native samples per second. RSS remains
subject to operating-system accounting and is not a container limit or a
production capacity result.

## Verification

Contract tests must accept both fully available and fully unavailable windows,
preserve raw schemas 1 through 9 and aggregate schemas 1 through 4, and reject
field drift, sample mismatch, impossible availability/byte combinations,
counter regression, incorrect deltas, and mixed aggregate children. A real
macOS `step-12` run must produce valid schema-9 evidence with all samples
unavailable and all resident-byte fields zero until a native provider is
separately approved.

## Rollback

Return new raw evidence to schema 8 and aggregate evidence to schema 3. Keep the
runtime RSS metrics and cached provider boundary. No product protocol, data,
readiness, admission, client, or deployment behavior changes.
