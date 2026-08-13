# ADR-0324: Default-Off Windows V2 Product Configuration

- Status: Accepted
- Date: 2026-08-13
- Owners: project maintainers
- Related milestone: M6

## Context

The canonical Windows binary now compiles the detached V2 WSS stack, but it
must not infer a gateway from the V1 TCP host or connect to a developer endpoint
merely because the code is present. The exact V2 route is a release-time product
decision and credentials or query parameters must never be embedded in it.

## Decision

- Keep Windows V2 disabled unless the canonical CMake build explicitly sets
  `CHATROOM_ENABLE_WINDOWS_V2_PREVIEW=ON` and one
  `CHATROOM_WINDOWS_V2_WSS_URL`.
- Require an exact canonical `wss://authority/v2/windows` URL with no user info,
  query, fragment, encoded path variant, or zero port. Configuration supplied
  while the feature is disabled and enabled-but-incomplete configuration fail
  CMake generation.
- Compile the public endpoint into a focused runtime configuration boundary.
  Invalid compiled values fail closed to disabled with a safe diagnostic.
- Do not derive this URL from V1, environment variables, command-line values,
  QSettings, or the Web endpoint. Do not enable it in ordinary release CI until
  a reviewed preview/cutover invocation supplies the value.

## Consequences

Ordinary Windows and qmake rollback builds retain exactly their V1 behavior.
The next UI composition slice can consume one typed endpoint without knowing
how build policy is represented. Preview activation requires a separate,
reviewable CMake invocation and can be rolled back by rebuilding with the
default-off configuration; no local user data migration is involved.

## Verification

- run the CMake script policy against disabled, complete, residual, insecure,
  credential-bearing, query-bearing, and wrong-route configurations;
- run the Qt runtime validator against canonical and hostile URL forms;
- require the default runtime build to remain disabled without an error;
- build the enabled configuration in native Windows CI before any release claim.
