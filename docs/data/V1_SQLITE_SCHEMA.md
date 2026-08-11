# V1 SQLite Schema Baseline

Status: active server schema created in `DatabaseManager::initialize()`.

Database path defaults to `chatroom.db` beside the server executable and can be
overridden with `CHATROOM_DB_PATH`. Each Qt SQL connection enables WAL and
foreign keys.

Run `python3 tools/m0_inventory.py --check` to detect table/index inventory drift.

## Tables

### Identity

`users`

- `id` integer primary key;
- unique `username` used as current unique ID;
- `display_name` added by startup migration;
- `password_hash` stores a self-describing libsodium Argon2id string for new or
  upgraded accounts; legacy rows contain a 64-character SHA-256 digest;
- `salt` is retained only for legacy SHA-256 verification and is empty after an
  Argon2id write;
- `created_at`, `last_login`, `last_uid_change`.

`user_avatars`

- one BLOB avatar per user;
- cascades on user deletion.

### Rooms

`rooms`

- room ID, name, creator ID;
- nullable optional room-password secret: Argon2id for new/updated rows, with
  legacy plaintext upgraded after successful verification;
- creation time.

`room_members`

- composite primary key `(room_id, user_id)`;
- join time;
- `last_read_msg_id` added by startup migration.

`room_admins`

- composite primary key `(room_id, user_id)`.

`room_settings`

- one row per room;
- maximum file size and total file space default to 10 GiB;
- maximum file count defaults to 1500;
- maximum members defaults to 50.

`room_avatars`

- one BLOB avatar per room.

### Room messages and files

`messages`

- integer ID, room ID, sender user ID;
- nullable `client_message_id` and per-room `sequence` added by the reliable
  message migration;
- nullable `mutation_sequence` records the newer room cursor allocated by an
  accepted recall while leaving the creation sequence immutable;
- content and string `content_type`;
- file name, size, and file ID;
- `file_cleared` and `clear_reason`;
- recall flag and Base64/string thumbnail;
- creation timestamp.

`room_message_sequences`

- one durable high-watermark row per room;
- sequence allocation and message insertion share one transaction;
- the watermark does not move backwards after physical message deletion.

`room_message_deletion_events`

- one durable audit/synchronization row per new administrator delete command;
- room, operator identity/display-name snapshot, and `client_operation_id`;
- mode, deleted-message/file ID JSON, cutoff timestamp, deleted count, and
  event sequence;
- currently an expand-phase table under ADR-0020; runtime deletion does not
  write or replay it until the behavioral phase is enabled.

`files`

- room and uploader IDs;
- name, local path, and size;
- cleared state, reason, and timestamp;
- `cos_url` added by migration;
- creation timestamp.

### Contacts and direct messages

`friend_requests`

- sender, recipient, status, and creation timestamp.

`friendships`

- normalized pair `user_id1`, `user_id2` with a unique constraint;
- per-user last-read message columns are intended to be added by migration.

`friend_messages`

- friendship ID and sender ID;
- nullable sender `client_message_id` and per-friendship `sequence`;
- nullable `mutation_sequence` for replayable recall state;
- content and string content type;
- file metadata and recall state;
- file-cleared state and reason;
- thumbnail and creation timestamp.

`friendship_message_sequences`

- one durable high-watermark row per friendship;
- shares the room-sequence migration/allocation algorithm and does not move
  backwards after physical deletion.

`friend_files`

- friendship and uploader IDs;
- name, local path, size, cleared state, optional COS URL, and timestamps.

## Declared Explicit Indexes

- `idx_msg_room_time` on `messages(room_id, created_at)`;
- `idx_messages_room_id_id` on `messages(room_id, id)`;
- unique partial `idx_messages_room_sequence` on
  `messages(room_id, sequence)`;
- partial `idx_messages_room_mutation_sequence` on
  `messages(room_id, mutation_sequence)`;
- unique partial `idx_messages_sender_client_id` on
  `messages(user_id, client_message_id)`;
- unique `idx_room_deletion_events_sequence` on
  `room_message_deletion_events(room_id, sequence)`;
- unique `idx_room_deletion_events_operator_operation` on
  `room_message_deletion_events(operator_user_id, client_operation_id)`;
