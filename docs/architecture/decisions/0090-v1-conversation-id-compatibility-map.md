# ADR-0090: V1 Conversation ID Compatibility Map

- Status: Accepted
- Date: 2026-08-12
- Related milestone: M3

## Context

V1 room and friendship commands expose independent positive numeric identifiers.
The V2 conversation domain uses one UUID namespace and distinguishes `GROUP`
from `DIRECT`. Recomputing UUIDs at request time would make identity dependent on
an algorithm forever, while adding numeric IDs to the V2 domain would spread a
temporary compatibility concern into every new client and repository.

The two V1 namespaces can contain the same number. A mapping must therefore keep
the source kind, reject changes of association, and prevent a room from pointing
to a direct conversation or a friendship from pointing to a group.

## Decision

- Add `chat.legacy_v1_conversation_map` as an additive compatibility projection.
- Key source identity by `(legacy_kind, legacy_conversation_id)`, where kind is
  exactly `ROOM` or `FRIENDSHIP` and the numeric ID is positive.
- Map `ROOM` to V2 `GROUP` and `FRIENDSHIP` to V2 `DIRECT` through a generated
  target-kind column and a composite foreign key to `conversation(id, kind)`.
- Make `conversation_id` unique so a V2 conversation cannot acquire multiple V1
  identities. Room and friendship numeric IDs may overlap because their source
  namespaces are independent.
- Keep this mapping outside the V2 conversation model and protocol. Only the V1
  import and gateway compatibility adapters may access it.
- Expose it through a transport-independent read-only application port with
  typed source identity and PostgreSQL lookups in both directions. Invalid
  non-positive incoming numeric IDs resolve as absent; infrastructure failures
  remain normalized server errors rather than protocol fields.
- Delete mapping rows automatically if an unused pre-cutover V2 conversation is
  deleted. Once PostgreSQL owns traffic, conversation deletion and restore must
  follow the later authoritative-data retention policy instead.

## Consequences

The stable projection can support V1 directory, history, message, and event
translation without exposing UUIDs to old clients. It does not import rooms,
memberships, friendships, messages, read cursors, or files by itself, and it does
not make PostgreSQL authoritative.

V1 self-friendship rows require a separately reviewed representation because the
current V2 direct-conversation constraint requires two distinct canonical
accounts. The conversation importer must reject or explicitly transform that
case; it must not silently drop it.

The pure pre-write planner now assigns separate UUIDv5 namespaces to rooms and
friendships, canonicalizes direct account pairs, and projects room creator/admin/
member roles. It retains legacy read-message IDs without pretending they are V2
sequences; message import must translate them later. Invalid timestamps, names,
IDs, users, members, administrators, duplicate pairs, negative read pointers,
and self friendships produce safe blocking codes before target comparison.

The source adapter reads only the required current V1 room/friendship graph over
a URI read-only SQLite connection with `query_only`, foreign keys, bounded busy
wait, and `quick_check`. It includes committed WAL rows, requires migrated read
cursor columns, and turns unparseable timestamps into planner issues. Paths and
source names never enter fixed infrastructure errors.

Conversation final-input verification reuses the existing whole-file SQLite
backup artifact and its physical hash/size proof, but produces a separate
in-memory capability from identity verification. It requires exact current-source
and backup conversation plans and supports re-verification during a future
target transaction. This preserves the current identity-only CLI contract while
making conversation import incapable of accepting an identity-only comparison.

V007 adds append-only `conversation_import_run` audit rows. A committed apply
must record the conversation fingerprint, the same physical backup proof, source
room/friendship/membership counts, and exactly reconciled inserted/already-present
counts in the same transaction as target writes. Database checks reject malformed
hashes, negative counts, non-positive backup sizes, and incomplete reconciliation.

## Verification

The disposable PostgreSQL gate migrates clean and restarted databases through
V006, validates Flyway checksums, accepts overlapping room/friendship numbers,
and rejects non-positive IDs, unsupported kinds, kind/target mismatch, duplicate
source association, and duplicate V2 targets. It also proves target deletion
removes only its compatibility mapping. Application and PostgreSQL tests prove
typed same-number room/friendship lookup, reverse UUID projection, invalid-ID
absence, and missing-target absence.
Pure planner tests prove input-order-independent fingerprints and plans,
namespace-separated UUIDv5 identities, canonical direct pairs, role precedence,
retained read pointers, and safe rejection of inconsistent source graphs.
SQLite tests prove WAL visibility without source writes, all three supported
timestamp forms, safe invalid-time blocking, and refusal of an older schema.
Final-input tests prove the same physical backup protects conversation metadata,
and reject both post-backup room drift and a mismatched artifact hash.
The disposable PostgreSQL gate also rejects an audit row whose conversation
result counts do not reconcile with its source counts.

## Rollback

Before traffic cutover, disable the uninstalled Java compatibility path and
leave the additive table unused. Production migrations remain forward-only; do
not run a destructive down migration. SQLite remains authoritative until the
later import and cutover ADR is accepted and verified.
