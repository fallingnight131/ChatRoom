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
