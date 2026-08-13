# ADR-0327: Windows V2 Device-Management Product Composition

- Status: Accepted
- Date: 2026-08-13
- Owners: project maintainers
- Related milestone: M6

## Context

The Windows client has independently verified V2 protocol, WSS lifecycle,
one-use credential, installation identity, ViewModel, and accessible dialog
boundaries. Users still cannot reach them because the V1 login and `ChatWindow`
do not compose the feature. Enabling V2 chat globally is outside this slice.

## Decision

- Add one controller that exclusively owns and wires the V2 WSS transport,
  session/application service, and device ViewModel. `ChatWindow` owns the
  controller lifetime and presentation, not protocol state transitions.
- After successful V1 login, always clear the login field. Only a build with a
  valid default-off V2 configuration and valid installation UUID transfers the
  UTF-8 password bytes to the one-use V2 service; every other path erases them.
- In an enabled build, start the exact Windows V2 endpoint in parallel with V1.
  V2 failure disables only device management and never logs the user out of V1
  chat. Session establishment automatically refreshes the live device list.
- Add a hidden-by-default “登录设备” settings action. Show it only after the V2
  controller starts; opening it reuses one model/dialog and requests a fresh
  server directory. Logout stops V2 before disconnecting V1.
- Keep this composition in the canonical CMake Windows product only. The qmake
  rollback remains V1-only and ordinary builds leave the V2 product switch off.
  Web continues to use its independently gated V2 preview route.

## Consequences

The supported Windows client now has a complete, build-gated device-management
user path without changing its V1 chat protocol. A preview can be rolled back
by rebuilding with the V2 switch off; no database or installer payload rollback
is required. Until a reviewed endpoint is supplied and native Windows evidence
passes, ordinary released binaries expose no menu item and make no V2 request.

## Verification

- simulate hello, one-use authentication, session establishment, automatic
  directory refresh, projection, and stop through the composed controller;
- run protocol/session/WSS tests with fatal project warnings;
- compile the V1 qmake client with the product macro absent;
- enforce source policy for default-hidden UI, password clearing, configuration,
  identity, signal wiring, logout stop, and no QSettings security-state cache;
- compile the default-off and enabled canonical client with MSVC, then run the
  Windows payload/installer gates before claiming native release evidence.
