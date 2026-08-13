# ADR-0325: One-Use Windows V2 Credential Handoff

- Status: Accepted
- Date: 2026-08-13
- Owners: project maintainers
- Related milestone: M6

## Context

The Windows product remains on V1 for chat while the device-management preview
uses V2. Starting a separate V2 session after a successful V1 login requires
the same user credential, but persisting or indefinitely retaining the password
to survive WSS connection attempts would create a new secret store.

## Decision

- Permit the successful login dialog to transfer the entered UTF-8 password
  once, in process memory, to a focused device-management application service.
- Start a 60-second maximum credential lifetime when the V2 connection attempt
  begins. Consume the credential on the first negotiated fresh-authentication
  opportunity; never resend it after it has been handed to the transport.
- Erase the pending bytes on handoff, authentication rejection, timeout, stop,
  or destruction. An authentication exception stops this V2 path fail-closed.
- After session establishment, keep only the transport's memory-only rotating
  resume credential. A rejected/expired resume requires a fresh interactive
  login; the device-management feature must not ask `ChatWindow` to cache the
  password.
- Treat the server device directory as live security state. Refresh it after
  authentication, abandon correlated UI work on disconnect, and never persist
  the directory in SQLite, QSettings, or the message cache.

## Consequences

V1 chat remains usable if the default-off V2 preview is unavailable. A gateway
outage lasting longer than 60 seconds disables the preview until the next login
instead of lengthening password exposure. The application layer is transport-
independent and can be tested without a network connection. Product composition
and the login-dialog transfer are the next reversible slice.

## Verification

- start at most once and consume the credential on exactly one authentication;
- erase and reject reuse after handoff, timeout, stop, rejection, and failure;
- refresh the live directory only after an authenticated device is established;
- clear authenticated/loading/request state on disconnect;
- prove no password or device directory is written to durable client storage.
