# ADR-0160: CMake Windows Update Product Configuration

- Status: Accepted
- Date: 2026-08-12
- Owners: project maintainers
- Related milestone: M4

## Context

ADR-0159 makes CMake the canonical Windows package source. The qmake client had
an explicit compile-time seam for public update channel, manifest URL, and
Ed25519 verification keys, but the new CMake target always compiled the updater
off. Removing qmake without restoring this seam would either block protected
update releases or encourage ad-hoc macros that bypass review.

These values establish public trust routing but are not secrets. Private
manifest-signing and Authenticode keys must remain outside source, CMake cache,
compiler arguments, and artifacts.

## Decision

- Add `CHATROOM_ENABLE_WINDOWS_UPDATES`, default off, plus cache variables for
  channel, manifest URL, primary public key, and optional secondary public key.
- Validate the configuration in a reusable CMake module even when no Windows
  product target is built, so ignored residual values fail instead of silently
  disappearing.
- When disabled, reject every nonempty update value.
- When enabled, require `stable` or `beta`, a restricted HTTPS literal ending in
  `/manifest.json`, a lowercase bounded key ID, and exactly 64 lowercase hex
  characters for each public key.
- Require the primary pair and allow the secondary only as a complete pair.
- Require the supported Windows product target whenever updates are enabled.
- Emit the existing `CHAT_UPDATE_*` compile definitions only after validation;
  retain the runtime `WindowsUpdateProductConfiguration::validate` boundary.
- Build the default-off configuration as a reusable CMake library and execute
  both default-off and synthetic enabled fixtures through bounded CTest.

## Consequences

Protected release automation can enable the canonical CMake updater without
source edits. Ordinary CI and local builds remain update-disabled. Configuration
errors stop during CMake generation, and runtime validation remains defense in
depth against a build-system validation mistake.

The public values appear in CMake cache and compiler arguments by design. No
private signing material is accepted by this interface.

## Migration and Rollback

Release workflows may pass reviewed public configuration only after protected
signing and endpoint ownership exist. qmake retains its equivalent seam during
the fallback window. Rollback leaves the option off; the compiled client then
contains no trusted endpoint/key and exposes no update UX.

Before qmake removal, compare an enabled qmake/CMake synthetic build or document
why only canonical CMake configuration remains relevant.

## Verification

- run script-mode CMake positive/default and negative configuration fixtures;
- reject disabled residue, missing primary values, HTTP/query-bearing URLs,
  uppercase/invalid keys, and incomplete secondary pairs;
- execute default-off and enabled runtime configuration CTests;
- run the full 29-test CMake/health gate;
- keep private signing material outside every command and repository artifact.
