---
name: guide-chat-architecture
description: Guide safe, incremental architecture evolution for this chat project. Use when planning or implementing Java backend migration, service or module boundaries, database and cache changes, deployment topology, large refactors, architecture documents, ADRs, or any change spanning multiple server/client components.
---

# Guide Chat Architecture

## Establish Context

1. Read `/AGENTS.md`.
2. Read `/docs/architecture/README.md` and the relevant milestone in
   `/docs/architecture/ROADMAP.md`.
3. Inspect the active implementation; treat `/DESIGN.md` as legacy when it
   conflicts with code or the architecture source of truth.
4. Identify affected quality attributes and existing compatibility promises.

## Choose the Smallest Evolution Step

Prefer a releasable vertical slice with a rollback path. Do not propose a
big-bang Java rewrite or premature microservices.

Default toward:

- a Java modular monolith plus an independently scalable Netty gateway;
- in-process domain/application boundaries before network services;
- PostgreSQL as durable server truth;
- Redis for reconstructable presence, routing, rate limiting, and cache;
- object storage for file bytes;
- a durable broker only after multi-instance or asynchronous requirements are
  demonstrated.

Preserve V1 clients through a gateway adapter. Keep legacy translation at the
boundary rather than adding V1 conditions throughout the V2 domain.

## Define the Change

Document:

- current evidence and bottleneck;
- desired user outcome;
- owning module and data owner;
- API/protocol effect;
- migration and rollback;
- security and failure behavior;
- tests, metrics, and release evidence.

Create an ADR from `/docs/architecture/decisions/0000-template.md` when changing
a technology choice, durable model, service boundary, protocol guarantee,
security model, or distribution channel.

## Protect Boundaries

- Keep transport objects outside domain logic.
- Keep SQL and vendor SDKs behind infrastructure interfaces.
- Keep durable and ephemeral state distinct.
- Do not turn each module into a service without a scaling, isolation,
  availability, or ownership reason.
- Extract focused behavior when touching oversized `ChatServer`,
  `DatabaseManager`, `ChatWindow`, or web chat-store areas.

## Complete the Slice

Run relevant builds and compatibility tests. Update the target architecture,
roadmap milestone, ADR status, deployment instructions, or protocol docs whenever
the implemented truth changes. Report measured results and unresolved risks; do
not report speculative capacity as achieved performance.
