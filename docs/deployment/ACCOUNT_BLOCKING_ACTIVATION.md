# V2 Account Blocking Activation and Rollback

This checklist activates the capability-7 candidate without treating it as a
complete block-list product or weakening the transactional direct-contact
policy. It applies to the Java gateway and the default-off Web V2 preview.
Windows composition remains outside this slice.

## Preconditions

- Back up PostgreSQL with a verified restore path and validate migrations
  through V052 on the exact candidate revision.
- Run `python3 tools/verify_m0.py --postgres`; retain the real TLS/WSS evidence
  for durable block, exact retry, and generic direct-message denial.
- Run Web tests, default and candidate builds, and the Chromium/Firefox account-
  blocking fixture described in `docs/BUILDING.md`.
- Confirm metrics expose only fixed `account_block_changed` and
  `account_block_noop` outcomes and no account, target, operation, or message
  labels.
- Confirm support understands that the Web candidate has no persisted block
  list or initial-state read. A fresh page reports unknown state until a desired-
  state command succeeds.

## Gateway-first activation

1. Deploy the schema-compatible Java candidate with
   `CHATROOM_GATEWAY_ACCOUNT_BLOCKING_ENABLED=false`.
2. Prove V1 and ordinary V2 handshakes, direct/group messaging, contact
   requests, readiness, and metrics remain unchanged.
3. Set `CHATROOM_GATEWAY_ACCOUNT_BLOCKING_ENABLED=true`, restart a bounded
   canary, and keep all client capability-7 flags off.
4. Verify ordinary clients still omit capability 7 and the canary installs no
   usable mutation path for them.
5. With a controlled protocol client, verify authenticated actor binding,
   generic target denial, exact operation retry, block/unblock, bilateral new
   direct-message and contact-request denial, unchanged history, and unaffected
   shared-group delivery.

## Web candidate activation

1. Build immutable assets with exact `VITE_CHAT_V2_ACCOUNT_BLOCKING=true` and
   the reviewed V2 preview endpoint; retain a same-revision build with the flag
   absent for rollback.
2. Release to a bounded non-production or internal cohort only after the gateway
   canary is healthy.
3. Verify the dialog appears only for DIRECT conversations, resolves the unique
   authorized non-self participant, contains focus, requires confirmation, and
   uses generic failure copy.
4. Interrupt one in-flight operation, resume the same page-memory session, and
   explicitly retry the same operation UUID. Verify one durable desired result.
5. Reload the page and verify it reports unknown state instead of inventing a
   persisted projection; apply either desired state and verify convergence.

## Rollback

Rollback clients first: move the Web deployment pointer to immutable assets
without `VITE_CHAT_V2_ACCOUNT_BLOCKING=true`. Existing negotiated connections
may retain capability 7 until the old page/socket closes, so drain the candidate
cohort before declaring the client path disabled.

Then remove `CHATROOM_GATEWAY_ACCOUNT_BLOCKING_ENABLED` or set it to exact
`false` and restart/drain gateways. Keep V052 and all transactional direct-
contact enforcement in place. Existing block rows remain authoritative and
must continue denying new direct contact. Removing that enforcement requires a
separate approved data policy or a verified empty graph; never delete rows or
rewrite Flyway history as an operational rollback.

This procedure is candidate evidence, not public Web support or a capacity
claim. Production activation still requires the normal Web endpoint, CSP,
health-window, staged rollout, and immutable rollback gates.
