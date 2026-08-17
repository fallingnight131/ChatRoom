# ADR-0412: Windows Locale Preference Boundary

- Status: Accepted
- Date: 2026-08-17
- Owners: project maintainers
- Related milestone: M6

## Context

The Web product has an exact `zh-CN`/`en-US` catalog and bounded preference,
while the Windows Qt client still embeds Chinese presentation strings directly
in Widgets. Adding a selector directly to one dialog would create a mixed-
language product and couple presentation to `QSettings`.

The first Windows localization slice needs a stable terminology boundary and a
non-secret restart-stable preference without changing protocol, account data,
message storage, server behavior, or the supported Web/Windows product scope.

## Decision

- Define an exact two-value Windows locale type: `zh-CN` and `en-US`. Invalid,
  missing, or differently-cased values fail closed to `zh-CN`.
- Keep presentation copy in a typed Qt Core catalog. User/server identities and
  server-provided diagnostics are data and are never translated by lookup.
- Store only the bounded locale code at `QSettings` key `ui/locale`. It is a
  product preference, not security, identity, credential, or sync state.
- Keep persistence behind `WindowsLocalePreferenceRepository`; Widgets consume
  catalog/view-model state rather than reading `QSettings` directly.
- Do not expose a language selector until one complete Windows surface can
  switch without mixed catalog-owned copy. Migrate vertical slices and retain
  the existing Chinese default throughout the compatibility window.
- The first exposed selector belongs to the complete V2 conversation surface.
  `ChatWindow` composes settings, repository, and locale ViewModel; one accepted
  change recomposes every conversation-owned ViewModel, Widget, and child
  dialog. Persistence failure retains the old locale and reports fixed copy.
- The selector has an explicit label buddy and accessible description. Its
  first tab transition is deterministic, and visible persistence failure emits
  an accessibility Alert after restoring the accepted locale.
- Grow the catalog for the complete legacy profile surface before connecting
  that Widget. Avatar, identity, password, low-bandwidth, validation, and local
  feedback copy must migrate together; server-provided failure text remains
  opaque data.
- `ChatWindow` owns one locale repository/ViewModel for both the profile and V2
  conversation surfaces. Profile labels establish buddy relationships for
  identity and password fields, and an open profile recomposes on the same
  `changed` signal as the conversation surface.
- Once the complete profile is localized, expose the same exact selector there
  as the primary Windows language entry point. The V2 conversation selector may
  remain as a contextual duplicate; both consume the same ViewModel and stored
  value. Failed profile writes restore the accepted selection and emit visible
  status plus an accessibility Alert.
- Grow the exact catalog across the complete login/registration surface before
  exposing its pre-authentication selector. Connection and validation states are
  catalog copy; server-supplied rejection and socket reasons remain opaque data.
- Once login/registration is complete, move locale composition to `main.cpp` so
  one application-lifetime repository/ViewModel is injected into both the
  pre-authentication `LoginDialog` and post-authentication `ChatWindow`.
  Individual Widgets retain fallback ownership only for isolated construction.
- Represent local login/registration progress and validation with stable status
  kinds, then project those kinds through the active catalog. Preserve socket and
  server reason strings as opaque detail across recomposition. A failed selector
  write restores the accepted value and emits visible status plus an
  accessibility Alert.
- Treat the emoji picker as a complete leaf surface: its visible title and each
  glyph button's insertion action come from the live catalog, while the fixed
  96-glyph identity/order and emitted payload remain locale-independent.
- Inject the same ViewModel into the legacy multi-target forwarding dialog.
  Conversation/account names and IDs stay opaque, while tabs, search, presence,
  unread labels, actions, and accessible list names are catalog copy. Block list
  signals while recomposing so presentation changes cannot mutate checked IDs.
- Model user-information roles as `None`, `Member`, or `Administrator` rather
  than passing localized strings from ChatWindow. Catalog only the role
  presentation, labels, avatar actions, and accessible names; username and
  display name remain opaque identity data. An already-open avatar preview
  follows the same live ViewModel.
- Pass the shared ViewModel through both profile and room-settings avatar flows
  into the reusable cropper. Its title, custom-painted instruction, preview,
  actions, and accessible description are presentation only; crop geometry,
  source pixels, and the 256x256 result remain locale-independent.
- Migrate the whole room-settings surface together: limit summaries/forms,
  administrator avatar/name/password actions, leave/delete confirmations,
  validation, file dialogs, and accessible label buddies use catalog copy.
  Room IDs, names, numeric limits, and unsaved form values remain unchanged.
  Password entry is masked, password/developer-key fields clear after dispatch,
  and the query action is named as password-status inspection because the
  server never returns the non-recoverable password.
- Inject the same ViewModel into the administrator room-file manager and
  localize its entry, usage summary, accessible table, headers, type/status
  presentation, actions and destructive confirmation together. Locale changes
  may update row presentation but must retain Unicode filenames, timestamps,
  stable room/file IDs, cleared eligibility and checked deletion targets.
  Listing and deletion authorization remains server-side.
- Replace the static protected-room password input with one complete leaf that
  consumes the shared ViewModel. Mask the field, preserve exact non-empty secret
  text and the server-provided room ID, clear component plaintext before emit,
  and retain server-side join authorization. Empty input is a local validation
  state and must recompose with the active locale. A newer server challenge
  closes and replaces stale prompt state before capturing its room ID.
- Catalog local room-password set/remove/query success and status feedback, but
  preserve server-provided failure text as opaque detail. Status presentation
  must continue to say only whether a password exists; it must never imply that
  the non-recoverable secret can be displayed.
- Before localizing Windows device management, replace ViewModel-owned Chinese
  failure sentences with stable `None`, `LoadFailed`, `RevokeFailed`, and
  `InvalidDirectory` states. Presentation belongs to the dialog catalog; request
  correlation, device identity and current-device protection remain application
  state and must not be reconstructed from translated text.
