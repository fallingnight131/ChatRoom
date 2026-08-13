# ADR-0378: HAProxy Backend CA Expand-Migrate-Contract Gate

- Status: Accepted
- Date: 2026-08-14
- Owners: project maintainers
- Related milestone: M5

## Context

ADR-0377 rotates the public HAProxy leaf certificate but deliberately leaves the
private HAProxy-to-gateway trust relationship unchanged. A backend CA can expire,
be replaced by policy, or require emergency revocation. Replacing the CA bundle
and every gateway certificate in one instant creates either an outage or a period
with certificate verification disabled.

## Decision

- Generate independent old and new disposable backend CAs and one
  `gateway.internal` server certificate from each. Keep hostname verification
  required in every phase.
- Start with only the old CA trusted. Require the old gateway to accept WSS and
  require HAProxy health checks to reject the new-CA gateway.
- Expand HAProxy trust to an old-plus-new CA bundle through master-worker reload.
  Require the current worker to establish authenticated sessions through both
  gateway certificate generations.
- Contract HAProxy trust to only the new CA through a second master-worker
  reload. Keep both test endpoints configured long enough to prove the old
  certificate is rejected and new admission reaches only the new gateway.
- Preserve established former-worker WSS tunnels across both reloads. Require an
  old tunnel to commit and remotely deliver sequence 1, then require a new-CA
  session to repair history and deliver sequence 2 without duplication.
- Keep this Docker and local-service gate explicit and outside ordinary `--all`.

## Consequences

Backend trust rotation now has executable evidence for the required ordering:
expand verifier trust, migrate gateway identities, prove the new path, and only
then contract old trust. At no point does the reviewed HAProxy configuration use
`verify none` or omit hostname verification.

The gate uses two same-host gateways and one HAProxy instance. It does not prove
production secret-store delivery, intermediate-chain rotation, CRL or OCSP
behavior, private-key compromise response, correlated host loss, multi-edge
coordination, or rollback after the old CA has been destroyed.

## Verification

Run:

```bash
python3 tools/verify_m0.py --gateway-backend-ca-rotation
```

The generated CAs, private keys, certificates, processes, and data are disposable
and removed on every exit path.

## Rollback

During the expand or migrate phase, restore the old-plus-new bundle and route new
admission to a known-good gateway generation. Do not remove the new CA first if
any active backend presents a new certificate. After contraction, restoring the
old CA requires restoring the old-plus-new bundle before reintroducing an old
certificate gateway. No application protocol or persistent schema changes.
