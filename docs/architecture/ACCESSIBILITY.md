# Accessibility Baseline

The supported Web and Windows clients must make core chat workflows usable
without a mouse and understandable with platform assistive technology. This is
an M2 foundation, not a claim of formal WCAG conformance.

## Product Semantics

- Conversation history is an ordered live log. New messages may be announced,
  while background cache hydration must not repeatedly announce the entire
  history.
- Every composer control has an accessible name. Upload progress exposes a
  numeric value and send failures expose an explicit retry action.
- User profiles, attachment preview/download, message retry, and message action
  menus must be keyboard reachable. `Shift+F10`/the context-menu key opens
  message actions on Web.
- Images and video thumbnails carry useful alternative text derived from
  server-authoritative metadata; decorative glyphs do not replace labels.
- Destructive actions retain confirmation and must not depend only on color.

## Visual and Motion Policy

- Keyboard focus uses the shared accent token and remains visible against light
  and dark themes.
- Layout and controls must remain usable at 200% browser zoom without requiring
  horizontal scrolling for the primary conversation workflow.
- `prefers-reduced-motion: reduce` disables nonessential animation and smooth
  scrolling. Product state must never be communicated by animation alone.

## Verification

Automated source gates protect semantic landmarks, names, progress state,
keyboard entry points, visible focus, and reduced-motion behavior. M4 release
gates must add browser keyboard walkthroughs and screen-reader smoke tests for
send, retry, attachment preview/download, and conversation switching. Native
Windows UI Automation checks remain a separate Windows release gate.
