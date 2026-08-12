# ADR-0205: Separate Web Candidate Preview from the Production Origin

- Status: Accepted
- Date: 2026-08-13
- Owners: Web release engineering and operations
- Related milestone: M4
- Supersedes: ADR-0145, ADR-0170 schema details

## Context

Schema 1 required candidate B and retained A at one root HTTPS origin before
mutation. While A is active, that origin cannot also serve B at `/`; satisfying
the check required an unrecorded switch or synthetic evidence.

## Decision

- Advance technical promotion to schema 2 with distinct `candidateBaseUrl` and
  `productionBaseUrl`.
- Require B static bytes/policy and B `/api/health` plus `/ws` routes at one
  preview origin. Require retained A at a different production origin.
- Derive production origin from observed A, not an unverified mutation input.
- Advance authorization to schema 2, bind both origins, and retain `baseUrl` as
  the exact production mutation/post-switch observation origin.
- Reject schema 1. After switching, require fresh B static/routes at production.

## Consequences

B can be verified without exposing it to production traffic. Preview must apply
the same response policy and routes, but cannot prove production edge behavior.

## Migration and Rollback

Regenerate technical and authorization records under schema 2. Do not silently
reinterpret the ambiguous single origin in schema 1.

## Verification

- `python3 Tests/web_promotion_evidence_test.py`
- `python3 Tests/web_release_authorization_test.py`
- downstream Web execution/completion/rollback suites reconstruct schema 2.
