# V1 Identity Import Runbook

Status: M3 rehearsal runbook. Do not use it to switch production authority yet.

This runbook covers only V1 `users` identity material. Rooms, contacts,
messages, files, avatars, and active sessions are not migrated by this command.
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

4. On an isolated host, start the matching V1 server binary against another copy
   of the restored database, exercise login for designated test accounts from
   both credential generations, stop it cleanly, and record restore duration.
   This manual full-server rehearsal is not automated yet.

## Final apply rehearsal

1. Enter the announced maintenance window and stop all V1 processes that can
   create or update users. Confirm no alternate writer remains.
2. Create a new final backup/proof pair after quiescence. Do not reuse the early
   rehearsal artifact.
3. Run `preview` again and compare the displayed source fingerprint with the
   final proof.
4. Supply that exact non-secret fingerprint as the explicit confirmation:

   ```bash
   ./gradlew --no-daemon :migration-cli:run --args='apply <v1.db> <final-backup.db> <final-proof.properties> <source-fingerprint>'
   ```

5. Require `status=APPLIED`, `inserted_rows + already_imported_rows =
   source_rows`, zero issues, and a non-empty `import_run_id`. Independently
   verify the corresponding `chat.identity_import_run` row through an approved
   read-only database channel.
6. Keep V1 stopped only for the bounded rehearsal. Do not enable V2 product
   traffic. For the current additive milestone, discard/reset the rehearsal
   PostgreSQL database if needed and restart V1 from the unchanged source.

## Stop conditions

Stop immediately on a changed fingerprint, backup/proof mismatch, target
conflict, unexpected target account, Flyway validation failure, partial
operational evidence, or missed maintenance-window deadline. Do not edit the
proof, delete target conflicts, or rerun with a different source merely to make
the command pass. Preserve artifacts and investigate under a new reviewed plan.
