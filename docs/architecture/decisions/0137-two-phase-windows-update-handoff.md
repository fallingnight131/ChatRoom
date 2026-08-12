# ADR-0137: Two-Phase Windows Update Handoff

- Status: Accepted
- Date: 2026-08-12
- Owners: project maintainers
- Related milestone: M4
- Amends: ADR-0129, ADR-0131, ADR-0134

## Context

The one-phase helper signaled that it owned the parent wait before the client
persisted pending lifecycle state. If persistence then failed, the client stayed
open, but a normal user exit within the helper's two-minute parent window could
still launch Setup without a durable request to bind its eventual result.

This race was found before product discovery or installation was enabled. It
must be closed before consent UI can call the coordinator.

## Decision

- Extend the strict launcher command from nine to ten option/value pairs with a
  UUID-derived `Local\\ChatRoom.UpdateLauncher.Commit.<uuid>` event.
- The helper opens both ready and commit events before signaling ready. It waits
  at most 15 seconds for commit before it starts the parent-exit wait. Missing or
  failed commit writes a normalized `handoff-aborted` result and exits without
  verifying or starting Setup.
- The client creates both events before starting the helper. After ready, it
  invokes the coordinator's commit authorizer in the worker. The authorizer
  atomically persists the exact pending UUID/version/time.
- Signal commit and return `readyToQuit=true` only after authorization succeeds.
  Authorization or `SetEvent` failure keeps the client running; the helper
  reaches bounded abort instead of installing.
- Add `handoff-aborted` to the closed schema-1 result outcome set with exit code
  zero. Update native unsigned-launcher CI to signal commit explicitly.
- No backward compatibility adapter is required because the product invocation
  path and production key/origin were never enabled. Copied verification helpers
  are temporary CI artifacts, not released clients.

## Consequences

The install path now has an explicit prepare/commit barrier:

```text
helper owns parent -> ready
client persists pending -> commit
client exits normally -> helper verifies and starts Setup
```

There is no path from persistence failure to later Setup launch. If commit
signaling fails after persistence, startup may observe an aborted result or a
bounded pending record, both of which fail closed.

The helper command is intentionally incompatible with the earlier inactive
nine-pair schema. Any future command evolution after release must be versioned
or support the deployed compatibility window.

## Migration and Rollback

Client, helper, parser, packaging CI, and tests move together. Rolling back must
restore all five pieces together; mixing a ten-pair client with a nine-pair
helper fails command validation and cannot install.

## Verification

- parser tests require the exact commit event and reject malformed/missing
  options;
- handoff tests call the commit authorizer only after ready and authorize quit
  only after it succeeds;
- coordinator tests prove an existing pending record denies commit and quit;
- result tests accept normalized `handoff-aborted` evidence;
- native Windows CI creates both events, observes ready, explicitly commits,
  then continues the unsigned Setup rejection path;
- full Qt verification builds client, helper, and all related tests.
