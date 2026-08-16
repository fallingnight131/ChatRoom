# ADR-0409: Web Push Delivery Foundation

- Status: Accepted
- Date: 2026-08-17
- Owners: project maintainers
- Related milestone: M6

## Context

ADR-0407 deliberately limits Web notifications to an open browser page. Closed-
browser delivery needs a Service Worker, browser push subscriptions, a provider
delivery protocol, durable asynchronous work, expiry handling, abuse controls,
and operational ownership. Calling a push provider inline from message
persistence or from a Netty event loop would couple chat availability to an
external service and make retries unsafe.

Push subscription endpoints and key material are credentials. Message text,
sender names, conversation names, membership, and user identifiers must not be
exposed to provider logs, lock screens, metrics labels, or operator evidence.
Delivery is inherently at least once and can be delayed beyond membership or
preference changes, so every asynchronous boundary needs reauthorization and a
bounded lifetime.

## Decision

### Ownership and topology

- Add Web Push under the target Notification module, outside the IM gateway.
  The first deployment may share the modular-monolith process, but subscription
  HTTP APIs, durable scheduling, and provider delivery remain explicit ports.
- The authenticated HTTP API owns subscription create/replace/delete. Do not
  send push endpoints or key material through chat envelopes. Activation waits
  for a server-issued HTTP session/token, strict Origin policy, CSRF protection
  where applicable, and account/device binding.
- Message acceptance writes one bounded notification outbox event in the same
  PostgreSQL transaction as durable message truth. It never calls a provider.
  A bounded worker claims outbox rows, resolves current eligible recipients and
  active subscriptions, rechecks membership/block/preference policy, and then
  invokes a provider port. A broker may replace PostgreSQL polling only after an
  observed scaling need and an operations plan.

### Subscription and secret model

- A subscription belongs to one authenticated account plus one opaque browser
  installation ID. Endpoint uniqueness transfers only after authenticated
  replacement; it must never reveal a previous owner.
- Store endpoint, P-256 ECDH public key, and authentication secret encrypted at
  rest through an injected key-management boundary. Logs, traces, metrics,
  diagnostics, and retained evidence contain none of these values or their
  hashes. VAPID private keys and encryption keys never enter the repository.
- Bound endpoint/key sizes, canonical HTTPS shape, subscriptions per account,
  replacements per installation, and mutation rate. Delete immediately on
  explicit opt-out or provider `404`/`410`; quarantine repeated authentication
  failures. Expire unused subscriptions after a documented maximum lifetime.

### Event and delivery semantics

- Only a newly persisted user-visible message can create an outbox event. ACKs,
  history/sync repair, edits, recalls, reactions, self echoes, retries that find
  an existing message, and cache activity do not.
- Outbox identity is stable and unique per accepted message. Provider attempts
  are bounded, exponentially delayed with jitter, and stop at event expiry.
  Delivery is best effort and at least once; the Service Worker deduplicates the
  opaque notification ID.
- The encrypted payload contains a version, opaque notification ID, stable
  conversation/message IDs for client navigation/deduplication, and a mention
  boolean. It contains no message body, sender/account identity, display name,
  conversation title, avatar URL, or attachment metadata. Visible title/body
  remain localized generic copy, matching ADR-0407.
- Before every provider attempt the worker rechecks that the recipient account
  is enabled, remains an active conversation member, is not the sender, has an
  enabled subscription/preference, and is not denied by current privacy policy.
  Expired or ineligible events are terminal without delivery.
- Fixed-cardinality telemetry records queued, claimed, delivered, transient,
  expired, invalid-subscription, ineligible, and saturated outcomes plus bounded
  latency/backlog gauges. No user, conversation, message, endpoint, provider
  response body, or subscription label is allowed.

### Client and activation

- Service Worker registration, permission, subscription upload, and push event
  handling use a separate exact-default-false Web build gate. A user gesture is
  still required; browser permission alone never registers server delivery.
- The Service Worker validates payload version and bounds, displays generic
  copy, deduplicates notification IDs, and opens/focuses only the supported Web
  origin with an opaque conversation route. The page reauthorizes and loads
  current server truth; push payload data never becomes message truth.
- Activate message outbox production first with delivery workers off, then a
  provider canary, then the Web subscription UI. Roll back client subscription
  creation first, stop workers second, and stop new outbox production last. Keep
  schema and let bounded rows expire; never delete message truth as notification
  rollback.

## Consequences

This adds PostgreSQL outbox and secret-bearing subscription data, so migrations,
backup/restore, key rotation, retention, and erasure become release gates. Push
cannot be implemented as a small extension to the existing in-page presenter.
The provider may receive delivery metadata such as timing, endpoint, IP, and
payload size even though the payload is encrypted; privacy documentation must
state that limitation.

Generic copy sacrifices previews but keeps lock-screen and provider exposure
bounded. Reliable chat remains independent: provider outage, worker saturation,
or invalid subscriptions may delay/drop push but cannot reject message
acceptance, stall synchronization, or reduce gateway readiness.

## Verification

ADR-0410 refines the previously open server-issued HTTP-session requirement:
an authenticated, capability-gated WSS command issues an independent,
session-bound, short-lived bearer/CSRF pair on demand. It does not reuse the WSS
resume proof or activate a gateway/client path.

- The first detached application slice locks defensive credential ownership,
  callback-copy zeroing, redacted rendering, canonical HTTPS/P-256/auth bounds,
  stable message identity, a 24-hour maximum event lifetime, and an explicit
  default-disabled policy. It intentionally has no runtime composition.
- Migration V053 is the expand-only storage step: it accepts ciphertext-only
  subscription columns and a keyed endpoint lookup tag, and constrains the
  payload-free outbox lifetime/claim/retention shape. No writer is composed yet.
