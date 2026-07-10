# ADR-0007: Validate V1 Fields Before Expensive or Durable Work

- Status: Accepted
- Date: 2026-07-11
- Owners: project maintainers
- Related milestone: M1

## Context

A bounded 16 MiB JSON frame can still contain abusive fields. V1 previously
accepted unbounded passwords into authentication, stored very large chat text,
trusted a file's declared size instead of decoded bytes, allowed path components
inside filenames, appended chunks beyond the upload's declared total, and
finalized incomplete uploads. History counts were also client-controlled.

These defects amplify CPU/memory/disk use and can bypass room file quotas without
requiring an oversized transport frame.

## Decision

- Add a focused `InputValidator` boundary shared by room and direct handlers.
- Limit passwords to 1,024 characters before Argon2id work. Retain V1's minimum
  of four characters for registration/password changes so existing accounts
  remain compatible at login.
- Allow at most five login, registration, or password-change commands per
  connection per minute. The sixth disconnects with a structured operational
  category. Process/account/IP abuse controls remain a later deployment layer.
- Limit display names to 64 characters and message content to 64 KiB of UTF-8.
  Accept only the existing client content types.
- Clamp room and direct history requests to at most 100 messages.
- Require a basename-only filename of at most 255 UTF-8 bytes and reject both
  slash styles before constructing a local path.
- For inline files, strictly decode Base64, require decoded bytes to equal
  `fileSize`, and enforce the 8 MiB inline threshold before quota or disk work.
- For upload chunks, strictly decode Base64, reject empty chunks, enforce the
  4 MiB chunk limit, and never exceed the declared remaining bytes.
- Finalize an upload only when received bytes exactly equal the declared total;
  otherwise delete the temporary file and release reserved quota.
- Invalid fire-and-forget chat messages are dropped with category/user-ID logs.
  Existing file/auth response types return `success: false` and an error.

## Consequences

The server no longer preserves malformed legacy behavior such as filename paths,
size mismatches, arbitrary content types, or incomplete upload finalization.
Normal Qt and Web paths already send basenames, exact browser/file sizes, known
content types, and sequential chunks, so successful wire behavior is unchanged.

The five-per-minute authentication limit is deliberately connection-scoped to
avoid incorrect client IP assumptions behind proxies. It limits accidental or
single-socket abuse but is not a substitute for gateway/account throttling and
security monitoring in the future Java deployment.

Rollback reopens quota bypass, disk amplification, path construction, and
authentication-work amplification and is not suitable for public deployment.

## Verification

`Tests/v1_input_validation_test.py` runs against a real server and temporary
database/filesystem. It verifies authentication-work disconnect, overlong
password rejection, non-persistence of oversized/unknown-type chat, bounded
history, filename/path and size mismatch rejection, chunk overflow rejection,
successful exact upload, and cleanup of incomplete upload state.

The same verification command also runs positive V1 compatibility,
authorization, frame/rate, and slow-consumer suites.