- `idx_friend_msg_time` on `friend_messages(friendship_id, created_at)`;
- `idx_friend_messages_friendship_id_id` on
  `friend_messages(friendship_id, id)`;
- unique partial `idx_friend_messages_friendship_sequence` on
  `friend_messages(friendship_id, sequence)`;
- partial `idx_friend_messages_mutation_sequence` on
  `friend_messages(friendship_id, mutation_sequence)`;
- unique partial `idx_friend_messages_sender_client_id` on
  `friend_messages(sender_id, client_message_id)`;
- `idx_room_members_user` on `room_members(user_id, room_id)`;
- `idx_files_room_active` on `files(room_id, cleared, created_at, id)`;
- `idx_friend_requests_recipient` on
  `friend_requests(to_user_id, status, created_at)`;
- `idx_friend_requests_pair` on
  `friend_requests(from_user_id, to_user_id, status)`;
- `idx_friendships_user2` on `friendships(user_id2)`.

SQLite also creates indexes for primary-key and unique constraints. The schema
regression asserts `EXPLAIN QUERY PLAN` index use for reconnect membership,
room/direct unread counts, active files, friend request recipient/pair lookups,
the second normalized friendship participant, and sequence resume. Remaining
queries should be added based on measured workloads rather than indexed
speculatively.

## Relationships

```mermaid
erDiagram
    users ||--o{ rooms : creates
    users ||--o{ room_members : joins
    rooms ||--o{ room_members : contains
    rooms ||--o{ messages : contains
    rooms ||--o| room_message_sequences : sequences
    rooms ||--o{ room_message_deletion_events : audits
    users ||--o{ messages : sends
    rooms ||--o{ files : owns
    users ||--o{ files : uploads
    users ||--o{ friend_requests : participates
    users ||--o{ friendships : participates
    friendships ||--o{ friend_messages : contains
    friendships ||--o| friendship_message_sequences : sequences
    friendships ||--o{ friend_files : owns
```

Foreign keys generally cascade relationship/message/file metadata when a parent
user, room, or friendship is deleted. Physical local/COS object deletion requires
application handling and is not performed by SQLite foreign keys.

## Startup Migration Behavior

V1 uses `CREATE TABLE IF NOT EXISTS`, unconditional `ALTER TABLE ADD COLUMN`, and
targeted `PRAGMA table_info` checks rather than numbered migrations.

Observed order:

1. create users, rooms, room members, room messages, files, administrators,
   settings, and avatar tables;
2. execute several additive alters and default-value backfills;
3. linearly backfill room messages with a null sequence and legacy recalled rows
   with a null mutation sequence in existing-ID order, then create/raise durable
   room high-watermarks and unique indexes;
4. add the room read pointer;
5. create friend request, friendship, direct-message, and friend-file tables;
6. add friendship read pointers, direct reliable-message columns, creation and
   recall-sequence backfill/high-watermarks, and unique indexes after
   friendships exist;
7. expire old files;
8. mark the manager initialized after the full schema path completes.

`Tests/DatabaseSchemaTest.cpp` verifies that a clean first initialization has all
required migrated columns/tables, passes `PRAGMA integrity_check`, uses room,
friend, recall-mutation, and deletion-event indexes for resume/idempotency, and
produces the same schema after a simulated restart. The V1 reliability
integration tests also insert intentionally null sequences and prove startup
resumes those partial migrations.

## Retention

Room and friend files older than seven days are marked cleared by the current
expiry process. Associated messages retain metadata with `file_cleared` and a
reason. Local files are removed and COS URLs are returned to the caller for
object deletion.

## Migration Risks

- no durable schema version or migration history;
- migration errors are not consistently distinguished from expected duplicate
  column errors;
- schema initialization and retention side effects share one startup function;
- no complete historical-schema fixture covering every prior release;
- no documented backup/restore verification before migration;
- limited explicit query indexes;
- primary server storage is a single SQLite file.

## M0 Follow-up Verification

Schema verification currently covers:

1. clean first initialization;
2. second initialization/restart;
3. required migrated columns/table;
4. sequence-resume query-plan index use;
5. `PRAGMA integrity_check`;
6. interrupted nullable-sequence recovery in the integration suite.

Remaining follow-up should cover full historical-schema fixtures and foreign-key
cascades before Java/PostgreSQL migration.
