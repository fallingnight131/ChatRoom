# ADR-0003: Keep V1 Web Passwords in Page Memory Only

- Status: Accepted
- Date: 2026-07-11
- Owners: project maintainers
- Related milestone: M1

## Context

The V1 web client stored `{ username, password }` in `sessionStorage` so a page
refresh could submit the password again. `sessionStorage` is readable by any
script executing in the origin, remains available for the tab lifetime, and is
not a revocable server session. V1 does not yet expose access/refresh tokens, so
there is no non-password credential that can safely preserve login across a
refresh.

The same credentials are needed for protocol-compatible reauthentication after
an unexpected WebSocket disconnect. Removing all temporary access would force a
user to log in after every short network interruption.

## Decision

- Keep the username/password pair in a module-private, in-memory holder after a
  successful login.
- Never write the password, credentials object, or authenticated identity marker
  to Web Storage.
- Purge the old `credentials` and `user` session-storage keys when the updated
  client loads.
- Use the memory-only credentials only for reauthentication after a disconnect
  during the current page lifetime.
- Require explicit login after refresh, tab close, logout, forced offline, or
  failed reauthentication.
- Keep non-secret preferences such as theme and server address in local storage.
- Update the memory-only credential when the user changes username or password.

## Alternatives Considered

- Continue using `sessionStorage`: rejected because it preserves the plaintext
  password at rest and treats browser storage as an authentication mechanism.
- Encrypt the password in browser storage: rejected because the browser would
  also need the decryption key, providing no meaningful protection against
  same-origin script execution.
- Add refresh tokens in this slice: deferred because that requires a server
  session model, revocation, expiry, protocol changes, and a broader migration.
- Never retain the password, even in memory: safer against some in-page attacks
  but unacceptably disruptive for transient network disconnects in V1.

## Consequences

Refreshing the web page now signs the user out. Short disconnects within the
same page can reauthenticate without prompting. Passwords remain present in
JavaScript memory while logged in and can still be stolen by successful XSS;
Content Security Policy and server-issued sessions remain required follow-up.

This change does not make plaintext `ws://` safe. Public authentication must use
TLS/WSS, and the server-side password hash/session model remains separate M1
work.

## Migration and Rollback

Loading the new user store removes legacy session keys. Rolling back the client
restores old behavior but cannot recover the removed stored password; the user
must log in again. No server, database, or protocol migration is required.

## Verification

- Node tests cover memory set/get/copy/clear and legacy-storage removal.
- A source regression test rejects new session-storage writes and credential-like
  local-storage writes in authentication sources.
- The production Web bundle must build.
- Manual/browser integration should cover login, refresh-to-login, transient
  disconnect reauthentication, logout, forced offline, username change, and
  password change.
