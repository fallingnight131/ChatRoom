# ADR-0187: Final Windows Product Update-Trust Evidence

- Status: Accepted
- Date: 2026-08-12
- Owners: Windows client, release engineering, and security
- Related milestone: M4
- Extends: ADR-0185, ADR-0186

## Context

The final binary diagnostic exposes compiled trust, but retaining its stdout
without binding the exact PE and reviewed intent permits substitution. Protected
packaging needs one independently reconstructable record that connects all
three before Authenticode changes the executable bytes.

## Decision

- Add write-once schema-1 product update-trust evidence for the unsigned final
  `ChatClient.exe`.
- Reverify the live ADR-0185 intent and strictly parse the ADR-0186 diagnostic.
- Require enabled state, exact channel/manifest/signature URL, empty error, and
  sorted one/two key ID/raw Ed25519 values equal to the reviewed intent.
- Bind exact client SHA-256, intent SHA-256, diagnostic SHA-256, version,
  revision, channel, URL, key IDs, and whole-second UTC capture time.
- Permit durable verification at the recorded capture instant after the intent
  expires, while requiring the intent to be live during evidence creation.
- Include no private material or runtime override. The evidence proves compiled
  public trust only; Authenticode and installer acceptance remain downstream.

## Consequences

An unsigned artifact can distinguish a genuinely trust-enabled final PE from
one built with missing, different, or disabled configuration. Later signing can
retain the pre-sign client identity and intent while separately proving the
post-sign PE. Diagnostic and evidence become release artifacts but contain only
public information.

## Migration and Rollback

Default-off artifacts contain no such evidence. A failed or stale capture is
discarded and rebuilt from a fresh intent; files are not overwritten. Removing
this boundary disables trust-enabled protected packaging until an equivalent
binary attestation exists.

## Verification

- `python3 Tests/windows_update_product_trust_evidence_test.py`
- reject disabled/wrong URL/key, client/intent/diagnostic mutation, duplicate or
  unknown fields, expired live intent, unsafe files, and overwrite;
- bind the evidence into the unsigned artifact next.
