# ADR-0347: Slow-Consumer Backlog Observation

- Status: Accepted
- Date: 2026-08-14
- Owners: project maintainers
- Related milestone: M5

## Context

The gateway already closes an unwritable live subscriber without blocking
healthy peers and exposes a fixed closure counter. The real TLS baseline records
how many maximum-size messages were sent before that action. That message count
depends on the kernel, TLS implementation, client buffering, receiver demand,
and host scheduling, so it is not portable enough to compare hosts or tune a
write watermark.

Netty exposes `Channel.bytesBeforeWritable()` after a channel crosses its write
watermark. It reports the bytes that must drain before the channel becomes
writable again. It does not require internal outbound-buffer APIs and can be
observed at the existing server-authoritative close decision.

## Decision

- At every live publication path, sample `bytesBeforeWritable()` immediately
  before closing an unwritable subscriber.
- Carry only the maximum value from one publication result into messaging
  telemetry. Do not attach account, device, session, peer, conversation, or
  channel labels.
- Retain a process-lifetime maximum with a thread-safe accumulator and export it
  as the gauge
  `chat_gateway_messaging_slow_consumer_maximum_bytes_before_writable`.
- Keep the existing closure counter. A valid close may report zero when
  unwritability was set for a reason other than the configured byte watermark;
  the real socket benchmark must require a positive value.
- Treat this value as a host/runtime observation, not total pending bytes, a
  per-connection queue gauge, a release threshold, or a capacity claim.

## Consequences

Operators and retained benchmarks can compare the actual byte-watermark action
without high-cardinality identity data. The metric is monotonic for one process,
so a deployment must compare fresh instances or reset by restart; it cannot show
the current backlog after recovery.

The router still closes immediately once a channel is observed unwritable. This
change does not add buffering, change write watermarks, alter delivery order, or
replace sequence-history repair.

## Verification

- router tests cover unwritable closure and result invariants;
- handler tests retain publication/closure accounting across every event type;
- admin tests prove the fixed gauge and absence of identity labels;
- schema-9 slow-consumer evidence requires a positive real-socket observation
  while preserving all historical schema-4 recovery guarantees.

## Rollback

Remove the result field, accumulator, and gauge. The existing unwritable close,
counter, durable history, and client recovery behavior are unchanged.
