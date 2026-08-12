# ADR-0190: Signed Windows Candidate Product-Trust Closure

- Status: Accepted
- Date: 2026-08-12
- Owners: Windows release engineering and security
- Related milestone: M4
- Extends: ADR-0162, ADR-0165, ADR-0167, ADR-0189

## Context

Protected Authenticode signing still accepted the ordinary null-trust artifact.
Even after redirecting intake to ADR-0189, retaining only pre-sign trust evidence
would not prove that the exact signed `ChatClient.exe` distributed in Setup has
the reviewed stable/beta URL and Ed25519 keys.

## Decision

- Change protected-signing intent identity to the exact channel-specific
  `unsigned-product-trust` artifact emitted by ADR-0189.
- Require schema-4 product trust at protected intake and require every client,
  helper, and Setup input to remain unsigned before the explicit signing steps.
- Immediately after signing the client PE, execute its side-effect-free trust
  diagnostic, require byte-for-byte JSON equality with the unsigned diagnostic,
  and create new ADR-0187 evidence bound to the exact signed PE.
- Advance the signed Windows candidate from schema 5 to schema 6. Close the
  original public trust intent, signed-PE diagnostic/evidence, primary public
  PEM, and optional secondary public PEM beside Authenticode, install, and
  protected-signing evidence.
- Reconstruct product trust during assembly and every later candidate audit.
  Rehashing a changed diagnostic, intent, evidence, or public key must not make
  the candidate valid.
- Keep update-manifest signing and channel publication in their existing
  independent trust domains.

## Consequences

The signed unpublished candidate now proves both Windows publisher trust and
the exact update authority the final client will accept. Installation evidence
already requires the installed client bytes to equal this signed PE, so the
same compiled trust is transitively bound to the installed result. Historical
schema-5 candidates remain audit records but are rejected by current tooling.
No positive protected Windows execution is claimed from repository-only tests.

## Migration and Rollback

Only dispatch protected signing with an ADR-0189 artifact run ID. Rollback may
restore the previous workflow and schema-5 tooling only before any schema-6
candidate is used downstream; never relabel a null-trust or schema-5 candidate.
Downstream update signing automatically fails closed until it consumes schema 6.

## Verification

- `python3 Tests/windows_protected_release_intent_test.py`
- `python3 Tests/windows_protected_signing_workflow_test.py`
- `python3 Tests/windows_release_candidate_test.py`
- `python3 Tests/windows_update_channel_candidate_test.py`
- relevant downstream Windows update candidate/execution/probe suites.
