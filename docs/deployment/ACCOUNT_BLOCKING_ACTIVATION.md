# V2 Account Blocking Activation and Rollback

This checklist activates the capability-7 candidate without treating it as a
complete block-list product or weakening the transactional direct-contact
policy. It applies to the Java gateway and the independent default-off Web and
Windows V2 candidates. Windows has a separately compiled
protocol/transport/Widgets candidate. It
must remain internal until the native Windows Release interaction and real
endpoint gates below are retained for the exact revision.

## Preconditions

- Back up PostgreSQL with a verified restore path and validate migrations
  through V052 on the exact candidate revision.
- Run `python3 tools/verify_m0.py --postgres`; retain the real TLS/WSS evidence
  for durable block, exact retry, and generic direct-message denial.
- Run Web tests, default and candidate builds, and the Chromium/Firefox account-
  blocking fixture described in `docs/BUILDING.md`.
- Confirm metrics expose only fixed `account_block_changed`,
  `account_block_noop`, `account_block_directory_page`, and
  `account_block_directory_row` outcomes and no account, target, cursor,
  operation, or message labels.
- Confirm support understands that the Web candidate keeps the server block
  directory in page memory only. A fresh authenticated page reads it again;
  incomplete or failed pagination leaves an absent target unknown rather than
  claiming it is unblocked.
- Confirm the shipped Windows diagnostic reports schema 5 with
  `accountBlockingEnabled=false`. A candidate may set it true only for the
  bounded Windows validation cohort described below.

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
   shared-group delivery. After blocking, request type 134 and verify type 135
   contains only the actor's outgoing edge, current target display name, positive
   block time, and a consistent bounded continuation cursor; never probe or
   expose inbound blockers.

## Web candidate activation

1. Build immutable assets with exact `VITE_CHAT_V2_ACCOUNT_BLOCKING=true` and
   the reviewed V2 preview endpoint; retain a same-revision build with the flag
   absent for rollback.
2. Release to a bounded non-production or internal cohort only after the gateway
   canary is healthy.
3. Verify the global privacy dialog is available without an active conversation,
   contains focus, requires confirmation, uses generic failure copy, and renders
   only outgoing server-returned block rows. In a DIRECT conversation, verify a
   new block resolves only the unique authorized non-self participant; other
   conversation kinds expose no new-block target.
4. Interrupt one in-flight operation, resume the same page-memory session, and
   explicitly retry the same operation UUID. Verify one durable desired result.
5. Reload the page and verify it requests a fresh type-134 page, derives a known
   state only after complete pagination, supports bounded load-more, and can
   confirm unblock for a server-returned row. Apply either desired state and
   verify the post-mutation directory refresh converges.

## Windows candidate activation

1. Build an unsigned/internal candidate with the reviewed preview endpoint and
   `CHATROOM_ENABLE_WINDOWS_V2_ACCOUNT_BLOCKING=ON`; retain a same-revision build
   without that option for rollback.
2. On native Windows, require the account-block dialog CI test plus clean-host
   launch. Verify ordinary and group conversations do not expose an enabled
   action, while a DIRECT conversation waits for its authoritative participant.
3. Against the gateway canary, confirm block, unblock, generic denial, one
   interrupted same-operation retry, keyboard-only navigation, screen-reader
   names/status, and focus return to the conversation window.
4. Retain diagnostic schema 5, gateway telemetry, database desired state, and
   client-first rollback evidence for the exact binary. Until these checks pass,
   do not distribute the candidate outside the bounded validation cohort.

## Rollback

Rollback clients first: move the Web deployment pointer to immutable assets
without `VITE_CHAT_V2_ACCOUNT_BLOCKING=true`. Existing negotiated connections
may retain capability 7 until the old page/socket closes, so drain the candidate
cohort before declaring the client path disabled.

If a Windows candidate was used in a test environment, replace it with
the same-revision build made without
`CHATROOM_ENABLE_WINDOWS_V2_ACCOUNT_BLOCKING=ON`, verify diagnostic schema 5
reports false, and close its existing V2 connections.

Then remove `CHATROOM_GATEWAY_ACCOUNT_BLOCKING_ENABLED` or set it to exact
`false` and restart/drain gateways. Keep V052 and all transactional direct-
contact enforcement in place. Existing block rows remain authoritative and
must continue denying new direct contact. Removing that enforcement requires a
separate approved data policy or a verified empty graph; never delete rows or
rewrite Flyway history as an operational rollback.

This procedure is candidate evidence, not public Web/Windows support or a
capacity claim. Production activation still requires the normal Web endpoint,
CSP, health-window, staged rollout, immutable rollback, and signed clean-host
Windows release gates.
