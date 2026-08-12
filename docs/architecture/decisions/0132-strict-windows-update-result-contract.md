# ADR-0132: Strict Windows Update Result Contract

- Status: Accepted
- Date: 2026-08-12
- Owners: project maintainers
- Related milestone: M4

## Context

The external launcher writes durable JSON after the client exits. Treating any
parseable file as success would allow a stale UUID, unknown future schema,
contradictory exit code, or implausible timestamp to become product state.

## Decision

- Add a client-side schema-1 parser that accepts exactly the six fields emitted
  by ADR-0129 and requires the expected canonical request UUID.
- Normalize the closed outcome set. `installed` requires exit code zero,
  `installer-failed` requires a nonzero unsigned 32-bit exit code, and every
  pre-install/indeterminate outcome requires zero.
- Require an exact UTC timestamp no earlier than the pending request (allowing
  five minutes of clock skew) and no later than five minutes beyond the current
  UTC observation. Limit result documents to 16 KiB and non-secret error text to
  1024 control-free characters.
- Reject unknown fields and outcomes. Do not silently interpret a future schema
  with the schema-1 parser.
- Keep the parser inactive until a pending-request repository can bind and
  consume one exact result at startup.

## Consequences

The future UI can distinguish installer success, installer failure, trust
failure, and indeterminate states without inferring from process launch. Result
contract evolution must be additive and explicitly dispatched by schema.

## Migration and Rollback

No product path or durable data is activated. Rollback removes the parser and
test. Changing accepted outcome semantics requires a new result schema or an ADR
that proves compatibility with already copied schema-1 helpers.

## Verification

Portable tests accept installed and nonzero installer failure records while
rejecting UUID mismatch, contradictory exit codes, future time, unknown outcome,
unknown field, and control characters. Full Qt verification builds the parser
into the client. Native result-file consumption remains a later M4 gate.
