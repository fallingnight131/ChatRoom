# V2 Message Forwarding Activation

Status: default-off M6 release contract for Web and Windows. Completing this
runbook does not by itself prove production readiness; retain the environment,
test, observability, and rollback evidence for the exact release candidates.
The invariant is gateway-first activation and client-first rollback.

## Independent gates

| Surface | Exact gate | Activation boundary |
| --- | --- | --- |
| Java gateway | `CHATROOM_GATEWAY_MESSAGE_FORWARDING_ENABLED=true` | process startup and each new V2 handshake |
| Web | `VITE_CHAT_V2_MESSAGE_FORWARDING=true` | immutable Vite candidate build |
| Windows | `CHATROOM_ENABLE_WINDOWS_V2_FORWARDING=ON` | immutable CMake Release build |

All gates default to disabled and reject malformed values. The Windows gate
also requires `CHATROOM_ENABLE_WINDOWS_V2_PREVIEW=ON`. A client requests
capability 5 only when its own gate is enabled; the server advertises it only
when both the gateway policy and that request are present. Existing connections
do not renegotiate after a gateway configuration change.

## Activation order

1. Apply and verify PostgreSQL migration V049 before routing a capable client.
2. Deploy the same gateway code with forwarding disabled. Confirm readiness,
   fixed-cardinality metrics, message history, and legacy-client behavior.
3. Set the gateway flag to exact `true`, choose reviewed bounded admission
   values, restart instances gradually, and confirm new capable test sessions
   negotiate capability 5. Keep user clients disabled at this point.
4. Exercise source and destination authorization, revision conflict, exact
   retry, rate limit, ACK-lost reconnect convergence, and legacy downgrade with
   non-production accounts. Logs and metrics must contain no account,
   conversation, source-message, or copied-content labels.
5. Promote an immutable Web candidate built with its exact flag, then a signed
   Windows Release candidate built with both preview and forwarding enabled.
   Canary each endpoint independently and retain candidate identities.
6. Observe forward accepted/rejected/rate-limited outcomes, message worker and
   PostgreSQL saturation, reconnect behavior, and support feedback before wider
   promotion. The configured limits are safeguards, not capacity claims.

Do not enable a client candidate before the gateway. Such a client must fail
closed when capability 5 is omitted, but that intentional protection would make
the preview unusable rather than provide forwarding.

## Rollback order

1. Stop promotion and restore Web and Windows candidates whose forwarding gates
   are disabled. Existing accepted destination messages remain ordinary durable
   messages and are not deleted or rewritten.
2. Wait for or terminate the bounded set of capable client sessions according
   to the incident plan. Confirm new client sessions request capabilities 1–4.
3. Set the gateway flag to exact `false` and restart instances gradually. New
   handshakes must omit capability 5; existing negotiated sessions retain it
   until disconnected, which is why client rollback happens first.
4. Preserve V049. Do not roll back or erase durable marker columns during an
   operational disable; old clients already project the copied text without the
   marker.

## Required release evidence

- exact gateway deployment revision and sanitized configuration digest;
- Web asset/candidate identity and the three public V2 build values;
- signed Windows candidate identity and CMake option evidence;
- capable Web and Windows handshake evidence plus an old-client downgrade test;
- authorization denial, revision race, idempotent retry, rate limit, offline
  replay, ACK-lost convergence, restart, and rollback results;
- fixed-label metrics before, during, and after the canary.

Never place credentials, user identifiers, message contents, signing keys, or
production endpoints in this evidence.
