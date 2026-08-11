# Client Performance Policy

This document records client-side performance boundaries. It defines mechanisms
and verification expectations; it does not make an unmeasured latency or
capacity claim.

## Web Conversation Timeline

The Web conversation repository retains at most 500 message records per
account-scoped conversation. Retention and rendering are separate limits:

- conversations with at most 80 messages render normally;
- longer conversations render only the viewport plus 700 CSS pixels of
  overscan on either side;
- top and bottom spacers preserve the full scroll range;
- text and attachment messages begin with conservative height estimates and a
  `ResizeObserver` replaces those estimates with measured wrapper heights;
- prepending a history page restores the previous viewport by the change in
  scroll height, rather than jumping to the newly loaded first item;
- new messages scroll automatically only while the reader is already near the
  bottom.

The pure window calculation is covered by source-independent unit tests for
short lists, variable heights, invalid dimensions, end-of-list behavior, and a
500-message retained conversation. Browser interaction and memory measurements
remain release evidence to add before making a user-visible performance claim.

## Remaining M2 Boundaries

- The Windows message view still needs an equivalent bounded rendering model.
- Browser media bytes need a dedicated bounded cache policy; IndexedDB message
  snapshots must not become an implicit unbounded media store.
- Delivery/read presentation must be defined against protocol semantics rather
  than inferred from local rendering.
