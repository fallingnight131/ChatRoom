# V1 Identity, Conversation, Contact, and Message Import Runbook

Status: M3 rehearsal runbook. Do not use it to switch production authority yet.

The existing `preview`/`verify-final`/`apply` commands cover V1 `users` identity
material. The additive `conversation-*` commands cover rooms, room memberships,
administrators, and accepted friendships only after identities exist. The
additive `message-*` commands then cover retained message text, recalls,
administrative deletion audit events, translated read sequences, compatibility
maps, and preserved high watermarks. The `contact-*` commands preserve pending
friend requests after identity import without fabricating accepted/rejected
resolution history. The profile-image section exports bounded user/room avatar
evidence only; it does not upload objects or change PostgreSQL. Attachment bytes
and active sessions remain outside this runbook.
Completing an apply does not authorize routing user traffic to Java V2.

## Preconditions

- Use a dedicated PostgreSQL target with all current Flyway migrations already
  applied and validated.
- Build and review the exact repository revision used for the migration.
- Keep the V1 SQLite source and its `-wal`/`-shm` neighbors under the V1 server's
  normal ownership. Do not copy the main file directly.
- Choose new backup and proof paths on encrypted, access-controlled storage.
- Set `CHATROOM_MIGRATION_POSTGRES_URL`,
  `CHATROOM_MIGRATION_POSTGRES_USER`, and
  `CHATROOM_MIGRATION_POSTGRES_PASSWORD` in the operator environment. Never put
  the password in `--args`, logs, chat, or Git.

Examples below run from `Backend/`. Replace placeholders locally; do not commit
real paths, endpoints, or fingerprints.

## Rehearsal before a maintenance window

1. Create an online, WAL-consistent backup and its versioned proof:

   ```bash
   ./gradlew --no-daemon :migration-cli:run --args='backup <v1.db> <new-backup.db> <new-proof.properties>'
   ```

2. Copy the backup and proof to an isolated restore location. Reverify the copied
   artifact rather than the original path:

   ```bash
   ./gradlew --no-daemon :migration-cli:run --args='verify-backup <restored-copy.db> <proof.properties>'
   ```

3. Run the read-only target preview:

   ```bash
   ./gradlew --no-daemon :migration-cli:run --args='preview <v1.db>'
   ```

   Continue only with `status=READY`, zero issues, and zero unexpected target
   rows. Archive the non-secret output with the release evidence.

4. Rehearse the target-independent final input gate with the restored copy and
   the exact fingerprint printed by `backup`:

   ```bash
   ./gradlew --no-daemon :migration-cli:run --args='verify-final <v1.db> <restored-copy.db> <proof.properties> <source-fingerprint>'
   ```

   Require `status=FINAL_INPUT_VERIFIED` and the expected row count. This checks
   the input set but does not prove that all writers have been stopped.

5. Run the repository's isolated full-server rehearsal from the repository root:

   ```bash
   python3 tools/verify_m0.py --v1-identity-restore
   ```

   Archive `build/m0/<host>/v1-identity-restore-evidence.json`. The harness uses
   the matching C++ server and Java CLI to verify both credential generations,
   restored history, and bounded timings. Its
   `production_writer_quiescence_verified=false` field is intentional: it owns
   and can prove exit only for its temporary source process.
6. Separately rehearse and record how operators identify and stop every V1
   writer in the deployment topology. Do not replace this evidence with the
   automated isolated-host result.

## Final apply rehearsal

1. Enter the announced maintenance window and stop all V1 processes that can
   create or update users. Confirm no alternate writer remains.
2. Create a new final backup/proof pair after quiescence. Do not reuse the early
   rehearsal artifact.
3. Run `preview` again and compare the displayed source fingerprint with the
   final proof.
4. Before any target write, run the standalone final gate with that exact
   non-secret fingerprint:

   ```bash
   ./gradlew --no-daemon :migration-cli:run --args='verify-final <v1.db> <final-backup.db> <final-proof.properties> <source-fingerprint>'
   ```

   Continue only with `status=FINAL_INPUT_VERIFIED`. Independently confirm the
   V1 writer processes are still stopped; the command cannot prove quiescence.
5. Supply the same fingerprint as the explicit apply confirmation:

   ```bash
   ./gradlew --no-daemon :migration-cli:run --args='apply <v1.db> <final-backup.db> <final-proof.properties> <source-fingerprint>'
   ```

