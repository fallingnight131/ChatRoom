# ADR-0377: HAProxy Frontend Certificate Rotation Gate

- Status: Accepted
- Date: 2026-08-14
- Owners: project maintainers
- Related milestone: M5

## Context

ADR-0376 proves HAProxy master-worker reload while retaining the same TLS
material. Public certificates expire and may require emergency replacement, so
the edge must install a new keypair without disconnecting every active Web and
Windows WSS session. Merely parsing a PEM or observing a successful reload does
not prove new connections receive the intended leaf certificate.

## Decision

- Generate two independent one-day frontend keypairs from the disposable test
  CA, both with the required `localhost` SAN, plus exact SHA-256 DER leaf
  fingerprints. Mount only their combined PEMs, the backend CA, and HAProxy
  configuration into the read-only edge container.
- Establish authenticated WSS sessions on both gateways through the initial
  frontend certificate. Read its leaf fingerprint through Java's real
  HTTP/1.1-over-TLS product stack and require an exact match.
- Atomically render the retained backend set with the second frontend PEM and
  trigger the reviewed `SIGUSR2` master-worker reload.
- Read the leaf certificate again on a new HTTPS connection and require the
  second exact fingerprint and inequality with the first.
- Preserve the existing sender WSS on the former worker long enough to commit
  and remotely deliver sequence 1. Require a new WSS connection using the
  rotated edge to repair history and deliver sequence 2 through the retained
  gateway without duplication.
- Keep the explicit Docker/local-service gate outside ordinary `--all`.

## Consequences

The edge now has executable proof that a frontend leaf/keypair can rotate
without a fleet-wide WSS disconnect and that new TLS connections see the new
certificate rather than a cached or former-worker certificate. The fingerprint
probe uses the same HTTPS/ALPN stack as product traffic.

This does not rotate the backend gateway certificates or their CA bundle. It
also does not prove public trust-chain availability, OCSP behavior, key-store
integration, overlapping certificate expiry, rollback to the previous PEM, or
multi-edge coordination. Production automation must protect private keys,
validate the full chain and hostname, stage rollback material, and monitor expiry.

## Verification

Run:

```bash
python3 tools/verify_m0.py --gateway-load-balancer-certificate-rotation
```

All CA keys, leaf keys, certificates, fingerprints, processes, and data are
disposable and removed on every exit path.

## Rollback

Remove the second disposable frontend and rotation scenario. Retain the
ADR-0376 same-certificate reload gate and restore the previous reviewed PEM in
deployment automation. No application protocol, schema, or Java runtime change
is involved.
