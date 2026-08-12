# ADR-0179: Immutable Windows Update-Channel Store

- Status: Accepted
- Date: 2026-08-12
- Owners: Windows release engineering and operations
- Related milestone: M4
- Extends: ADR-0176, ADR-0178

## Context

Publishing Setup, manifest, and signature independently can expose a partially
updated channel. Promotion needs all candidate bytes staged before one small
atomic activation operation, while staging itself must grant no traffic-change
authority.

## Decision

- Add a provider-neutral immutable store that accepts only a fully verified
  ADR-0176 candidate.
- Address each release directory by the SHA-256 of its canonical update
  manifest and retain the complete candidate, including Windows payload and
  evidence, rather than loose serving files.
- Copy through a sibling temporary directory, revalidate after copy, and rename
  atomically. Treat an identical existing release as idempotent; reject changed
  content, links, unsafe store boundaries, and identity collisions.
- Expose staging and validation only. Do not implement an active pointer,
  endpoint upload, network client, authorization consumption, or rollback in
  this component.

## Consequences

Release bytes can be prepositioned without making them visible to clients. A
future provider adapter can map fixed manifest/installer URLs to one active
immutable release and switch only a small pointer after ADR-0178 compare-and-
swap authorization. Retaining the complete candidate costs additional storage
but preserves independent audit evidence.

## Migration and Rollback

Staging changes no active channel. An unused immutable directory may be removed
according to retention policy; never edit it in place. Provider-specific stores
must preserve the same content-addressed and pre-stage-before-activate semantics.

## Verification

- `python3 Tests/windows_update_channel_store_test.py`
- verify idempotent staging, exact copied identity, tamper rejection, safe
  boundaries, and absence of activation/network capabilities.
