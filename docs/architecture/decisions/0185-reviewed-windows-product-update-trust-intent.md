# ADR-0185: Reviewed Windows Product Update Trust Intent

- Status: Accepted
- Date: 2026-08-12
- Owners: Windows release engineering and security
- Related milestone: M4
- Extends: ADR-0136, ADR-0160, ADR-0175

## Context

The client update controller is reachable only in a build containing a valid
manifest URL and Ed25519 public trust ring. Current ordinary builds deliberately
contain neither. Passing arbitrary CMake strings directly into a release build
would make endpoint/key review unauditable and could bind a signing candidate to
the wrong public key.

## Decision

- Add a short-lived, write-once product-trust build intent for the fixed
  `windows-update-product-trust` environment.
- Bind exact canonical version, source revision, stable/beta channel, and one
  credential-free HTTPS URL at `/windows/<channel>/manifest.json`.
- Inspect each reviewed PEM with OpenSSL and accept only canonical Ed25519 SPKI.
  Extract the exact raw 32-byte lowercase public key expected by the CMake/Qt
  verifier and bind the PEM file SHA-256 plus reviewed key ID.
- Require one primary key and permit one complete, distinct secondary key for
  overlap rotation. Reject duplicate IDs or raw keys and incomplete pairs.
- Limit intent lifetime to 300–7200 seconds and label it
  `reviewed-product-update-trust-not-built`.
- Include no private key, signing URI, PIN, password, certificate, provider
  credential, build command, or publication authority.

## Consequences

A future protected product build can consume one reconstructable public input
record instead of unreviewed cache strings. Public trust becomes traceable to
the exact source release and PEM files while signing keys remain in their
separate HSM domain. This intent alone does not build, sign, or enable any
client.

## Migration and Rollback

Ordinary builds remain update-disabled. Expired or incorrect intents are
discarded and recreated; they are never edited. Key rotation first ships a
dual-key intent/build, waits for adoption, and only then removes the old key in
a later release intent.

## Verification

- `python3 Tests/windows_update_product_trust_intent_test.py`
- reject non-Ed25519 PEM, URL/channel mismatch, duplicate/incomplete keys,
  expiry/future/mutation/duplicates, invalid lifetime, and overwrite;
- protected native product-build orchestration remains the next gate.
