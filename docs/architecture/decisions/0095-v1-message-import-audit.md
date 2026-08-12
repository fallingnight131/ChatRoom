# ADR-0095: V1 Message Import Audit

- Status: Accepted
- Date: 2026-08-12
- Owners: project maintainers
- Related milestone: M3

## Context

Message import spans retained messages, recall/deletion conversation entries,
synthetic legacy devices, translated member read cursors, two logical source
fingerprints, and one physical SQLite backup proof. Reusing identity or
conversation audit rows would hide partial message outcomes behind unrelated
counts.

## Decision

- Add an append-only `message_import_run` for each successfully committed
  message apply attempt.
- Persist the sequence-state fingerprint, full payload/attachment-metadata
  fingerprint, physical backup hash/size/time, source counts, and result counts.
- Let PostgreSQL enforce reconciliation for messages, all conversation entries,
  legacy devices, and translated member read cursors. Recall entries plus
  deletion entries are counted separately from creation messages.
- Write the audit row in the same serializable transaction as target data and
  final source re-verification. A transaction without a reconciled audit row is
  not a successful import.
- Store no SQLite path, message body, filename, thumbnail, credential, account
  name, or production endpoint in the audit.

## Verification and Rollback

Disposable PostgreSQL verification covers clean/restart migration and rejects
negative, malformed-hash, and mismatched reconciliation rows. Before message
apply exists this table is empty and additive. After applies exist, rollback
requires the documented pre-import database restore rather than deleting audit
evidence independently.

The implemented repeatable-read preview performs no writes and fail-closes on
conversation/mapping/high-watermark drift, legacy-device conflicts, any of the
three message uniqueness identities, creation/recall/deletion entry differences,
mapping conflicts, unexpected target rows, missing memberships, or read-cursor
drift. It reports only typed numeric source identities and fixed issue codes;
message bodies and operator/profile metadata are never included.
