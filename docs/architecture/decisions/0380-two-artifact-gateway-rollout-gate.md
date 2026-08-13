# ADR-0380: Two-Artifact Gateway Rollout Gate

- Status: Accepted
- Date: 2026-08-14
- Owners: project maintainers
- Related milestone: M5

## Context

ADR-0370 replaces a gateway using the same application classes. ADR-0379 makes
running release identity observable, but identity alone does not prove two
revisions can share PostgreSQL, Redis routing, HAProxy, and the V2 wire contract.
A trustworthy rolling gate must run actual independently built artifacts rather
than assign two labels to one classpath.

## Decision

- Pin the previous compatible baseline to full revision
  `1487e1f08992a1b4d10a3d5ece59b4fa8c935ac5`, the first release-identity
  implementation. Permit an explicit override for future compatibility windows.
- Require a clean candidate worktree and resolve both identities from Git commit
  objects. Export the previous tree with `git archive`; build separate Gradle
  application distributions for previous and candidate revisions.
- Start each distribution in its own JVM with exact SemVer/revision identity,
  the same compatibility epoch, independent ports, and shared disposable
  PostgreSQL/Redis. Verify each running `/identity` before sending traffic.
- Prove a real application delta: the previous baseline lacks the later
  `chat_gateway_release_info` metric while the candidate exposes it.
- Place both through the pinned HAProxy edge. Deliver ordered messages previous
  to candidate and candidate to previous, remove the previous JVM, reconnect to
  the candidate, repair both messages, and deliver the next sequence.
- Keep the expensive local-service/Docker gate explicit and outside `--all`.

## Consequences

Passing the gate will provide bounded evidence for one exact previous/candidate
pair and V2 messaging slice. It will not prove arbitrary release pairs, schema
contract migration, every feature handler, correlated failure, or production
capacity. A failed identity, build, placement, ordering, or repair assertion
blocks rollout compatibility for that pair.

## Verification

Run from a clean worktree:

```bash
python3 tools/verify_m0.py --gateway-mixed-version
```

The gate passed with previous revision
`1487e1f08992a1b4d10a3d5ece59b4fa8c935ac5` and candidate revision
`79ed828cdcfa5fd8af63922e16b92bae88d3d9b3`. Both running identities matched,
bidirectional sequences 1 and 2 crossed revisions, HAProxy removed the stopped
previous JVM, and the candidate repaired both messages before sequence 3.

## Rollback

Remove the explicit gate and selector. Keep release identity and the same-build
rolling, HAProxy reload, crash, and certificate gates. No product protocol or
schema changes.