- The detached PostgreSQL subscription adapter accepts an injected, context-
  bound protection result, performs same-transaction endpoint ownership
  transfer/replacement, scopes deletion by account/install, and clears protected
  working bytes. Account locking rejects unavailable accounts and enforces a
  10-install server quota without blocking existing-install rotation. The
  fixture protector is not a production key-custody claim.
- The message adapter's ordinary constructor remains default-off. Its explicit
  enabled policy writes one payload-free row in the new-message transaction;
  duplicate replay writes none, and a forced outbox failure rolls back all
  message/sequence/event state. No composition selects it yet.
- The detached outbox adapter uses bounded `SKIP LOCKED` claims fenced by owner,
  claim ID, and lease expiry. Retry, terminal completion, expiry, and retention
  are bounded and tested concurrently; no scheduler or provider is composed.
- The detached crypto adapter uses AES-256-GCM with fresh nonces and context/
  purpose/key-ID AAD, plus a separately keyed HMAC-SHA256 endpoint tag. It can
  resolve old encryption key IDs for rotation. ADR-0411 adds strict mounted-file
  custody with clearable callback copies. Runtime path-only composition now
  selects an active/prior key ring; lookup-key rewrite, backup/restore, and
  rotation rehearsal remain open gates.
- The subscription application use case consumes an account-free, zeroable
  request, binds only its authenticated caller, applies exact default-off and
  admission before protection, returns fixed outcomes, and closes secrets on
  every path. The detached HTTP contract adds an exact-default-off policy, one
  bounded exact canonical-HTTPS Origin, a fixed server-session/CSRF decision,
  and strict bounded JSON decoding with transport-byte clearing. A detached
  Netty handler now enforces that contract, allows one
  bounded request per connection, moves session/CSRF/decode/mutation work off
  the event loop, clears transport secrets, and maps only fixed statuses and
  identity-free outcomes. A second exact runtime gate now installs it before
  WebSocket upgrade with the PostgreSQL credential bridge, mounted-file custody,
  bounded per-process mutation admission, and the shared bounded messaging pool.
  ADR-0410 owns the token implementation and bridge.
- The Web pure payload boundary accepts only schema 1, three canonical UUIDs,
  and a mention boolean within 2 KiB. It derives generic-copy presentation and
  exact-HTTPS-origin V2 navigation without accepting message content. Service
  Worker events, registration, PushManager, HTTP upload, UI, and browser gates
  remain open.
- An injectable worker runtime handles validated push display and revalidates
  click targets before focusing/navigating one same-origin client or opening a
  window. It installs no global listener until the future exact-gated entry is
  built, so registration and product behavior remain off.
- The detached browser controller requires no key or installation identity while
  disabled. Its explicit gesture path orders permission, worker registration,
  existing-or-new subscription, and authenticated-port replacement; new local
  state is rolled back on upload failure. Disable deletes server authority first
  and only then unsubscribes locally. Fetch credentials, global entry, and UI
  remain uncomposed.
- The detached Web HTTP adapter acquires bearer/CSRF only through one short-lived
  lease per mutation, validates the subscription before that lease, and pins a
  credential-omitting, no-redirect/no-cache/no-referrer request to the exact
  HTTPS product origin. It exposes fixed outcomes and discards response bodies.
  ADR-0410 now composes the exact-gated WSS issuer, and the second server gate
  composes the subscription route. A detached Web V2 transport lease contains
  credential issuance and clearing, while default runtime/UI composition remains
  absent.
- A Vite module-worker entry installs the validated event runtime, while a
  detached browser adapter requires secure Notification/ServiceWorker/
  PushManager capabilities, validates local URL/scope, registers explicitly,
  reuses current subscription state, and requests only user-visible push with a
  copied public key. The default app does not import or register it.
- PostgreSQL recipient resolution now starts from the exact committed message,
  rechecks current membership/account/bilateral-block/recall truth, and returns
  a complete ordered result or explicit saturation. Active subscription reads
  remain ciphertext-only and batch-closeable, clear JDBC copies, and derive
  mention state from outbox truth. No provider worker is composed.
- The detached worker application service is default-off, bounded by recipient/
  installation caps, reauthorizes each attempt, exposes only generic stable-ID
  provider commands, erases invalid installs, fences retry/completion, and emits
  fixed-cardinality events. Detached lock-free counters render only fixed,
  label-free Prometheus series, while exponential retry is jittered, capped at
  15 minutes, and clipped before event expiry. An explicit delivery loop keeps
  claim/provider work off its scheduler, enforces one bounded pass in flight,
  validates claim ownership, isolates claims, applies capped polling/backoff,
  and cancels pending scheduling on close. Real provider, owned executors,
  readiness policy, metrics endpoint, and runtime composition remain open.
- application tests for eligibility, self/duplicate suppression, current-policy
  reauthorization, expiry, stable outbox identity, and no inline provider call;
- PostgreSQL migration/restart/constraint, concurrent claim, exact retry,
  retention, erasure, and backup/restore rehearsal;
- fake-provider tests for success, `404`/`410`, authentication failure,
  transient retry/backoff, timeout, saturation, and secret-free diagnostics;
- Chromium/Firefox Service Worker tests for explicit opt-in/out, payload bounds,
  generic copy, duplicate delivery, notification click/open/focus, account
  switch, revoked membership, and offline page startup;
- a real-provider canary with exact revisions, sanitized configuration digests,
  key-rotation/rollback evidence, and no message-capacity claim.

## Rollback

Disable new Web subscription creation and unregister/disable the Service Worker
candidate. Stop provider workers, then disable new notification outbox events.
Preserve subscriptions only according to the approved retention policy and keep
the outbox schema; allow bounded pending events to expire or terminally cancel
them. No chat message, cursor, membership, or client message cache is rewritten.
