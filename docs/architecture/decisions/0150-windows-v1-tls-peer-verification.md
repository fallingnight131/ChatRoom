# ADR-0150: Windows V1 TLS Peer Verification

- Status: Accepted
- Date: 2026-08-12
- Owners: project maintainers
- Related milestones: M1, M4

## Context

`NetworkManager::connectToServer(..., useSsl=true)` created a `QSslSocket` with
`VerifyNone`. Any certificate could therefore be accepted. The same code also
treated the underlying TCP `connected` signal as application readiness, before
the TLS handshake or certificate validation completed. A login triggered from
that signal could send credentials before the connection was authenticated.
The normal local V1 connection currently uses plaintext TCP, but an optional TLS
path must not silently weaken trust when a deployment enables it.

## Decision

- Require `QSslSocket::VerifyPeer` and set the expected peer name to the exact
  configured host. Use Qt/system trust roots; do not add an insecure-skip option
  or trust-on-first-use state.
- For TLS sockets, publish the existing application `connected` signal only from
  `QSslSocket::encrypted`, never from the underlying TCP connection event.
- Keep ordinary plaintext TCP behavior unchanged when `useSsl=false`.
- Emit one fixed, non-secret `TLS certificate validation failed` application
  error on `sslErrors`; never call `ignoreSslErrors`.
- Verify policy with runtime-generated one-day localhost material. First connect
  without trusting it and require both client/server to observe rejection. Then
  explicitly add that certificate only to the isolated test process's default
  CA set and require a hostname-valid encrypted connection.
- Generate and delete the private key in a temporary directory. Commit no test,
  product, or signing private key.

## Consequences

The optional Windows V1 TLS client no longer accepts arbitrary certificates or
reports readiness before encryption. Deployments using private CAs must install
their root through the operating-system trust mechanism; writable application
settings cannot bypass validation. Existing local plaintext development remains
unchanged. This does not add TLS to the current Qt V1 server, select a public
endpoint, or prove a production proxy/certificate deployment.

## Migration and Rollback

Any environment that depended on self-signed/untrusted certificates must deploy
a trusted certificate or managed CA root before enabling TLS. Rolling back this
change would reintroduce credential interception risk and is not an acceptable
release fallback; use plaintext only on the documented loopback development path
until trusted TLS is available.

## Verification

- untrusted hostname-valid self-signed TLS is rejected and never emits the
  application connected signal;
- the same certificate, explicitly trusted only inside the test process, reaches
  `encrypted` and then emits connected;
- the TLS server independently observes rejected and accepted handshakes;
- ordinary plaintext three-connection reconnect/session-clear behavior remains
  green;
- temporary key material is deleted after every pass or failure.
