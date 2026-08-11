# ADR-0082: Default-off Web V2 Preview Composition

- Status: Accepted
- Date: 2026-08-12
- Related milestone: M3

## Context

The Web V2 protocol, transport, cache, and application coordinator are tested in
isolation, but the supported Web product still runs V1. Statically importing the
V2 stack nearly doubled the initial JavaScript bundle during local verification
and would place inactive migration code on every V1 page load. A premature
connection could also send production traffic before UI, migration, operations,
and rollback gates are ready.

## Decision

- Keep V1 as the default and supported Web path. V2 is enabled only when the
  exact build-time value `VITE_CHAT_V2_PREVIEW=true` is present.
- Require an exact `wss://.../v2/web` endpoint and a bounded application version.
  Invalid or incomplete configuration fails closed to a disabled runtime.
- Load the V2 composition root through a dynamic import. The default build must
  not contain the V2 runtime chunk in its initial asset graph.
- Construct but do not start the V2 application. A later preview UI owns the
  explicit start/authentication lifecycle, so this slice sends no V2 traffic.
- Store only a random non-secret device UUID under the isolated
  `chat.v2.device-id` key. If browser storage is unavailable, use a page-lifetime
  identity. Never persist credentials, session IDs, or resume proofs here.
- Provide the lazy runtime to Vue through a readonly shallow reference and
  dispose it on page exit, including the asynchronous-import race.

## Consequences

Operators can produce an isolated preview build without changing the V1 product
configuration. V2 code becomes a separate chunk only in that build. The device
identifier is privacy-relevant client state and should be cleared with site
data, but it is not authentication authority. Enabling the build flag alone does
not activate a connection or constitute a production cutover.

## Verification

Unit tests cover default-off behavior, invalid WSS/configuration rejection,
stable and storage-denied device identity, malformed UUID containment, and the
lazy source boundary. Both default and explicitly enabled production builds are
run: the default initial bundle remains V1-only, while the preview emits a
separate V2 runtime chunk.

## Rollback

Deploy assets built without `VITE_CHAT_V2_PREVIEW=true`, or restore the prior Web
asset version. The V1 server, store, routes, and browser database are unchanged;
the isolated V2 IndexedDB database and non-secret device key may be deleted as
ordinary site data.
