# ADR-0396: Duration-Aware Reconnect Ladder Classification

- Status: Accepted
- Date: 2026-08-14
- Owners: project maintainers
- Related milestone: M5

## Context

ADR-0394 classifies any positive authentication queue, PostgreSQL waiter, or
Netty pending-task peak as a run-level pressure signal. ADR-0395 shows that the
same peak may exist in only one five-millisecond sample. Continuing to call an
isolated sampled peak repeated pressure would discard the duration evidence and
overstate local contention.

Schema-1 aggregate evidence is already committed and must remain reproducible
under its original rules. New schema-7 child records can support a separately
versioned, more conservative aggregate without rewriting that history.

## Decision

- Emit aggregate schema version 2 when all child records use raw schema version
  7. Preserve aggregate schema version 1 only for uniform schema-6 children.
- Retain peak signal counts as diagnostic context.
- Count authentication queue, PostgreSQL wait, or Netty pending-task pressure
  as sustained only when the longest consecutive positive streak reaches at
  least two samples (a target-cadence observation of about 10 ms).
- Continue to treat a latest event-loop lag of at least one 50 ms probe period
  or normalized process CPU of at least 0.8 as a run-level sustained signal;
  those existing rules already represent a window-scale delay or utilization.
- Require sustained pressure in at least two of three runs before reporting the
  first repeated sustained-pressure profile.
- Preserve the existing latency candidate rule: median P95 must be both at
  least 2x the `step-12` median and at least 10 ms higher. Repeated sustained
  pressure takes precedence in the conclusion.
- If no rule triggers, report only that no sustained pressure knee was observed
  within the fixed ladder. Never infer safe capacity from that outcome.

## Consequences

An isolated positive sample remains visible but no longer determines the
schema-2 conclusion. This makes the local interpretation less sensitive to one
metrics read while retaining evidence needed to tune the next experiment.

Two sampled points are still not proof of operationally meaningful saturation.
The target cadence can drift, three repetitions have low statistical power,
and the rule omits queue magnitude integration, RSS, GC, host contention and
production quotas. It is deliberately a candidate-pressure classifier, not a
capacity estimator.

## Verification

Contract tests must continue to reproduce the committed schema-1 aggregate,
accept uniform schema-7/schema-2 evidence, ignore one-sample peaks for sustained
classification, accept two-sample streaks, preserve latency rules, and reject
mixed aggregate/child schemas or tampered conclusions.

## Rollback

Stop producing schema-2 aggregates and retain raw schema-7 children for manual
inspection. Historical schema-1 evidence remains valid. No production runtime,
protocol, data, admission, readiness, or deployment behavior changes.
