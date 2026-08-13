# ADR-0363: Post-Response Conversation Route Activation

- Status: Accepted
- Date: 2026-08-14
- Owners: project maintainers
- Related milestone: M5

## Context

Starting the distributed relay before product subscriptions publish Redis routes
would allow an outbox event to resolve an empty target set and be marked
published. Publishing the route inside the initial history read is also unsafe:
the hint consumer or second repair could send live events before the client sees
the history response that defines its cursor.

## Decision

- Extend the live-router boundary with a post-history-response activation hook.
- Keep the per-channel messaging command serialized until the history response
  flush succeeds and activation completes off the Netty event loop.
- Close the connection if response flush, worker admission, route publication,
  or second repair fails. A successful history response without a reliable live
  activation must force reconnect/synchronization rather than silently degrade.
- Add `DistributedConversationLiveRouter` as a decorator around the existing
  local router.
- After the response flush, publish the Redis route at the channel's observed
  contiguous sequence, then perform a bounded authoritative PostgreSQL repair.
  Read at most ten pages of 100 entries, require monotonic page/message order,
  reauthorize from the server-bound account on every page, and fail closed on
  denial, no progress, malformed order, or excessive catch-up.
- Roll back the local conversation subscription if external publication or
  repair fails.
- Retain one external route while any local channel remains subscribed and
  remove it best-effort when the last channel leaves; lease expiry remains the
  safety net.
- Keep process-local publish behavior unchanged and do not yet construct this
  decorator in the product server.

## Consequences

The activation sequence is now history response → route visibility → second
authoritative repair. Events committed before route visibility are recovered
from PostgreSQL; events committed after visibility can arrive as payload-free
hints. No Redis payload authorizes or supplies message content.

The decorator is not enough for production activation: conversation routes
still need periodic renewal while subscriptions remain active, and the runtime
composition/readiness path remains unconnected.

## Verification

Handler tests prove the history response is present before activation work runs.
Decorator tests prove route publication followed by PostgreSQL repair and local
delivery, rollback on publication rejection, and last-subscriber route removal.
Existing messaging handler tests preserve response, serialization, capability,
and live-publication behavior.

## Rollback

Remove the activation hook and unconstructed decorator. Keep distributed relay
disabled; otherwise an empty-route publication could lose cross-gateway live
delivery.