- Inject the shared ViewModel into the complete device-management dialog.
  Security guidance, accessibility names, platform/current/activity
  presentation, typed failures, actions and revocation confirmation are catalog
  copy. Recomposition must preserve selected/focused stable device ID, current-device
  protection and the one-at-a-time correlated revoke state. Device identifiers
  are opaque data and must use plain-text rendering.
- Split blocked-account directory failure state into stable local directory and
  mutation enums plus a separate safe opaque server-detail field. Catalog the
  complete Widget, including guidance, accessibility, row/time presentation,
  paging, actions and default-cancel confirmation. Recomposition retains the
  selected stable account and correlated idempotent unblock operation; retry
  and authorization remain application/server responsibilities.
- Preserve account-block directory protocol retryability as a typed failure.
  The controller must not synthesize localized text into the safe-server-detail
  channel; absent real safe detail, the Widget projects typed state by locale.
- Keep the Windows V2 notification policy locale-free: it emits only generic or
  mention semantics and stable conversation identity. The presenter consumes
  the shared locale at show time and maps to privacy-safe catalog copy; locale
  changes must not reset deduplication or alter activation identity.
- Inject the same application-lifetime ViewModel into the Windows tray adapter.
  Tooltip and show/quit action copy follow the active locale by updating the
  existing QAction instances; locale changes must not rebuild platform menus,
  reconnect activation handlers, or replace pending notification identity.
- Treat the main-window title, menu hierarchy/actions, and About dialog as one
  chrome surface. Update existing menu/action objects on locale changes so
  shortcuts, visibility gates and connections survive. Display names remain
  opaque identity data inserted only into the active title template.
- Separate server connection lifecycle from the legacy window's transient
  activity feedback. A locale-independent ViewModel owns disconnected,
  connected and normalized reconnect-attempt state; the Widget projects it in a
  dedicated permanent status label and never overwrites upload/download status.

## Consequences

The catalog and preference can be tested on the macOS development host through
Qt Core, while product UI evidence still requires native Windows. Catalog
growth is explicit and duplicates some Web copy by design; shared behavior and
terminology matter, but browser and Windows presentation code remain separate.

Rollback removes consumers before removing the additive catalog/repository.
An existing `ui/locale` string is harmless and ignored by older builds. No
database migration or protocol compatibility window is required.

## Verification

- Qt tests prove missing and non-exact values use the Chinese default;
- exact English persists across a fresh `QSettings` instance and can return to
  exact Chinese;
- catalog tests bind stable language, composer action, and byte-budget copy;
- conversation composition proves a Chinese-to-English persisted switch across
  shell, timeline actions, participant accessibility, and a fresh settings
  instance, while a visible write failure preserves the active locale, restores
  the selector, and retains a usable focus path;
- later UI slices must run native Windows keyboard/accessibility checks and may
  not claim product localization from this foundation alone.
- profile composition proves a live Chinese-to-English switch across every
  profile-owned visible action and a fresh profile instance starts in the
  persisted locale; server-provided password errors remain verbatim;
- login composition proves a Chinese-to-English switch across tabs, fields,
  actions, placeholders, and live validation; a fresh dialog restores the exact
  value, socket detail survives recomposition verbatim, and an unwritable target
  restores the accepted selector with announced failure status.
- emoji-picker composition proves all 96 controls retain identity/order and
  selection behavior while their title and accessible insertion actions switch.
- legacy forwarding composition proves selected stable identities survive live
  list reconstruction while every dialog-owned string switches together.
- user-information composition proves typed role presentation and nested avatar
  preview recomposition without changing Unicode display or account identity.
- avatar-cropper composition proves both entry points carry the shared boundary
  and a live language switch leaves the resulting image pixel-equivalent.
- room-settings composition proves administrator and limit groups switch
  together without changing unsaved Unicode/secret input; successful commands
  clear secret fields and keep unchanged room identity on the transport path.
- room-file composition proves available and cleared row presentation switches
  together while a checked stable file ID, Unicode filename and refresh room ID
  remain unchanged.
- protected-room composition proves empty local rejection and live validation
  recomposition, then requires clear-before-emit with the unchanged room ID and
  exact non-empty password.
- source composition binds room-password result/status feedback to the catalog
  while passing the server `error` detail unchanged.
- device-management ViewModel tests bind invalid directories and protocol/list
  failures to exact typed states while preserving retry and stale-response rules.
- device-management Widget composition proves a selected remote identity and
  correlated revocation survive live language changes, current-device revoke is
  absent, typed failure recomposes without replay, and identifier text cannot be
  interpreted as markup.
- blocked-directory ViewModel/Widget tests prove local failures recompose while
  safe server detail stays verbatim, selection survives live switching, one
  operation ID owns unblock/retry, and disconnect preserves rows while disabling
  network mutation.
- controller source composition rejects manufactured Chinese directory detail,
  and Widget tests distinguish localized retryable state from opaque detail.
- notification policy/presenter tests prove no localized copy or message body
  enters policy state, while both Chinese and English projection retain bounded
  deduplication and stable conversation activation.
- source composition binds the tray tooltip and existing show/quit actions to
  the shared locale without embedding Chinese presentation copy; the catalog
  test locks both exact languages and native Windows interaction remains open.
- source composition also rejects embedded menu copy, requires every gated
  action and About presentation to use the catalog, and catalog tests lock the
  exact title/menu terms; native Windows mnemonic interaction remains open.
- connection-state tests bind fail-closed initialization, normalized attempts,
  deduplicated change signals and metadata clearing. Source composition requires
  a separate activity/connection label and rejects direct connection copy.
