package com.fallingnight.chat.persistence.postgres.migration;

import com.fallingnight.chat.application.compatibility.v1.LegacyV1ConversationKind;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.time.Instant;
import java.util.List;

/** Builds a fully re-verifiable mixed-message capability for PostgreSQL integration tests. */
public final class V1UnifiedAttachmentImportFixture {
    private V1UnifiedAttachmentImportFixture() { }

    public static VerifiedV1UnifiedMessageImportBundle create(Path directory) throws Exception {
        Path source = directory.resolve("unified-source.sqlite");
        createSource(source);
        Path backup = directory.resolve("unified-backup.sqlite");
        Files.copy(source, backup);
        VerifiedV1IdentityBackup proof = new VerifiedV1IdentityBackup(
                "1".repeat(64), V1SqliteIdentityBackup.sha256(backup), 1,
                Files.size(backup), Instant.parse("2026-08-13T12:00:00Z"));
        VerifiedV1MessageStateImportInput state =
                new V1MessageStateImportInputVerifier().verify(source, backup, proof);
        VerifiedV1MessagePayloadImportInput payload =
                new V1MessagePayloadImportInputVerifier().verify(source, backup, proof);
        V1AttachmentSourcePlan sourcePlan = new V1SqliteAttachmentSource(source).readPlan();
        PlannedV1AttachmentSource attachment = sourcePlan.attachments().getFirst();
        V1AttachmentObjectEvidenceBundle evidence = new V1AttachmentObjectEvidenceBundle(
                sourcePlan.sourceFingerprintSha256(), List.of(new V1AttachmentObjectEvidence(
                        LegacyV1ConversationKind.ROOM, 7,
                        "attachments/" + attachment.attachmentId(), "application/pdf", 123,
                        new byte[32], Instant.parse("2026-01-02T03:04:07Z"))));
        VerifiedV1AttachmentImportInput attachments =
                new V1AttachmentImportInputVerifier().verify(
                        source, backup, proof, evidence);
        return new V1UnifiedMessageImportBundleVerifier().combine(
                state, payload, attachments);
    }

    private static void createSource(Path database) throws Exception {
        try (Connection connection = DriverManager.getConnection(
                    "jdbc:sqlite:" + database.toAbsolutePath());
                Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE users(id INTEGER PRIMARY KEY)");
            statement.execute("CREATE TABLE rooms(id INTEGER PRIMARY KEY, name TEXT, "
                    + "creator_id INTEGER, created_at TEXT)");
            statement.execute("CREATE TABLE room_settings(room_id INTEGER PRIMARY KEY, "
                    + "max_file_size INTEGER, total_file_space INTEGER, "
                    + "max_file_count INTEGER, max_members INTEGER)");
            statement.execute("CREATE TABLE room_members(room_id INTEGER, user_id INTEGER, "
                    + "joined_at TEXT, last_read_msg_id INTEGER)");
            statement.execute("CREATE TABLE room_admins(room_id INTEGER, user_id INTEGER)");
            statement.execute("CREATE TABLE friendships(id INTEGER, user_id1 INTEGER, "
                    + "user_id2 INTEGER, created_at TEXT, user1_last_read_msg_id INTEGER, "
                    + "user2_last_read_msg_id INTEGER)");
            statement.execute("CREATE TABLE room_message_sequences(room_id INTEGER, "
                    + "last_sequence INTEGER)");
            statement.execute("CREATE TABLE friendship_message_sequences(friendship_id INTEGER, "
                    + "last_sequence INTEGER)");
            statement.execute("CREATE TABLE room_message_deletion_events(id INTEGER, "
                    + "room_id INTEGER, operator_user_id INTEGER, operator_name TEXT, "
                    + "client_operation_id TEXT, command_fingerprint TEXT, mode TEXT, "
                    + "message_ids_json TEXT, file_ids_json TEXT, cutoff_ms INTEGER, "
                    + "deleted_count INTEGER, sequence INTEGER, created_at TEXT)");
            statement.execute(messageTable("messages", "room_id", "user_id"));
            statement.execute(messageTable("friend_messages", "friendship_id", "sender_id"));
            statement.execute(fileTable("files", "room_id"));
            statement.execute(fileTable("friend_files", "friendship_id"));
            statement.execute("INSERT INTO users VALUES (1)");
            statement.execute("INSERT INTO rooms VALUES (9, 'Room', 1, "
                    + "'2026-01-02 03:04:05')");
            statement.execute("INSERT INTO room_settings VALUES "
                    + "(9, 10737418240, 10737418240, 1500, 50)");
            statement.execute("INSERT INTO room_members VALUES "
                    + "(9, 1, '2026-01-02 03:04:05', 100)");
            statement.execute("INSERT INTO room_message_sequences VALUES (9, 1)");
            statement.execute("INSERT INTO files VALUES (7, 9, 1, 'report.pdf', "
                    + "'/private/report.pdf', 123, 0, '', NULL, "
                    + "'2026-01-02 03:04:05', 'https://legacy.invalid/secret')");
            statement.execute("INSERT INTO messages VALUES (100, 9, 1, 'ignored', 'file', "
                    + "'report.pdf', 123, 7, 0, '', '', 0, 1, NULL, "
                    + "'2026-01-02 03:04:06')");
        }
    }

    private static String messageTable(String table, String conversation, String sender) {
        return "CREATE TABLE " + table + "(id INTEGER PRIMARY KEY, " + conversation
                + " INTEGER, " + sender + " INTEGER, content TEXT, content_type TEXT, "
                + "file_name TEXT, file_size INTEGER, file_id INTEGER, file_cleared INTEGER, "
                + "clear_reason TEXT, thumbnail TEXT, recalled INTEGER, sequence INTEGER, "
                + "mutation_sequence INTEGER, created_at TEXT)";
    }

    private static String fileTable(String table, String conversation) {
        return "CREATE TABLE " + table + "(id INTEGER PRIMARY KEY, " + conversation
                + " INTEGER, user_id INTEGER, file_name TEXT, file_path TEXT, "
                + "file_size INTEGER, cleared INTEGER, clear_reason TEXT, cleared_at TEXT, "
                + "created_at TEXT, cos_url TEXT)";
    }
}
