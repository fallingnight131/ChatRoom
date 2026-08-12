# ADR-0229: Preserve V1 Self Chat as a Direct Conversation

- Status: Accepted
- Date: 2026-08-13
- Related milestone: M3

## Decision

Permit equal account IDs in `direct_conversation` and map a V1 self friendship
to a one-member DIRECT conversation. Pair uniqueness still allows only one self
chat per account. V016 relaxes strict ordering to non-strict ordering; normal
two-person conversations are unchanged.

This is a compatibility representation, not permission to create arbitrary
malformed pairs. The verified V1 import owns equal-pair creation; membership
authorization and message sequencing remain unchanged.

## Consequences and rollback

Existing PostgreSQL rows satisfy the relaxed constraint. Before traffic cutover,
rollback uses the previous binary and leaves it in place. Re-tightening requires
proving no imported self chat exists; deleting self-chat history is forbidden.

## Verification

Run the disposable PostgreSQL migration/import gate and planner tests.