6. Require `status=APPLIED`, `inserted_rows + already_imported_rows =
   source_rows`, zero issues, and a non-empty `import_run_id`. Independently
   verify the corresponding `chat.identity_import_run` row through an approved
   read-only database channel.
7. Keep V1 stopped only for the bounded rehearsal. Do not enable V2 product
   traffic. For the current additive milestone, discard/reset the rehearsal
   PostgreSQL database if needed and restart V1 from the unchanged source.

## Conversation metadata apply rehearsal

Run this only after the exact identity apply above has succeeded against the
same source, backup, proof, and PostgreSQL target. Keep every V1 writer stopped.

1. Preview the target with the independent conversation graph fingerprint:

   ```bash
   ./gradlew --no-daemon :migration-cli:run --args='conversation-preview <v1.db>'
   ```

   Require `status=READY`, zero issues, and archive the printed
   `conversation_fingerprint_sha256`. It intentionally differs from the identity
   fingerprint because unchanged users do not imply unchanged rooms.

2. Reconcile current source, final backup, physical proof, and the explicitly
   confirmed conversation fingerprint without touching PostgreSQL:

   ```bash
   ./gradlew --no-daemon :migration-cli:run --args='conversation-verify-final <v1.db> <final-backup.db> <final-proof.properties> <conversation-fingerprint>'
   ```

3. Apply using the same confirmed fingerprint:

   ```bash
   ./gradlew --no-daemon :migration-cli:run --args='conversation-apply <v1.db> <final-backup.db> <final-proof.properties> <conversation-fingerprint>'
   ```

4. Require `status=APPLIED`, conversation and membership inserted/already
   counts equal their source counts, zero issues, and a non-empty
   `import_run_id`. Verify the corresponding `chat.conversation_import_run` row.
   Imported `last_read_sequence` remains zero until message import translates
   retained V1 read-message IDs.

## Pending contact-request apply rehearsal

Run this only after identity apply succeeded against the same quiesced source,
final backup/proof, and PostgreSQL target. Conversation import may run before or
after this slice; accepted friendship truth belongs to conversation import.

1. Preview the verified contact graph against PostgreSQL:

   ```bash
   ./gradlew --no-daemon :migration-cli:run --args='contact-preview <v1.db>'
   ```

   Require `status=READY`, zero issues, and archive the independent
   `contact_fingerprint_sha256` plus pending/terminal source counts.

2. Reconcile current source, protected backup, physical proof, and the explicit
   contact fingerprint without touching PostgreSQL:

   ```bash
   ./gradlew --no-daemon :migration-cli:run --args='contact-verify-final <v1.db> <final-backup.db> <final-proof.properties> <contact-fingerprint>'
   ```

3. Apply with the exact same confirmation fingerprint:

   ```bash
   ./gradlew --no-daemon :migration-cli:run --args='contact-apply <v1.db> <final-backup.db> <final-proof.properties> <contact-fingerprint>'
   ```

4. Require `status=APPLIED`; inserted plus already-imported pending counts must
   equal `source_pending_requests`; issues must be zero; and `import_run_id` must
   be non-empty. Verify `chat.contact_request_import_run` through an approved
   read-only database channel. An exact rerun must report zero insertable rows.

## Message history apply rehearsal

Run this only after identity and conversation applies succeeded against the same
quiesced source, final backup/proof, and PostgreSQL target.

1. Preview the verified state/payload bundle against PostgreSQL:

   ```bash
   ./gradlew --no-daemon :migration-cli:run --args='message-preview <v1.db> <final-backup.db> <final-proof.properties>'
   ```

   Require `status=READY`, zero issues, and archive both
   `message_state_fingerprint_sha256` and
   `message_payload_fingerprint_sha256`. The command does not print message
   content or file/profile metadata.

2. Reconcile the current source and protected backup again with both explicit
   fingerprints, without touching PostgreSQL:

   ```bash
   ./gradlew --no-daemon :migration-cli:run --args='message-verify-final <v1.db> <final-backup.db> <final-proof.properties> <state-fingerprint> <payload-fingerprint>'
   ```

3. Apply with the exact same confirmation pair:

   ```bash
   ./gradlew --no-daemon :migration-cli:run --args='message-apply <v1.db> <final-backup.db> <final-proof.properties> <state-fingerprint> <payload-fingerprint>'
   ```

