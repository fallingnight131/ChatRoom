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
