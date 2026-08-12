# ADR-0256: Preserve V1 Text/Emoji Presentation Type

- Status: Accepted
- Date: 2026-08-13
- Related milestone: M3
- Refines: ADR-0094

## Decision

Keep the ADR-0094 canonical model: both V1 text and emoji bodies remain V2 UTF-8
content type 1. Add nullable `legacy_content_type` to the isolated V1 message
mapping with only `text` and `emoji` accepted. New imports and runtime V1 writes
must fill it in the same transaction as the mapping so compatibility history can
reconstruct the original presentation without creating an unregistered V2
content type.

Existing pre-cutover mappings may be null after additive V020 migration because
PostgreSQL cannot infer whether canonical type 1 originated as text or emoji.
The verified offline importer treats an otherwise exact null mapping as
backfillable and fills it only from the reverified V1 source plan. Runtime exact
retry requires the stored legacy type to match the request. A missing type at
history-serving time fails closed until the verified import repair is applied.

Rollback restores the pre-V020 database backup. Older binaries ignore the new
nullable column; no V1 or V2 wire field changes.

## Verification

Planner tests prove text and emoji remain canonical type 1 while retaining
distinct legacy presentation values. Real PostgreSQL migration/import tests
verify the column constraint, first apply, exact rerun, and null backfill. Runtime
message tests continue proving atomic canonical/mapping creation.
