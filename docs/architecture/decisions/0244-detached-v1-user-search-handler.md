# ADR-0244: Compose Detached V1 User Search

- Status: Accepted
- Date: 2026-08-13
- Related milestone: M3

## Decision

Compose strict bounded `USER_SEARCH_REQ/RSP` handling in the detached Java V1
module. Parse exactly one string `data.keyword`, bind self exclusion to the
authenticated channel account, and dispatch through the existing bounded
directory executor with one search in flight per connection. Suppress results
after disconnect or identity replacement.

Valid empty/oversized/control-bearing keywords reach the application policy and
return the compatible `success=false` empty-keyword error without closing.
Malformed JSON/field types, executor saturation, encoding failure, and dependency
failure close with a fixed reason instead of returning a potentially authoritative
empty success list. Successful responses contain only `userId`, `username`,
`displayName`, and `online`. Telemetry has fixed found/input-rejected/failure/
saturation labels, bounded result count, and elapsed time without identifiers.
The product listener remains unchanged.

## Verification

Codec and embedded-channel tests cover exact fields, duplicate/unknown/type
rejection, business input denial, server-bound account identity, dependency
failure, and saturation. Disposable PostgreSQL integration searches the same
mapped peer before and after its V1 login, proving stable numeric/profile fields,
offline-to-online presence change, native-account exclusion, and no UUID leak.
