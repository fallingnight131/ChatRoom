# ADR-0413: Windows Low-Bandwidth Preference Boundary

- Status: Accepted
- Date: 2026-08-17
- Owners: project maintainers
- Related milestone: M6

## Context

The Web product already has a bounded low-bandwidth policy that suppresses
automatic avatar requests without weakening message delivery or synchronization.
The Windows product still requests uncached avatars automatically across its
legacy conversation surfaces. Windows V2 message history is a correctness and
offline-repair path, so reducing or stopping that traffic would be mislabeled as
a safe data-saving feature.

## Decision

- Store one non-secret exact `true`/`false` value at QSettings key
  `ui/lowBandwidth`. Missing, malformed, or differently-cased values default to
  disabled.
- Keep storage behind `WindowsBandwidthPreferenceRepository`; a failed write
  restores the previous stored value.
- Keep automatic-avatar eligibility in `WindowsBandwidthPolicy`. Low-bandwidth
  mode suppresses only uncached automatic avatar requests. Cached avatars remain
  visible, and an explicit user action may still request an avatar.
- Do not alter message delivery, reconnect synchronization, history repair,
  text search, attachment metadata, or user-initiated attachment transfer.
- Expose the control from the Windows profile dialog through a preference
  ViewModel. Failed persistence restores the accepted checkbox state and emits
  visible status plus an accessibility Alert.
- Route avatar dispatch through `WindowsAvatarRequestCoordinator`; ordinary
  list/login requests consult the policy, while explicit profile access and an
  upload-completion refresh are classified as user initiated.

## Consequences

The first policy is intentionally narrow and measurable. It does not claim a
general network bandwidth reduction, and it does not treat smaller protocol
pages as savings when the client would still fetch every page.

The Core policy and repository can be tested on the macOS development host.
Native Windows UI and network-dispatch evidence remains required before this is
a supported product behavior.

Rollback removes product consumers before deleting the additive preference
code. Older clients ignore the harmless QSettings key.

## Verification

- missing and malformed values default to disabled;
- an exact enabled choice survives a fresh QSettings instance;
- an unwritable settings target retains the disabled default;
- the policy permits only a non-empty, uncached, automatic request while the
  preference is disabled;
- product composition distinguishes automatic avatar loading from explicit
  profile access and keeps message synchronization independent of the setting.
- the offscreen profile Widget verifies accessible description, activation,
  restart persistence, and UI rollback on an unwritable settings target.