4. Require `status=APPLIED`; inserted plus already-imported counts must equal
   the reported source message/entry counts; issues must be zero; and
   `import_run_id` must be non-empty. Verify `chat.message_import_run` through an
   approved read-only database channel. An identical rerun is permitted and
   must report zero insertable messages and entries.

## Profile-image export rehearsal

Run this after creating the final backup/proof pair while every V1 writer is
still stopped. This command reads only the protected backup and does not require
PostgreSQL or object-storage credentials:

```bash
./gradlew --no-daemon :migration-cli:run --args='profile-image-export <final-backup.db> <final-proof.properties> <new-export-directory>'
```

The source backup must have no adjacent `-wal`, `-shm`, or `-journal` file, and
the destination must not exist. The command verifies the proof, stages a private
temporary input copy, checks the identity fingerprint and SQLite integrity,
then rechecks the protected backup before atomically publishing the export.

Require `status=PROFILE_IMAGES_EXPORTED`, `invalid=0`, and a non-empty
`manifest_sha256`. Preserve the resulting `profile-images.tsv` and
`objects/avatars/sha256/<digest>.png` tree together on encrypted,
access-controlled storage. Every mapped user and room has an explicit manifest
record: missing/empty source data remains `ABSENT`, exact canonical content is
deduplicated as `PRESENT`, and malformed or oversized data is `INVALID`.

Before any downstream use, independently verify the preserved bundle using the
exact manifest hash retained separately with the migration evidence:

```bash
./gradlew --no-daemon :migration-cli:run --args='profile-image-verify <export-directory> <final-proof.properties> <manifest-sha256>'
```

Require `status=PROFILE_IMAGE_EXPORT_VERIFIED` and reconcile the printed counts
with the export evidence. The verifier rejects a substituted manifest, proof
mismatch, invalid record, non-canonical/tampered object, symbolic link, missing
path, or unexpected path. It prints neither target IDs nor local paths.

With identity and conversation import already applied to the rehearsal target,
run the read-only PostgreSQL gate before supplying any object credentials:

```bash
./gradlew --no-daemon :migration-cli:run --args='profile-image-preview <export-directory> <final-proof.properties> <manifest-sha256>'
```

Require `status=READY_FOR_PROVIDER_WRITES` and `issues=0`. Archive the counts of
already registered objects and `provider_objects_to_verify`. Every unique object
must still pass the Provider create-only/exact-retry operation; a PostgreSQL row
does not prove that Provider bytes exist. Stop on a
missing/disabled mapping, closed or wrong-kind room, existing account/room
pointer, conflicting object metadata, active delete claim, or previously
imported manifest. This command validates PostgreSQL only and never contacts the
object provider.

Exit status 2 or `status=PROFILE_IMAGES_BLOCKED` is a stop condition. Remediate
invalid source data under a reviewed plan; never silently drop it or edit the
manifest. This milestone has not yet implemented the object-storage/PostgreSQL
Provider/apply operator command, so even a verified export is evidence only and
must not be uploaded or used to activate avatar handlers. The underlying
PostgreSQL adapter is implemented and tested, but invoking infrastructure pieces
manually is not an approved migration procedure.

## Stop conditions

Stop immediately on a changed fingerprint, backup/proof mismatch, target
conflict, unexpected target account, Flyway validation failure, partial
operational evidence, or missed maintenance-window deadline. Do not edit the
proof, delete target conflicts, or rerun with a different source merely to make
the command pass. Preserve artifacts and investigate under a new reviewed plan.
For conversation commands, also stop on a dangling/duplicate room graph,
missing imported account, unexpected target membership, direct-pair collision,
or any mapping/role/title/timestamp difference. A V1 self friendship is expected
and must reconcile as one self DIRECT conversation with one membership.
For contact commands, also stop on a dangling/self request, invalid status/time,
pending request for accepted friends, reverse duplicate pending pair, missing or
disabled imported account, target request/pair collision, or mapping difference.
For message commands, also stop on either fingerprint changing, source/backup
bundle disagreement, missing or disabled actors, message/sequence/idempotency
collision, unexpected target entries/messages, mapping/event differences, or
read/high-watermark drift. Restore the pre-import PostgreSQL backup after a
failed cutover rehearsal; never delete audit or compatibility rows piecemeal.
