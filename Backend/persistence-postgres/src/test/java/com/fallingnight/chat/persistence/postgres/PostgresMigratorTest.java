package com.fallingnight.chat.persistence.postgres;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.fallingnight.chat.application.compatibility.v1.LegacyV1AccountIdentity;
import com.fallingnight.chat.application.compatibility.v1.LegacyV1ConversationIdentity;
import com.fallingnight.chat.application.compatibility.v1.LegacyV1ConversationKind;
import com.fallingnight.chat.application.compatibility.v1.LegacyV1MessageIdentity;
import com.fallingnight.chat.application.identity.AccountCredential;
import com.fallingnight.chat.application.identity.ClientDescriptor;
import com.fallingnight.chat.application.identity.ClientPlatform;
import com.fallingnight.chat.application.identity.IssuedSession;
import com.fallingnight.chat.application.identity.StoredCredential;
import com.fallingnight.chat.application.conversation.ConversationDirectoryPage;
import com.fallingnight.chat.application.conversation.ConversationDirectoryQuery;
import com.fallingnight.chat.application.conversation.ConversationKind;
import com.fallingnight.chat.application.messaging.MessageHistoryQuery;
import com.fallingnight.chat.application.messaging.MessageHistoryResult;
import com.fallingnight.chat.application.messaging.ConversationEntryHistoryResult;
import com.fallingnight.chat.application.messaging.ConversationHistoryEntry;
import com.fallingnight.chat.application.messaging.MessageSubmission;
import com.fallingnight.chat.application.messaging.MessageSubmissionResult;
import com.fallingnight.chat.application.security.SecretBytes;
import com.fallingnight.chat.persistence.postgres.migration.PostgresV1IdentityImporter;
import com.fallingnight.chat.persistence.postgres.migration.PostgresV1ConversationImporter;
import com.fallingnight.chat.persistence.postgres.migration.PostgresV1MessageImporter;
import com.fallingnight.chat.persistence.postgres.migration.PlannedV1ConversationCursor;
import com.fallingnight.chat.persistence.postgres.migration.PlannedV1HistoricalMessage;
import com.fallingnight.chat.persistence.postgres.migration.PlannedV1LegacyDevice;
import com.fallingnight.chat.persistence.postgres.migration.PlannedV1MemberReadCursor;
import com.fallingnight.chat.persistence.postgres.migration.V1MessageImportReport;
import com.fallingnight.chat.persistence.postgres.migration.V1MessageImportException;
import com.fallingnight.chat.persistence.postgres.migration.V1MessageImportBundleVerifier;
import com.fallingnight.chat.persistence.postgres.migration.V1MessagePayloadImportInputVerifier;
import com.fallingnight.chat.persistence.postgres.migration.V1MessagePayloadImportPlanner;
import com.fallingnight.chat.persistence.postgres.migration.V1MessagePayloadSourceException;
import com.fallingnight.chat.persistence.postgres.migration.V1MessageStateImportInputVerifier;
import com.fallingnight.chat.persistence.postgres.migration.V1MessageTargetImportPlan;
import com.fallingnight.chat.persistence.postgres.migration.V1MessageTargetImportPlanner;
import com.fallingnight.chat.persistence.postgres.migration.V1ConversationImportInputVerifier;
import com.fallingnight.chat.persistence.postgres.migration.V1ConversationImportException;
import com.fallingnight.chat.persistence.postgres.migration.V1ConversationImportPlanner;
import com.fallingnight.chat.persistence.postgres.migration.V1ConversationImportReport;
import com.fallingnight.chat.persistence.postgres.migration.V1IdentityImportException;
import com.fallingnight.chat.persistence.postgres.migration.V1IdentityImportInputVerifier;
import com.fallingnight.chat.persistence.postgres.migration.V1IdentityImportPlanner;
import com.fallingnight.chat.persistence.postgres.migration.V1IdentityImportReport;
import com.fallingnight.chat.persistence.postgres.migration.V1IdentitySourceException;
import com.fallingnight.chat.persistence.postgres.migration.V1SqliteIdentityBackup;
import com.fallingnight.chat.persistence.postgres.migration.VerifiedV1IdentityBackup;
import com.fallingnight.chat.persistence.postgres.migration.VerifiedV1IdentityImportInput;
import com.fallingnight.chat.persistence.postgres.migration.VerifiedV1ConversationImportInput;
import java.nio.file.Path;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.MethodOrderer.OrderAnnotation;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.io.TempDir;
import org.postgresql.ds.PGSimpleDataSource;

@TestMethodOrder(OrderAnnotation.class)
class PostgresMigratorTest {
    private static final String URL = System.getenv("CHATROOM_TEST_POSTGRES_URL");
    private static final String USER = System.getenv("CHATROOM_TEST_POSTGRES_USER");
    private static final String PASSWORD = System.getenv("CHATROOM_TEST_POSTGRES_PASSWORD");

    @TempDir
    Path temporary;

    @Test
    @Order(1)
    void migratesCleanDatabaseAndRestartValidatesWithoutReapplying() throws Exception {
        requireDatabase();
        PostgresMigrator first = new PostgresMigrator(URL, USER, PASSWORD);
        assertEquals(12, first.migrate());
        first.validate();

        PostgresMigrator restarted = new PostgresMigrator(URL, USER, PASSWORD);
        assertEquals(0, restarted.migrate());
        restarted.validate();

        try (Connection connection = connect()) {
            assertEquals(
                    Set.of("account", "device", "device_session", "conversation",
                            "conversation_member", "direct_conversation", "message",
                            "identity_import_run", "legacy_v1_account_map",
                            "legacy_v1_conversation_map", "conversation_import_run",
                            "conversation_entry", "message_recall_event",
                            "messages_deleted_event", "legacy_v1_message_map",
                            "legacy_v1_deletion_event_map", "message_import_run",
                            "attachment"),
                    applicationTables(connection));
            proveSequenceAndIdempotencyConstraints(connection);
            proveMessageImportAuditConstraints(connection);
            proveAttachmentRegistryConstraints(connection);
        }
        proveLegacyV1MappingConstraints();
        proveLegacyV1ConversationMappingConstraints();
        proveConversationImportAuditConstraints();
    }

    @Test
    @Order(4)
    void refusesNonPostgresUrlsBeforeConnecting() {
        assertThrows(IllegalArgumentException.class,
                () -> new PostgresMigrator("jdbc:sqlite:test.db", "", ""));
    }

    @Test
    @Order(5)
    void appendsMessagesIdempotentlyAndReadsOnlyForActiveMembers() throws Exception {
        requireDatabase();
        truncateApplicationData();
        UUID account = UUID.randomUUID();
        UUID device = UUID.randomUUID();
        UUID conversation = UUID.randomUUID();
        seedMessageOwner(account, device, conversation);
        PostgresMessageAdapter adapter = new PostgresMessageAdapter(dataSource());
        MessageSubmission first = new MessageSubmission(
                conversation, account, device, "client-race", 100, new byte[] {1, 2, 3});

        List<MessageSubmissionResult.Accepted> raced = raceSubmit(adapter, first);
        assertEquals(2, raced.size());
        assertEquals(1, raced.stream().filter(result -> !result.duplicate()).count());
        assertEquals(1, raced.stream().filter(MessageSubmissionResult.Accepted::duplicate).count());
        assertEquals(raced.get(0).messageId(), raced.get(1).messageId());
        assertEquals(1, raced.get(0).conversationSequence());
        assertEquals(raced.get(0).acceptedAt(), raced.get(1).acceptedAt());

        assertEquals(
                MessageSubmissionResult.Rejected.IDEMPOTENCY_CONFLICT,
                adapter.submit(
                        new MessageSubmission(
                                conversation,
                                account,
                                device,
                                "client-race",
                                100,
                                new byte[] {9})));

        MessageSubmissionResult.Accepted second = (MessageSubmissionResult.Accepted) adapter.submit(
                new MessageSubmission(
                        conversation, account, device, "client-2", 101, new byte[] {4}));
        assertEquals(2, second.conversationSequence());
        assertEquals(2, conversationEntryCount(conversation));
        executeLegacyMessageMappings(
                conversation, raced.getFirst().messageId(), second.messageId());
        PostgresLegacyV1MessageProjection legacyMessages =
                new PostgresLegacyV1MessageProjection(dataSource());
        LegacyV1MessageIdentity firstLegacy = new LegacyV1MessageIdentity(
                LegacyV1ConversationKind.FRIENDSHIP,
                909,
                1001,
                conversation,
                raced.getFirst().messageId());
        assertEquals(Optional.of(firstLegacy), legacyMessages.findByLegacyId(
                LegacyV1ConversationKind.FRIENDSHIP, 1001));
        assertEquals(Optional.of(firstLegacy),
                legacyMessages.findByMessageId(raced.getFirst().messageId()));
        assertTrue(legacyMessages.findByLegacyId(
                LegacyV1ConversationKind.ROOM, 1001).isEmpty());

        MessageHistoryResult.Page firstPage = (MessageHistoryResult.Page) adapter.readAfter(
                new MessageHistoryQuery(conversation, account, 0, 1));
        assertEquals(1, firstPage.messages().size());
        assertEquals(1, firstPage.nextSequence());
        assertEquals(2, firstPage.latestSequence());
        assertTrue(firstPage.hasMore());
        assertEquals(new byte[] {1, 2, 3}.length, firstPage.messages().getFirst().payload().length);

        MessageHistoryResult.Page secondPage = (MessageHistoryResult.Page) adapter.readAfter(
                new MessageHistoryQuery(conversation, account, firstPage.nextSequence(), 100));
        assertEquals(List.of(second.messageId()),
                secondPage.messages().stream().map(message -> message.messageId()).toList());
        assertFalse(secondPage.hasMore());
        assertEquals(2, secondPage.nextSequence());
        assertMessageHistoryIndexEligible(conversation);
        assertEquals(
                MessageHistoryResult.Rejected.NOT_AUTHORIZED,
                adapter.readAfter(new MessageHistoryQuery(
                        conversation, UUID.randomUUID(), 0, 10)));

        leaveConversation(conversation, account);
        assertEquals(
                MessageSubmissionResult.Rejected.NOT_AUTHORIZED,
                adapter.submit(
                        new MessageSubmission(
                                conversation, account, device, "client-3", 100, new byte[0])));
        assertEquals(
                MessageHistoryResult.Rejected.NOT_AUTHORIZED,
                adapter.readAfter(new MessageHistoryQuery(conversation, account, 0, 10)));
    }

    @Test
    @Order(6)
    void listsOnlyActiveConversationMembershipWithStableCompositeCursor() throws Exception {
        requireDatabase();
        truncateApplicationData();
        UUID account = UUID.randomUUID();
        UUID peer = UUID.randomUUID();
        UUID direct = UUID.fromString("40000000-0000-4000-8000-000000000001");
        UUID group = UUID.fromString("40000000-0000-4000-8000-000000000002");
        try (Connection connection = connect()) {
            execute(connection,
                    "INSERT INTO chat.account(id, username_key, display_name, password_hash) "
                            + "VALUES (?, 'directory-owner', 'Owner', "
                            + "'$argon2id$v=19$m=65536,t=2,p=1$test$fixture'), "
                            + "(?, 'directory-peer', 'Peer Name', "
                            + "'$argon2id$v=19$m=65536,t=2,p=1$test$fixture')",
                    account, peer);
            execute(connection,
                    "INSERT INTO chat.conversation(id, kind, title, next_sequence, updated_at) "
                            + "VALUES (?, 'DIRECT', NULL, 3, ?), "
                            + "(?, 'GROUP', 'Project Room', 6, ?)",
                    direct, OffsetDateTime.parse("2026-08-12T11:00:00Z"),
                    group, OffsetDateTime.parse("2026-08-12T11:00:00Z"));
            execute(connection,
                    "INSERT INTO chat.direct_conversation"
                            + "(conversation_id, first_account_id, second_account_id) "
                            + "VALUES (?, LEAST(?, ?), GREATEST(?, ?))",
                    direct, account, peer, account, peer);
            execute(connection,
                    "INSERT INTO chat.conversation_member"
                            + "(conversation_id, account_id, role, last_read_sequence) "
                            + "VALUES (?, ?, 'MEMBER', 1), (?, ?, 'OWNER', 4), "
                            + "(?, ?, 'MEMBER', 0)",
                    direct, account, group, account, direct, peer);
        }

        PostgresConversationDirectoryAdapter adapter =
                new PostgresConversationDirectoryAdapter(dataSource());
        ConversationDirectoryPage first = adapter.list(new ConversationDirectoryQuery(
                account, Optional.empty(), 1));
        assertEquals(1, first.conversations().size());
        assertEquals(group, first.conversations().getFirst().conversationId());
        assertEquals("Project Room", first.conversations().getFirst().displayName());
        assertEquals(5, first.conversations().getFirst().latestSequence());
        assertTrue(first.hasMore());

        ConversationDirectoryPage second = adapter.list(new ConversationDirectoryQuery(
                account, first.next(), 1));
        assertEquals(direct, second.conversations().getFirst().conversationId());
        assertEquals(ConversationKind.DIRECT, second.conversations().getFirst().kind());
        assertEquals("Peer Name", second.conversations().getFirst().displayName());
        assertFalse(second.hasMore());

        leaveConversation(group, account);
        ConversationDirectoryPage active = adapter.list(new ConversationDirectoryQuery(
                account, Optional.empty(), 100));
        assertEquals(List.of(direct), active.conversations().stream()
                .map(summary -> summary.conversationId()).toList());
        disableAccount(account);
        assertTrue(adapter.list(new ConversationDirectoryQuery(
                account, Optional.empty(), 100)).conversations().isEmpty());
    }

    @Test
    @Order(7)
    void translatesTypedV1ConversationIdsInBothDirections() throws Exception {
        requireDatabase();
        truncateApplicationData();
        UUID room = UUID.randomUUID();
        UUID friendship = UUID.randomUUID();
        try (Connection connection = connect()) {
            execute(connection,
                    "INSERT INTO chat.conversation(id, kind, title) "
                            + "VALUES (?, 'GROUP', 'Projection Room'), (?, 'DIRECT', NULL)",
                    room, friendship);
            execute(connection,
                    "INSERT INTO chat.legacy_v1_conversation_map"
                            + "(legacy_kind, legacy_conversation_id, conversation_id) "
                            + "VALUES ('ROOM', 17, ?), ('FRIENDSHIP', 17, ?)",
                    room, friendship);
        }

        PostgresLegacyV1ConversationProjection projection =
                new PostgresLegacyV1ConversationProjection(dataSource());
        assertEquals(
                Optional.of(new LegacyV1ConversationIdentity(
                        LegacyV1ConversationKind.ROOM, 17, room)),
                projection.findByLegacyId(LegacyV1ConversationKind.ROOM, 17));
        assertEquals(
                Optional.of(new LegacyV1ConversationIdentity(
                        LegacyV1ConversationKind.FRIENDSHIP, 17, friendship)),
                projection.findByLegacyId(LegacyV1ConversationKind.FRIENDSHIP, 17));
        assertEquals(
                Optional.of(new LegacyV1ConversationIdentity(
                        LegacyV1ConversationKind.ROOM, 17, room)),
                projection.findByConversationId(room));
        assertEquals(Optional.empty(),
                projection.findByLegacyId(LegacyV1ConversationKind.ROOM, 0));
        assertEquals(Optional.empty(), projection.findByConversationId(UUID.randomUUID()));
    }

    @Test
    @Order(8)
    void previewsAppliesReconcilesAndAuditsV1ConversationImport() throws Exception {
        requireDatabase();
        truncateApplicationData();
        Path source = temporary.resolve("conversation-source.db");
        Path backup = temporary.resolve("conversation-backup.db");
        createConversationSource(source);
        var proof = new V1SqliteIdentityBackup(
                Clock.fixed(Instant.parse("2026-08-12T12:00:00Z"), ZoneOffset.UTC))
                .createVerified(source, backup);
        VerifiedV1ConversationImportInput input =
                new V1ConversationImportInputVerifier().verify(source, backup, proof);
        seedConversationImportAccounts();
        PostgresV1ConversationImporter importer =
                new PostgresV1ConversationImporter(dataSource());

        V1ConversationImportReport preview = importer.preview(input.plan());
        assertTrue(preview.readyToApply());
        assertEquals(2, preview.insertableConversations());
        assertEquals(4, preview.insertableMemberships());

        V1ConversationImportReport applied = importer.apply(input);
        assertTrue(applied.applied());
        assertTrue(applied.reconciled());
        assertEquals(2, applied.insertableConversations());
        assertEquals(4, applied.insertableMemberships());
        assertEquals(2, count("SELECT count(*) FROM chat.legacy_v1_conversation_map "
                + "WHERE (legacy_kind = 'ROOM' AND legacy_conversation_id = 10) "
                + "OR (legacy_kind = 'FRIENDSHIP' AND legacy_conversation_id = 20)"));
        assertEquals(2, count("SELECT count(*) FROM chat.conversation c "
                + "JOIN chat.legacy_v1_conversation_map m ON m.conversation_id = c.id "
                + "WHERE (m.legacy_kind = 'ROOM' AND m.legacy_conversation_id = 10) "
                + "OR (m.legacy_kind = 'FRIENDSHIP' AND m.legacy_conversation_id = 20)"));
        assertEquals(4, count("SELECT count(*) FROM chat.conversation_member cm "
                + "JOIN chat.legacy_v1_conversation_map m "
                + "ON m.conversation_id = cm.conversation_id "
                + "WHERE (m.legacy_kind = 'ROOM' AND m.legacy_conversation_id = 10) "
                + "OR (m.legacy_kind = 'FRIENDSHIP' AND m.legacy_conversation_id = 20)"));
        assertEquals(1, count("SELECT count(*) FROM chat.direct_conversation d "
                + "JOIN chat.legacy_v1_conversation_map m "
                + "ON m.conversation_id = d.conversation_id "
                + "WHERE m.legacy_kind = 'FRIENDSHIP' AND m.legacy_conversation_id = 20"));
        assertEquals(1, count("SELECT count(*) FROM chat.conversation_import_run"));

        V1ConversationImportReport rerun = importer.apply(input);
        assertEquals(0, rerun.insertableConversations());
        assertEquals(2, rerun.alreadyImportedConversations());
        assertEquals(0, rerun.insertableMemberships());
        assertEquals(4, rerun.alreadyImportedMemberships());
        assertEquals(2, count("SELECT count(*) FROM chat.conversation_import_run"));

        try (Connection connection = connect()) {
            execute(connection,
                    "UPDATE chat.conversation_member SET role = 'ADMIN' "
                            + "WHERE conversation_id = ? AND account_id = ?",
                    V1ConversationImportPlanner.deterministicRoomId(10),
                    V1IdentityImportPlanner.deterministicUserId(1));
        }
        V1ConversationImportReport conflict = importer.preview(input.plan());
        assertFalse(conflict.readyToApply());
        assertTrue(conflict.issues().stream().anyMatch(
                issue -> "TARGET_MEMBERSHIP_CONFLICT".equals(issue.code())));
        assertThrows(V1ConversationImportException.class, () -> importer.apply(input));
        assertEquals(2, count("SELECT count(*) FROM chat.conversation_import_run"));

        try (Connection connection = connect()) {
            execute(connection,
                    "UPDATE chat.conversation_member SET role = 'OWNER' "
                            + "WHERE conversation_id = ? AND account_id = ?",
                    V1ConversationImportPlanner.deterministicRoomId(10),
                    V1IdentityImportPlanner.deterministicUserId(1));
            execute(connection,
                    "UPDATE chat.legacy_v1_conversation_map "
                            + "SET legacy_conversation_id = 999 "
                            + "WHERE legacy_kind = 'ROOM' AND legacy_conversation_id = 10");
        }
        V1ConversationImportReport mappingConflict = importer.preview(input.plan());
        assertTrue(mappingConflict.issues().stream().anyMatch(
                issue -> "TARGET_CONVERSATION_MAPPING_CONFLICT".equals(issue.code())));
        assertThrows(V1ConversationImportException.class, () -> importer.apply(input));
        assertEquals(2, count("SELECT count(*) FROM chat.conversation_import_run"));
    }

    @Test
    @Order(9)
    void previewsV1MessageTargetsWithoutWritesAndRejectsLegacyDeviceConflict()
            throws Exception {
        requireDatabase();
        truncateApplicationData();
        UUID account = UUID.randomUUID();
        UUID conversation = UUID.randomUUID();
        UUID device = UUID.randomUUID();
        UUID message = UUID.randomUUID();
        try (Connection connection = connect()) {
            execute(connection,
                    "INSERT INTO chat.account(id, username_key, display_name, password_hash) "
                            + "VALUES (?, 'message-import-user', 'Import User', "
                            + "'$argon2id$v=19$m=65536,t=2,p=1$test$fixture')",
                    account);
            execute(connection,
                    "INSERT INTO chat.conversation(id, kind, title) VALUES (?, 'GROUP', 'Room')",
                    conversation);
            execute(connection,
                    "INSERT INTO chat.conversation_member(conversation_id, account_id) "
                            + "VALUES (?, ?)",
                    conversation, account);
            execute(connection,
                    "INSERT INTO chat.legacy_v1_conversation_map("
                            + "legacy_kind, legacy_conversation_id, conversation_id) "
                            + "VALUES ('ROOM', 77, ?)",
                    conversation);
        }
        V1MessageTargetImportPlan plan = new V1MessageTargetImportPlan(
                "a".repeat(64),
                "b".repeat(64),
                List.of(new PlannedV1LegacyDevice(account, device, "v1-history-import")),
                List.of(new PlannedV1HistoricalMessage(
                        LegacyV1ConversationKind.ROOM, 77, 501, message, conversation, 1,
                        null, account, device, "v1-import-room-501", 1, "hello",
                        false, true, Instant.parse("2026-01-02T03:04:05Z"))),
                List.of(),
                List.of(new PlannedV1ConversationCursor(
                        LegacyV1ConversationKind.ROOM, 77, conversation, 1, 2)),
                List.of(new PlannedV1MemberReadCursor(conversation, account, 501, 1)));
        PostgresV1MessageImporter importer = new PostgresV1MessageImporter(dataSource());

        V1MessageImportReport preview = importer.preview(plan);

        assertTrue(preview.readyToApply(), preview.issues().toString());
        assertEquals(1, preview.insertableMessages());
        assertEquals(1, preview.insertableEntries());
        assertEquals(1, preview.insertableLegacyDevices());
        assertEquals(1, preview.readCursorsToUpdate());
        assertEquals(0, count("SELECT count(*) FROM chat.message"));
        assertEquals(0, conversationEntryCount(conversation));

        try (Connection connection = connect()) {
            execute(connection,
                    "INSERT INTO chat.device(id, account_id, client_device_id, platform) "
                            + "VALUES (?, ?, 'v1-history-import', 'WEB')",
                    device, account);
        }
        V1MessageImportReport conflict = importer.preview(plan);
        assertFalse(conflict.readyToApply());
        assertTrue(conflict.issues().stream().anyMatch(
                issue -> "TARGET_LEGACY_DEVICE_CONFLICT".equals(issue.code())));
    }

    @Test
    @Order(10)
    void appliesV1MessagesAtomicallyReconcilesAndRerunsIdempotently() throws Exception {
        requireDatabase();
        truncateApplicationData();
        Path source = temporary.resolve("message-source.db");
        Path backup = temporary.resolve("message-backup.db");
        createMessageImportSource(source);
        Files.copy(source, backup);
        VerifiedV1IdentityBackup proof = new VerifiedV1IdentityBackup(
                "0".repeat(64),
                HexFormat.of().formatHex(sha256(Files.readAllBytes(backup))),
                1,
                Files.size(backup),
                Instant.parse("2026-08-12T12:00:00Z"));
        var state = new V1MessageStateImportInputVerifier().verify(source, backup, proof);
        var payload = new V1MessagePayloadImportInputVerifier().verify(source, backup, proof);
        var bundle = new V1MessageImportBundleVerifier().combine(state, payload);
        V1MessageTargetImportPlan plan = new V1MessageTargetImportPlanner().plan(bundle);
        UUID account = V1IdentityImportPlanner.deterministicUserId(1);
        UUID conversation = V1ConversationImportPlanner.deterministicRoomId(77);
        try (Connection connection = connect()) {
            execute(connection,
                    "INSERT INTO chat.account(id, username_key, display_name, password_hash) "
                            + "VALUES (?, 'message-apply-user', 'Apply User', "
                            + "'$argon2id$v=19$m=65536,t=2,p=1$test$fixture')",
                    account);
            execute(connection,
                    "INSERT INTO chat.conversation(id, kind, title, created_at, updated_at) "
                            + "VALUES (?, 'GROUP', 'Room', ?, ?)",
                    conversation,
                    OffsetDateTime.parse("2026-01-02T03:04:05Z"),
                    OffsetDateTime.parse("2026-01-02T03:04:05Z"));
            execute(connection,
                    "INSERT INTO chat.conversation_member("
                            + "conversation_id, account_id, role, joined_at) "
                            + "VALUES (?, ?, 'OWNER', ?)",
                    conversation, account, OffsetDateTime.parse("2026-01-02T03:04:05Z"));
            execute(connection,
                    "INSERT INTO chat.legacy_v1_conversation_map("
                            + "legacy_kind, legacy_conversation_id, conversation_id) "
                            + "VALUES ('ROOM', 77, ?)",
                    conversation);
        }
        PostgresV1MessageImporter importer = new PostgresV1MessageImporter(dataSource());

        V1MessageImportReport applied = importer.apply(bundle);

        assertTrue(applied.applied());
        assertTrue(applied.reconciled());
        assertEquals(2, applied.insertableMessages());
        assertEquals(4, applied.insertableEntries());
        assertEquals(1, applied.insertableLegacyDevices());
        assertEquals(2, count("SELECT count(*) FROM chat.message"));
        assertEquals(4, allConversationEntryCount(conversation));
        assertEquals(1, count("SELECT count(*) FROM chat.message_recall_event"));
        assertEquals(1, count("SELECT count(*) FROM chat.messages_deleted_event"));
        assertEquals(1, count("SELECT count(*) FROM chat.legacy_v1_deletion_event_map"));
        assertEquals(2, count("SELECT count(*) FROM chat.legacy_v1_message_map"));
        assertEquals(1, count("SELECT count(*) FROM chat.message_import_run"));
        assertEquals(5, count("SELECT next_sequence FROM chat.conversation WHERE id = '"
                + conversation + "'"));

        ConversationEntryHistoryResult.Page mixed =
                (ConversationEntryHistoryResult.Page) new PostgresMessageAdapter(dataSource())
                        .readEntriesAfter(new MessageHistoryQuery(
                                conversation, account, 0, 10));
        assertEquals(List.of(1L, 2L, 3L, 4L), mixed.entries().stream()
                .map(ConversationHistoryEntry::conversationSequence).toList());
        assertTrue(mixed.entries().get(0) instanceof ConversationHistoryEntry.Message);
        assertTrue(mixed.entries().get(1) instanceof ConversationHistoryEntry.Message);
        ConversationHistoryEntry.Recall recall =
                (ConversationHistoryEntry.Recall) mixed.entries().get(2);
        assertTrue(recall.occurredAt().isEmpty());
        ConversationHistoryEntry.Deletion deletion =
                (ConversationHistoryEntry.Deletion) mixed.entries().get(3);
        assertEquals(List.of(V1MessagePayloadImportPlanner.deterministicMessageId(
                LegacyV1ConversationKind.ROOM, 100)), deletion.messageIds());
        assertEquals("Apply User", deletion.operatorNameSnapshot());
        assertEquals(4, mixed.nextSequence());
        assertEquals(4, mixed.latestSequence());
        assertFalse(mixed.hasMore());

        V1MessageImportReport rerun = importer.apply(bundle);
        assertEquals(0, rerun.insertableMessages());
        assertEquals(2, rerun.alreadyImportedMessages());
        assertEquals(0, rerun.insertableEntries());
        assertEquals(4, rerun.alreadyImportedEntries());
        assertEquals(2, count("SELECT count(*) FROM chat.message_import_run"));

        try (Connection sourceConnection = DriverManager.getConnection(
                "jdbc:sqlite:" + source.toAbsolutePath())) {
            execute(sourceConnection,
                    "UPDATE messages SET content = 'source drift' WHERE id = 100");
        }
        assertThrows(V1MessagePayloadSourceException.class, () -> importer.apply(bundle));
        assertEquals(2, count("SELECT count(*) FROM chat.message_import_run"));

        try (Connection connection = connect()) {
            execute(connection,
                    "UPDATE chat.message SET payload = convert_to('conflict', 'UTF8') "
                            + "WHERE conversation_id = ? AND conversation_sequence = 1",
                    conversation);
        }
        assertFalse(importer.preview(plan).readyToApply());
        assertThrows(V1MessageImportException.class, () -> importer.apply(bundle));
        assertEquals(2, count("SELECT count(*) FROM chat.message_import_run"));
    }

    @Test
    @Order(3)
    void previewsAppliesReconcilesAndAuditsV1IdentityImport() throws Exception {
        requireDatabase();
        truncateApplicationData();
        Path source = temporary.resolve("v1-source.db");
        Path backup = temporary.resolve("v1-backup.db");
        createIdentitySource(source);
        Instant backupTime = Instant.parse("2026-08-11T12:00:00Z");
        VerifiedV1IdentityBackup proof = new V1SqliteIdentityBackup(
                Clock.fixed(backupTime, ZoneOffset.UTC)).createVerified(source, backup);
        VerifiedV1IdentityImportInput input = new V1IdentityImportInputVerifier()
                .verify(source, backup, proof);
        PostgresV1IdentityImporter importer = new PostgresV1IdentityImporter(dataSource());

        V1IdentityImportReport preview = importer.preview(input.plan());
        assertTrue(preview.readyToApply());
        assertEquals(2, preview.insertableRows());
        assertEquals(0, preview.alreadyImportedRows());
        assertEquals(0, accountCount());

        insertUnexpectedTargetAccount();
        V1IdentityImportReport unexpected = importer.preview(input.plan());
        assertFalse(unexpected.readyToApply());
        assertEquals(1, unexpected.unexpectedTargetRows());
        assertThrows(V1IdentityImportException.class, () -> importer.apply(input));
        assertEquals(1, accountCount());
        assertEquals(0, importRunCount());
        truncateApplicationData();

        V1IdentityImportReport applied = importer.apply(input);
        assertTrue(applied.applied());
        assertTrue(applied.reconciled());
        assertEquals(2, applied.insertedRows());
        assertEquals(2, accountCount());
        assertEquals(2, legacyMappingCount());
        assertEquals(
                input.plan().accounts().getFirst().accountId(), mappedAccountId(1));
        PostgresLegacyV1AccountProjection legacyProjection =
                new PostgresLegacyV1AccountProjection(dataSource());
        LegacyV1AccountIdentity byUsername = legacyProjection
                .findByPresentedUsername("alice-v1").orElseThrow();
        assertEquals(1, byUsername.legacyUserId());
        assertEquals(input.plan().accounts().getFirst().accountId(), byUsername.accountId());
        assertEquals(byUsername, legacyProjection
                .findByAccountId(byUsername.accountId()).orElseThrow());
        assertTrue(legacyProjection.findByPresentedUsername("Alice-v1").isEmpty());
        disableAccount(byUsername.accountId());
        assertTrue(legacyProjection.findByPresentedUsername("alice-v1").isEmpty());
        assertTrue(legacyProjection.findByAccountId(byUsername.accountId()).isEmpty());
        enableAccount(byUsername.accountId());
        assertEquals(1, importRunCount());
        assertEquals(proof.backupFileSha256(), storedBackupHash(applied.importRunId()));

        deleteLegacyMapping(1);
        V1IdentityImportReport repairPreview = importer.preview(input.plan());
        assertTrue(repairPreview.readyToApply());
        assertEquals(0, repairPreview.insertableRows());
        assertEquals(2, repairPreview.alreadyImportedRows());
        V1IdentityImportReport repaired = importer.apply(input);
        assertEquals(0, repaired.insertedRows());
        assertEquals(2, legacyMappingCount());
        assertEquals(2, importRunCount());

        V1IdentityImportReport rerun = importer.apply(input);
        assertEquals(0, rerun.insertedRows());
        assertEquals(2, rerun.alreadyImportedRows());
        assertEquals(3, importRunCount());
        assertEquals(2, accountCount());
        assertEquals(2, legacyMappingCount());

        changeLegacyMapping(1, 99);
        V1IdentityImportReport mappingConflict = importer.preview(input.plan());
        assertFalse(mappingConflict.readyToApply());
        assertEquals("TARGET_LEGACY_MAPPING_CONFLICT",
                mappingConflict.issues().getFirst().code());
        assertThrows(V1IdentityImportException.class, () -> importer.apply(input));
        changeLegacyMapping(99, 1);

        deleteOneImportedAccountAndCorruptAnother(input);
        V1IdentityImportReport blocked = importer.preview(input.plan());
        assertFalse(blocked.readyToApply());
        assertEquals(1, blocked.insertableRows());
        assertEquals("TARGET_ACCOUNT_CONFLICT", blocked.issues().getFirst().code());
        assertThrows(V1IdentityImportException.class, () -> importer.apply(input));
        assertEquals(1, accountCount());
        assertEquals(3, importRunCount());

        try (Connection sqlite = DriverManager.getConnection(
                "jdbc:sqlite:" + source.toAbsolutePath());
                PreparedStatement statement = sqlite.prepareStatement(
                        "INSERT INTO users VALUES (?, ?, ?, ?, ?, ?)")) {
            statement.setLong(1, 3);
            statement.setString(2, "late-user");
            statement.setString(3, "Late User");
            statement.setString(4, "a".repeat(64));
            statement.setString(5, "late-salt");
            statement.setString(6, "2026-01-02 03:04:07");
            statement.executeUpdate();
        }
        assertThrows(V1IdentitySourceException.class,
                () -> new V1IdentityImportInputVerifier().verify(source, backup, proof));
    }

    @Test
    @Order(2)
    void looksUpExactV1UsernameAndIssuesOnlyHashedRestartableSession() throws Exception {
        requireDatabase();
        PGSimpleDataSource dataSource = new PGSimpleDataSource();
        dataSource.setUrl(URL);
        dataSource.setUser(USER);
        dataSource.setPassword(PASSWORD);
        PostgresIdentityAdapter adapter = new PostgresIdentityAdapter(dataSource);

        AccountCredential account = adapter.findByPresentedUsername("alice").orElseThrow();
        assertEquals("Alice", account.displayName());
        assertTrue(account.credential() instanceof StoredCredential.Argon2id);
        assertTrue(account.enabled());
        assertTrue(adapter.findByPresentedUsername("Alice").isEmpty());
        insertLegacyAccount();
        AccountCredential legacy = adapter.findByPresentedUsername("legacy-user").orElseThrow();
        assertEquals(
                new StoredCredential.LegacySha256("a".repeat(64), "legacy-salt-1234"),
                legacy.credential());
        StoredCredential.Argon2id replacement = new StoredCredential.Argon2id(
                "$argon2id$v=19$m=65536,t=2,p=1$test$replacement");
        assertTrue(adapter.replace(legacy.accountId(), legacy.credential(), replacement));
        assertEquals(replacement,
                adapter.findByPresentedUsername("legacy-user").orElseThrow().credential());
        assertFalse(adapter.replace(legacy.accountId(), legacy.credential(), replacement));
        assertCredentialShapeConstraint();

        ClientDescriptor client = new ClientDescriptor(
                "browser-2", ClientPlatform.WEB, "0.1.0");
        Instant now = Instant.parse("2026-08-11T12:00:00Z");
        IssuedSession first = adapter.issue(account, client, now).orElseThrow();
        try (first) {
            byte[] rawToken = first.resumeToken().withCopy(byte[]::clone);
            byte[] expectedHash = sha256(rawToken);
            byte[] storedHash = sessionHash(first.sessionId());
            byte[] rotatedToken = null;
            try {
                assertTrue(Arrays.equals(expectedHash, storedHash));
                assertFalse(Arrays.equals(rawToken, storedHash));
                assertFalse(Arrays.equals(new byte[32], storedHash));
                assertEquals("WEB", devicePlatform(first.deviceId()));

                try (SecretBytes proof = SecretBytes.copyOf(rawToken);
                        IssuedSession rotated = adapter.resumeAndRotate(
                                first.sessionId(), proof, client, now.plusSeconds(30))
                                .orElseThrow()) {
                    assertEquals(first.accountId(), rotated.accountId());
                    assertEquals(first.deviceId(), rotated.deviceId());
                    assertEquals(first.sessionId(), rotated.sessionId());
                    rotatedToken = rotated.resumeToken().withCopy(byte[]::clone);
                    assertFalse(Arrays.equals(rawToken, rotatedToken));
                    assertFalse(Arrays.equals(storedHash, sessionHash(first.sessionId())));
                    assertTrue(Arrays.equals(
                            sha256(rotatedToken), sessionHash(first.sessionId())));
                }
                try (SecretBytes replay = SecretBytes.copyOf(rawToken)) {
                    assertTrue(adapter.resumeAndRotate(
                            first.sessionId(), replay, client, now.plusSeconds(31)).isEmpty());
                }
                ClientDescriptor wrongDevice = new ClientDescriptor(
                        "other-browser", ClientPlatform.WEB, "0.1.0");
                try (SecretBytes proof = SecretBytes.copyOf(rotatedToken)) {
                    assertTrue(adapter.resumeAndRotate(
                            first.sessionId(), proof, wrongDevice, now.plusSeconds(31)).isEmpty());
                }
                try (SecretBytes proof = SecretBytes.copyOf(rotatedToken)) {
                    assertTrue(adapter.resumeAndRotate(
                            first.sessionId(),
                            proof,
                            client,
                            now.plus(PostgresIdentityAdapter.DEFAULT_SESSION_LIFETIME)
                                    .plusSeconds(31))
                            .isEmpty());
                }

                IssuedSession restarted = adapter.issue(
                        account, client, now.plusSeconds(60)).orElseThrow();
                try (restarted) {
                    assertEquals(first.deviceId(), restarted.deviceId());
                    assertNotEquals(first.sessionId(), restarted.sessionId());
                    assertFalse(Arrays.equals(
                            storedHash,
                            sessionHash(restarted.sessionId())));
                }

                IssuedSession concurrentBase = adapter.issue(
                        account, client, now.plusSeconds(70)).orElseThrow();
                try (concurrentBase) {
                    byte[] concurrentToken = concurrentBase.resumeToken().withCopy(byte[]::clone);
                    try {
                        List<Optional<IssuedSession>> outcomes = raceResume(
                                adapter,
                                concurrentBase.sessionId(),
                                concurrentToken,
                                client,
                                now.plusSeconds(71));
                        try {
                            assertEquals(
                                    1, outcomes.stream().filter(Optional::isPresent).count());
                        } finally {
                            outcomes.stream()
                                    .flatMap(Optional::stream)
                                    .forEach(IssuedSession::close);
                        }
                    } finally {
                        Arrays.fill(concurrentToken, (byte) 0);
                    }
                }

                revokeDevice(first.deviceId());
                try (SecretBytes proof = SecretBytes.copyOf(rotatedToken)) {
                    assertTrue(adapter.resumeAndRotate(
                            first.sessionId(), proof, client, now.plusSeconds(120)).isEmpty());
                }
                assertTrue(adapter.issue(account, client, now.plusSeconds(120)).isEmpty());
                disableAccount(account.accountId());
                ClientDescriptor otherClient = new ClientDescriptor(
                        "browser-3", ClientPlatform.WEB, "0.1.0");
                assertTrue(adapter.issue(account, otherClient, now.plusSeconds(180)).isEmpty());
            } finally {
                Arrays.fill(rawToken, (byte) 0);
                Arrays.fill(expectedHash, (byte) 0);
                if (rotatedToken != null) {
                    Arrays.fill(rotatedToken, (byte) 0);
                }
            }
        }
    }

    private static void proveSequenceAndIdempotencyConstraints(Connection connection)
            throws SQLException {
        UUID account = UUID.randomUUID();
        UUID device = UUID.randomUUID();
        UUID conversation = UUID.randomUUID();
        UUID message = UUID.randomUUID();
        try (PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO chat.account(id, username_key, display_name, password_hash) "
                        + "VALUES (?, 'alice', 'Alice', "
                        + "'$argon2id$v=19$m=65536,t=2,p=1$test$fixture')")) {
            statement.setObject(1, account);
            statement.executeUpdate();
        }
        try (PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO chat.device(id, account_id, client_device_id, platform) "
                        + "VALUES (?, ?, 'browser-1', 'WEB')")) {
            statement.setObject(1, device);
            statement.setObject(2, account);
            statement.executeUpdate();
        }
        try (PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO chat.conversation(id, kind) VALUES (?, 'DIRECT')")) {
            statement.setObject(1, conversation);
            statement.executeUpdate();
        }
        try (PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO chat.conversation_member(conversation_id, account_id) VALUES (?, ?)")) {
            statement.setObject(1, conversation);
            statement.setObject(2, account);
            statement.executeUpdate();
        }

        long sequence;
        try (PreparedStatement statement = connection.prepareStatement(
                "UPDATE chat.conversation SET next_sequence = next_sequence + 1, "
                        + "updated_at = transaction_timestamp() WHERE id = ? "
                        + "RETURNING next_sequence - 1")) {
            statement.setObject(1, conversation);
            try (ResultSet result = statement.executeQuery()) {
                assertTrue(result.next());
                sequence = result.getLong(1);
            }
        }
        insertMessage(connection, message, conversation, sequence, account, device, "client-1");
        SQLException duplicateClientId = assertThrows(SQLException.class,
                () -> insertMessage(connection, UUID.randomUUID(), conversation, sequence + 1,
                        account, device, "client-1"));
        assertEquals("23505", duplicateClientId.getSQLState());
        SQLException duplicateSequence = assertThrows(SQLException.class,
                () -> insertMessage(connection, UUID.randomUUID(), conversation, sequence,
                        account, device, "client-2"));
        assertEquals("23505", duplicateSequence.getSQLState());
    }

    private static void proveAttachmentRegistryConstraints(Connection connection)
            throws SQLException {
        UUID account = UUID.randomUUID();
        UUID device = UUID.randomUUID();
        UUID conversation = UUID.randomUUID();
        execute(connection,
                "INSERT INTO chat.account(id, username_key, display_name, password_hash) "
                        + "VALUES (?, 'attachment-owner', 'Attachment Owner', "
                        + "'$argon2id$v=19$m=65536,t=2,p=1$test$fixture')",
                account);
        execute(connection,
                "INSERT INTO chat.device(id, account_id, client_device_id, platform) "
                        + "VALUES (?, ?, 'attachment-device', 'WEB')",
                device, account);
        execute(connection,
                "INSERT INTO chat.conversation(id, kind) VALUES (?, 'GROUP')",
                conversation);
        execute(connection,
                "INSERT INTO chat.conversation_member(conversation_id, account_id) "
                        + "VALUES (?, ?)",
                conversation, account);
        UUID attachment = UUID.randomUUID();
        execute(connection,
                "INSERT INTO chat.attachment(id, conversation_id, owner_account_id, "
                        + "owner_device_id, client_attachment_id, object_key, file_name, "
                        + "media_type, byte_size, content_sha256) "
                        + "VALUES (?, ?, ?, ?, 'client-attachment-1', ?, 'report.pdf', "
                        + "'application/pdf', 1024, ?)",
                attachment, conversation, account, device,
                "attachments/" + attachment, new byte[32]);
        SQLException duplicateClient = assertThrows(SQLException.class, () -> execute(
                connection,
                "INSERT INTO chat.attachment(id, conversation_id, owner_account_id, "
                        + "owner_device_id, client_attachment_id, object_key, file_name, "
                        + "media_type, byte_size, content_sha256) "
                        + "VALUES (?, ?, ?, ?, 'client-attachment-1', ?, 'other.pdf', "
                        + "'application/pdf', 1024, ?)",
                UUID.randomUUID(), conversation, account, device,
                "attachments/" + UUID.randomUUID(), new byte[32]));
        assertEquals("23505", duplicateClient.getSQLState());
        SQLException invalidHash = assertThrows(SQLException.class, () -> execute(
                connection,
                "INSERT INTO chat.attachment(id, conversation_id, owner_account_id, "
                        + "owner_device_id, client_attachment_id, object_key, file_name, "
                        + "media_type, byte_size, content_sha256) "
                        + "VALUES (?, ?, ?, ?, 'client-attachment-2', ?, 'other.pdf', "
                        + "'application/pdf', 1024, ?)",
                UUID.randomUUID(), conversation, account, device,
                "attachments/" + UUID.randomUUID(), new byte[31]));
        assertEquals("23514", invalidHash.getSQLState());
        SQLException invalidReadyState = assertThrows(SQLException.class, () -> execute(
                connection,
                "UPDATE chat.attachment SET state = 'READY' WHERE id = ?",
                attachment));
        assertEquals("23514", invalidReadyState.getSQLState());
        execute(connection,
                "UPDATE chat.attachment SET state = 'READY', "
                        + "ready_at = transaction_timestamp() WHERE id = ?",
                attachment);
    }

    private static List<Optional<IssuedSession>> raceResume(
            PostgresIdentityAdapter adapter,
            UUID sessionId,
            byte[] token,
            ClientDescriptor client,
            Instant now) throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<Optional<IssuedSession>>> futures = java.util.stream.IntStream.range(0, 2)
                    .mapToObj(index -> executor.submit(() -> {
                        ready.countDown();
                        assertTrue(start.await(2, TimeUnit.SECONDS));
                        try (SecretBytes proof = SecretBytes.copyOf(token)) {
                            return adapter.resumeAndRotate(sessionId, proof, client, now);
                        }
                    }))
                    .toList();
            assertTrue(ready.await(2, TimeUnit.SECONDS));
            start.countDown();
            return List.of(futures.get(0).get(), futures.get(1).get());
        } finally {
            start.countDown();
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(2, TimeUnit.SECONDS));
        }
    }

    private static List<MessageSubmissionResult.Accepted> raceSubmit(
            PostgresMessageAdapter adapter,
            MessageSubmission submission) throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<MessageSubmissionResult>> futures = java.util.stream.IntStream.range(0, 2)
                    .mapToObj(index -> executor.submit(() -> {
                        ready.countDown();
                        assertTrue(start.await(2, TimeUnit.SECONDS));
                        return adapter.submit(submission);
                    }))
                    .toList();
            assertTrue(ready.await(2, TimeUnit.SECONDS));
            start.countDown();
            return List.of(
                    (MessageSubmissionResult.Accepted) futures.get(0).get(),
                    (MessageSubmissionResult.Accepted) futures.get(1).get());
        } finally {
            start.countDown();
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(2, TimeUnit.SECONDS));
        }
    }

    private static void insertMessage(
            Connection connection,
            UUID id,
            UUID conversation,
            long sequence,
            UUID account,
            UUID device,
            String clientMessageId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "WITH inserted_entry AS ("
                        + "INSERT INTO chat.conversation_entry("
                        + "conversation_id, conversation_sequence, entry_kind, occurred_at) "
                        + "VALUES (?, ?, 'MESSAGE', transaction_timestamp()) RETURNING 1) "
                        + "INSERT INTO chat.message(id, conversation_id, conversation_sequence, "
                        + "sender_account_id, sender_device_id, client_message_id, message_type, "
                        + "payload, payload_sha256) "
                        + "SELECT ?, ?, ?, ?, ?, ?, 100, ?, ? FROM inserted_entry")) {
            statement.setObject(1, conversation);
            statement.setLong(2, sequence);
            statement.setObject(3, id);
            statement.setObject(4, conversation);
            statement.setLong(5, sequence);
            statement.setObject(6, account);
            statement.setObject(7, device);
            statement.setString(8, clientMessageId);
            statement.setBytes(9, new byte[] {1});
            statement.setBytes(10, new byte[32]);
            statement.executeUpdate();
        }
    }

    private static int conversationEntryCount(UUID conversationId) throws SQLException {
        try (Connection connection = connect();
                PreparedStatement statement = connection.prepareStatement(
                        "SELECT COUNT(*) FROM chat.conversation_entry "
                                + "WHERE conversation_id = ? AND entry_kind = 'MESSAGE'")) {
            statement.setObject(1, conversationId);
            try (ResultSet result = statement.executeQuery()) {
                assertTrue(result.next());
                return result.getInt(1);
            }
        }
    }

    private static int allConversationEntryCount(UUID conversationId) throws SQLException {
        try (Connection connection = connect();
                PreparedStatement statement = connection.prepareStatement(
                        "SELECT COUNT(*) FROM chat.conversation_entry "
                                + "WHERE conversation_id = ?")) {
            statement.setObject(1, conversationId);
            try (ResultSet result = statement.executeQuery()) {
                assertTrue(result.next());
                return result.getInt(1);
            }
        }
    }

    private static void proveMessageImportAuditConstraints(Connection connection) {
        SQLException mismatch = assertThrows(SQLException.class, () -> execute(
                connection,
                "INSERT INTO chat.message_import_run("
                        + "id, state_fingerprint_sha256, payload_fingerprint_sha256, "
                        + "backup_file_sha256, source_messages, source_recalled_messages, "
                        + "source_deletion_events, source_legacy_devices, "
                        + "source_member_read_cursors, inserted_messages, "
                        + "already_imported_messages, inserted_entries, "
                        + "already_imported_entries, inserted_legacy_devices, "
                        + "already_imported_legacy_devices, updated_read_cursors, "
                        + "already_translated_read_cursors, backup_bytes, backup_created_at) "
                        + "VALUES (?, ?, ?, ?, 1, 0, 0, 1, 1, 0, 0, 1, 0, 1, 0, 1, 0, "
                        + "1024, transaction_timestamp())",
                UUID.randomUUID(), "a".repeat(64), "b".repeat(64), "c".repeat(64)));
        assertEquals("23514", mismatch.getSQLState());
    }

    private static void executeLegacyMessageMappings(
            UUID conversationId, UUID firstMessageId, UUID secondMessageId) throws SQLException {
        try (Connection connection = connect()) {
            execute(connection,
                    "INSERT INTO chat.legacy_v1_conversation_map("
                            + "legacy_kind, legacy_conversation_id, conversation_id) "
                            + "VALUES ('FRIENDSHIP', 909, ?)",
                    conversationId);
            execute(connection,
                    "INSERT INTO chat.legacy_v1_message_map(legacy_kind, legacy_message_id, "
                            + "legacy_conversation_id, conversation_id, message_id) "
                            + "VALUES ('FRIENDSHIP', 1001, 909, ?, ?)",
                    conversationId, firstMessageId);
            SQLException wrongConversation = assertThrows(SQLException.class, () -> execute(
                    connection,
                    "INSERT INTO chat.legacy_v1_message_map(legacy_kind, legacy_message_id, "
                            + "legacy_conversation_id, conversation_id, message_id) "
                            + "VALUES ('FRIENDSHIP', 1003, 910, ?, ?)",
                    conversationId, secondMessageId));
            assertEquals("23503", wrongConversation.getSQLState());
            execute(connection,
                    "INSERT INTO chat.legacy_v1_message_map(legacy_kind, legacy_message_id, "
                            + "legacy_conversation_id, conversation_id, message_id) "
                            + "VALUES ('FRIENDSHIP', 1002, 909, ?, ?)",
                    conversationId, secondMessageId);
        }
    }

    private static void seedMessageOwner(UUID account, UUID device, UUID conversation)
            throws SQLException {
        try (Connection connection = connect()) {
            try (PreparedStatement statement = connection.prepareStatement(
                    "INSERT INTO chat.account(id, username_key, display_name, password_hash) "
                            + "VALUES (?, 'message-owner', 'Message Owner', "
                            + "'$argon2id$v=19$m=65536,t=2,p=1$test$fixture')")) {
                statement.setObject(1, account);
                statement.executeUpdate();
            }
            try (PreparedStatement statement = connection.prepareStatement(
                    "INSERT INTO chat.device(id, account_id, client_device_id, platform) "
                            + "VALUES (?, ?, 'message-device', 'WEB')")) {
                statement.setObject(1, device);
                statement.setObject(2, account);
                statement.executeUpdate();
            }
            try (PreparedStatement statement = connection.prepareStatement(
                    "INSERT INTO chat.conversation(id, kind) VALUES (?, 'DIRECT')")) {
                statement.setObject(1, conversation);
                statement.executeUpdate();
            }
            try (PreparedStatement statement = connection.prepareStatement(
                    "INSERT INTO chat.conversation_member(conversation_id, account_id) "
                            + "VALUES (?, ?)")) {
                statement.setObject(1, conversation);
                statement.setObject(2, account);
                statement.executeUpdate();
            }
        }
    }

    private static void leaveConversation(UUID conversation, UUID account) throws SQLException {
        try (Connection connection = connect();
                PreparedStatement statement = connection.prepareStatement(
                        "UPDATE chat.conversation_member SET left_at = transaction_timestamp() "
                                + "WHERE conversation_id = ? AND account_id = ?")) {
            statement.setObject(1, conversation);
            statement.setObject(2, account);
            assertEquals(1, statement.executeUpdate());
        }
    }

    private static void assertMessageHistoryIndexEligible(UUID conversation) throws SQLException {
        try (Connection connection = connect()) {
            connection.setAutoCommit(false);
            try (PreparedStatement disableSequentialScan = connection.prepareStatement(
                    "SET LOCAL enable_seqscan = off")) {
                disableSequentialScan.execute();
            }
            String plan = "";
            try (PreparedStatement statement = connection.prepareStatement(
                    "EXPLAIN (FORMAT TEXT) SELECT id, conversation_sequence "
                            + "FROM chat.message WHERE conversation_id = ? "
                            + "AND conversation_sequence > ? AND deleted_at IS NULL "
                            + "ORDER BY conversation_sequence ASC LIMIT ?")) {
                statement.setObject(1, conversation);
                statement.setLong(2, 0);
                statement.setInt(3, 101);
                try (ResultSet result = statement.executeQuery()) {
                    StringBuilder output = new StringBuilder();
                    while (result.next()) {
                        output.append(result.getString(1)).append('\n');
                    }
                    plan = output.toString();
                }
            } finally {
                connection.rollback();
            }
            assertTrue(plan.contains("message_conversation_history_idx"), plan);
        }
    }

    private static Set<String> applicationTables(Connection connection) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT table_name FROM information_schema.tables "
                        + "WHERE table_schema = 'chat' AND table_name <> 'flyway_schema_history'");
                ResultSet result = statement.executeQuery()) {
            Set<String> tables = new java.util.HashSet<>();
            while (result.next()) {
                tables.add(result.getString(1));
            }
            return Set.copyOf(tables);
        }
    }

    private static Connection connect() throws SQLException {
        return DriverManager.getConnection(URL, USER, PASSWORD);
    }

    private static void execute(Connection connection, String sql, Object... values)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            for (int index = 0; index < values.length; index++) {
                statement.setObject(index + 1, values[index]);
            }
            statement.executeUpdate();
        }
    }

    private static byte[] sessionHash(UUID sessionId) throws SQLException {
        try (Connection connection = connect();
                PreparedStatement statement = connection.prepareStatement(
                        "SELECT token_sha256 FROM chat.device_session WHERE id = ?")) {
            statement.setObject(1, sessionId);
            try (ResultSet result = statement.executeQuery()) {
                assertTrue(result.next());
                return result.getBytes(1);
            }
        }
    }

    private static String devicePlatform(UUID deviceId) throws SQLException {
        try (Connection connection = connect();
                PreparedStatement statement = connection.prepareStatement(
                        "SELECT platform FROM chat.device WHERE id = ?")) {
            statement.setObject(1, deviceId);
            try (ResultSet result = statement.executeQuery()) {
                assertTrue(result.next());
                return result.getString(1);
            }
        }
    }

    private static void revokeDevice(UUID deviceId) throws SQLException {
        try (Connection connection = connect();
                PreparedStatement statement = connection.prepareStatement(
                        "UPDATE chat.device SET revoked_at = transaction_timestamp() WHERE id = ?")) {
            statement.setObject(1, deviceId);
            assertEquals(1, statement.executeUpdate());
        }
    }

    private static void disableAccount(UUID accountId) throws SQLException {
        try (Connection connection = connect();
                PreparedStatement statement = connection.prepareStatement(
                        "UPDATE chat.account SET disabled_at = transaction_timestamp() WHERE id = ?")) {
            statement.setObject(1, accountId);
            assertEquals(1, statement.executeUpdate());
        }
    }

    private static void enableAccount(UUID accountId) throws SQLException {
        try (Connection connection = connect();
                PreparedStatement statement = connection.prepareStatement(
                        "UPDATE chat.account SET disabled_at = NULL WHERE id = ?")) {
            statement.setObject(1, accountId);
            assertEquals(1, statement.executeUpdate());
        }
    }

    private static void insertLegacyAccount() throws SQLException {
        try (Connection connection = connect();
                PreparedStatement statement = connection.prepareStatement(
                        "INSERT INTO chat.account(id, username_key, display_name, password_hash, "
                                + "password_scheme, legacy_password_salt) "
                                + "VALUES (?, 'legacy-user', 'Legacy', ?, 'V1_SHA256', ?)")) {
            statement.setObject(1, UUID.randomUUID());
            statement.setString(2, "a".repeat(64));
            statement.setString(3, "legacy-salt-1234");
            assertEquals(1, statement.executeUpdate());
        }
    }

    private static void assertCredentialShapeConstraint() throws SQLException {
        try (Connection connection = connect();
                PreparedStatement statement = connection.prepareStatement(
                        "INSERT INTO chat.account(id, username_key, display_name, password_hash, "
                                + "password_scheme, legacy_password_salt) "
                                + "VALUES (?, 'invalid-legacy', 'Invalid', 'not-hex', "
                                + "'V1_SHA256', '')")) {
            statement.setObject(1, UUID.randomUUID());
            SQLException exception = assertThrows(SQLException.class, statement::executeUpdate);
            assertEquals("23514", exception.getSQLState());
        }
    }

    private static byte[] sha256(byte[] value) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(value);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private static PGSimpleDataSource dataSource() {
        PGSimpleDataSource dataSource = new PGSimpleDataSource();
        dataSource.setUrl(URL);
        dataSource.setUser(USER);
        dataSource.setPassword(PASSWORD);
        return dataSource;
    }

    private static void truncateApplicationData() throws SQLException {
        try (Connection connection = connect();
                PreparedStatement statement = connection.prepareStatement(
                        "TRUNCATE chat.account, chat.conversation, "
                                + "chat.identity_import_run, chat.conversation_import_run, "
                                + "chat.message_import_run CASCADE")) {
            statement.execute();
        }
    }

    private static void insertUnexpectedTargetAccount() throws SQLException {
        try (Connection connection = connect();
                PreparedStatement statement = connection.prepareStatement(
                        "INSERT INTO chat.account(id, username_key, display_name, password_hash) "
                                + "VALUES (?, 'unexpected', 'Unexpected', "
                                + "'$argon2id$v=19$m=65536,t=2,p=1$test$fixture')")) {
            statement.setObject(1, UUID.randomUUID());
            assertEquals(1, statement.executeUpdate());
        }
    }

    private static void createIdentitySource(Path source) throws SQLException {
        try (Connection connection = DriverManager.getConnection(
                "jdbc:sqlite:" + source.toAbsolutePath());
                java.sql.Statement statement = connection.createStatement()) {
            statement.execute("PRAGMA journal_mode = WAL");
            statement.execute("CREATE TABLE users (id INTEGER PRIMARY KEY, "
                    + "username TEXT UNIQUE NOT NULL, display_name TEXT, "
                    + "password_hash TEXT NOT NULL, salt TEXT NOT NULL, created_at TEXT)");
            statement.execute("INSERT INTO users VALUES (1, 'alice-v1', 'Alice V1', "
                    + "'$argon2id$v=19$m=65536,t=2,p=1$c2FsdA$aGFzaA', '', "
                    + "'2026-01-02 03:04:05')");
            statement.execute("INSERT INTO users VALUES (2, 'legacy-v1', 'Legacy V1', '"
                    + "a".repeat(64) + "', 'legacy-salt', '2026-01-02 03:04:06')");
        }
    }

    private static void createConversationSource(Path source) throws SQLException {
        try (Connection connection = DriverManager.getConnection(
                "jdbc:sqlite:" + source.toAbsolutePath());
                java.sql.Statement statement = connection.createStatement()) {
            statement.execute("PRAGMA journal_mode = WAL");
            statement.execute("CREATE TABLE users(id INTEGER PRIMARY KEY, username TEXT UNIQUE, "
                    + "display_name TEXT, password_hash TEXT, salt TEXT, created_at TEXT)");
            statement.execute("CREATE TABLE rooms(id INTEGER PRIMARY KEY, name TEXT, "
                    + "creator_id INTEGER, created_at TEXT)");
            statement.execute("CREATE TABLE room_members(room_id INTEGER, user_id INTEGER, "
                    + "joined_at TEXT, last_read_msg_id INTEGER, PRIMARY KEY(room_id, user_id))");
            statement.execute("CREATE TABLE room_admins(room_id INTEGER, user_id INTEGER, "
                    + "PRIMARY KEY(room_id, user_id))");
            statement.execute("CREATE TABLE friendships(id INTEGER PRIMARY KEY, user_id1 INTEGER, "
                    + "user_id2 INTEGER, created_at TEXT, user1_last_read_msg_id INTEGER, "
                    + "user2_last_read_msg_id INTEGER)");
            statement.execute("INSERT INTO users VALUES "
                    + "(1, 'conversation-a', 'Conversation A', '" + "a".repeat(64)
                    + "', 'salt-a', '2026-01-02 03:04:05'), "
                    + "(2, 'conversation-b', 'Conversation B', '" + "b".repeat(64)
                    + "', 'salt-b', '2026-01-02 03:04:06')");
            statement.execute("INSERT INTO rooms VALUES "
                    + "(10, 'Imported Room', 1, '2026-01-02 03:04:05')");
            statement.execute("INSERT INTO room_members VALUES "
                    + "(10, 1, '2026-01-02 03:04:05', 0), "
                    + "(10, 2, '2026-01-02 03:04:06', 7)");
            statement.execute("INSERT INTO room_admins VALUES (10, 2)");
            statement.execute("INSERT INTO friendships VALUES "
                    + "(20, 1, 2, '2026-01-02 03:04:07', 0, 3)");
        }
    }

    private static void createMessageImportSource(Path source) throws SQLException {
        try (Connection connection = DriverManager.getConnection(
                "jdbc:sqlite:" + source.toAbsolutePath());
                java.sql.Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE users(id INTEGER PRIMARY KEY)");
            statement.execute("CREATE TABLE rooms(id INTEGER PRIMARY KEY, name TEXT, "
                    + "creator_id INTEGER, created_at TEXT)");
            statement.execute("CREATE TABLE room_members(room_id INTEGER, user_id INTEGER, "
                    + "joined_at TEXT, last_read_msg_id INTEGER)");
            statement.execute("CREATE TABLE room_admins(room_id INTEGER, user_id INTEGER)");
            statement.execute("CREATE TABLE friendships(id INTEGER PRIMARY KEY, user_id1 INTEGER, "
                    + "user_id2 INTEGER, created_at TEXT, user1_last_read_msg_id INTEGER, "
                    + "user2_last_read_msg_id INTEGER)");
            statement.execute("CREATE TABLE messages(id INTEGER PRIMARY KEY, room_id INTEGER, "
                    + "user_id INTEGER, content TEXT, content_type TEXT, file_name TEXT, "
                    + "file_size INTEGER, file_id INTEGER, file_cleared INTEGER, "
                    + "clear_reason TEXT, thumbnail TEXT, recalled INTEGER, sequence INTEGER, "
                    + "mutation_sequence INTEGER, created_at TEXT)");
            statement.execute("CREATE TABLE room_message_sequences("
                    + "room_id INTEGER PRIMARY KEY, last_sequence INTEGER)");
            statement.execute("CREATE TABLE room_message_deletion_events("
                    + "id INTEGER PRIMARY KEY, room_id INTEGER, operator_user_id INTEGER, "
                    + "operator_name TEXT, client_operation_id TEXT, command_fingerprint TEXT, "
                    + "mode TEXT, message_ids_json TEXT, file_ids_json TEXT, cutoff_ms INTEGER, "
                    + "deleted_count INTEGER, sequence INTEGER, created_at TEXT)");
            statement.execute("CREATE TABLE friend_messages(id INTEGER PRIMARY KEY, "
                    + "friendship_id INTEGER, sender_id INTEGER, content TEXT, content_type TEXT, "
                    + "file_name TEXT, file_size INTEGER, file_id INTEGER, file_cleared INTEGER, "
                    + "clear_reason TEXT, thumbnail TEXT, recalled INTEGER, sequence INTEGER, "
                    + "mutation_sequence INTEGER, created_at TEXT)");
            statement.execute("CREATE TABLE friendship_message_sequences("
                    + "friendship_id INTEGER PRIMARY KEY, last_sequence INTEGER)");
            statement.execute("INSERT INTO users VALUES (1)");
            statement.execute("INSERT INTO rooms VALUES "
                    + "(77, 'Room', 1, '2026-01-02 03:04:05')");
            statement.execute("INSERT INTO room_members VALUES "
                    + "(77, 1, '2026-01-02 03:04:05', 101)");
            statement.execute("INSERT INTO messages VALUES "
                    + "(100, 77, 1, 'hello', 'text', '', 0, 0, 0, '', '', 0, 1, NULL, "
                    + "'2026-01-02 03:04:06'), "
                    + "(101, 77, 1, '此消息已被撤回', 'text', '', 0, 0, 0, '', '', 1, 2, 3, "
                    + "'2026-01-02 03:04:07')");
            statement.execute("INSERT INTO room_message_sequences VALUES (77, 4)");
            statement.execute("INSERT INTO room_message_deletion_events VALUES "
                    + "(900, 77, 1, 'Apply User', 'delete-900', 'd900', "
                    + "'selected', '[100]', '[]', 0, 0, 4, "
                    + "'2026-01-02 03:04:08')");
        }
    }

    private static void seedConversationImportAccounts() throws SQLException {
        try (Connection connection = connect()) {
            execute(connection,
                    "INSERT INTO chat.account(id, username_key, display_name, password_hash) "
                            + "VALUES (?, 'conversation-a', 'Conversation A', "
                            + "'$argon2id$v=19$m=65536,t=2,p=1$test$fixture'), "
                            + "(?, 'conversation-b', 'Conversation B', "
                            + "'$argon2id$v=19$m=65536,t=2,p=1$test$fixture')",
                    V1IdentityImportPlanner.deterministicUserId(1),
                    V1IdentityImportPlanner.deterministicUserId(2));
        }
    }

    private static int accountCount() throws SQLException {
        return count("SELECT count(*) FROM chat.account");
    }

    private static int importRunCount() throws SQLException {
        return count("SELECT count(*) FROM chat.identity_import_run");
    }

    private static int legacyMappingCount() throws SQLException {
        return count("SELECT count(*) FROM chat.legacy_v1_account_map");
    }

    private static UUID mappedAccountId(long legacyId) throws SQLException {
        try (Connection connection = connect();
                PreparedStatement statement = connection.prepareStatement(
                        "SELECT account_id FROM chat.legacy_v1_account_map "
                                + "WHERE legacy_user_id = ?")) {
            statement.setLong(1, legacyId);
            try (ResultSet result = statement.executeQuery()) {
                assertTrue(result.next());
                return result.getObject(1, UUID.class);
            }
        }
    }

    private static void changeLegacyMapping(long from, long to) throws SQLException {
        try (Connection connection = connect();
                PreparedStatement statement = connection.prepareStatement(
                        "UPDATE chat.legacy_v1_account_map SET legacy_user_id = ? "
                                + "WHERE legacy_user_id = ?")) {
            statement.setLong(1, to);
            statement.setLong(2, from);
            assertEquals(1, statement.executeUpdate());
        }
    }

    private static void deleteLegacyMapping(long legacyId) throws SQLException {
        try (Connection connection = connect();
                PreparedStatement statement = connection.prepareStatement(
                        "DELETE FROM chat.legacy_v1_account_map WHERE legacy_user_id = ?")) {
            statement.setLong(1, legacyId);
            assertEquals(1, statement.executeUpdate());
        }
    }

    private static void proveLegacyV1MappingConstraints() throws SQLException {
        UUID account = UUID.randomUUID();
        try (Connection connection = connect();
                PreparedStatement statement = connection.prepareStatement(
                        "INSERT INTO chat.account(id, username_key, display_name, password_hash) "
                                + "VALUES (?, 'mapping-constraint', 'Mapping Constraint', "
                                + "'$argon2id$v=19$m=65536,t=2,p=1$test$fixture')")) {
            statement.setObject(1, account);
            assertEquals(1, statement.executeUpdate());
        }
        try (Connection connection = connect();
                PreparedStatement statement = connection.prepareStatement(
                        "INSERT INTO chat.legacy_v1_account_map(legacy_user_id, account_id) "
                                + "VALUES (0, ?)")) {
            statement.setObject(1, account);
            SQLException exception = assertThrows(SQLException.class, statement::executeUpdate);
            assertEquals("23514", exception.getSQLState());
        }
        try (Connection connection = connect();
                PreparedStatement first = connection.prepareStatement(
                        "INSERT INTO chat.legacy_v1_account_map(legacy_user_id, account_id) "
                                + "VALUES (41, ?)");
                PreparedStatement duplicate = connection.prepareStatement(
                        "INSERT INTO chat.legacy_v1_account_map(legacy_user_id, account_id) "
                                + "VALUES (42, ?)")) {
            first.setObject(1, account);
            assertEquals(1, first.executeUpdate());
            duplicate.setObject(1, account);
            SQLException exception = assertThrows(SQLException.class, duplicate::executeUpdate);
            assertEquals("23505", exception.getSQLState());
        }
        try (Connection connection = connect();
                PreparedStatement statement = connection.prepareStatement(
                        "DELETE FROM chat.account WHERE id = ?")) {
            statement.setObject(1, account);
            assertEquals(1, statement.executeUpdate());
        }
        assertEquals(0, mappingCountForAccount(account));
    }

    private static int mappingCountForAccount(UUID account) throws SQLException {
        try (Connection connection = connect();
                PreparedStatement statement = connection.prepareStatement(
                        "SELECT count(*) FROM chat.legacy_v1_account_map "
                                + "WHERE account_id = ?")) {
            statement.setObject(1, account);
            try (ResultSet result = statement.executeQuery()) {
                assertTrue(result.next());
                return result.getInt(1);
            }
        }
    }

    private static void proveLegacyV1ConversationMappingConstraints() throws SQLException {
        UUID room = UUID.randomUUID();
        UUID friendship = UUID.randomUUID();
        UUID secondRoom = UUID.randomUUID();
        try (Connection connection = connect()) {
            execute(connection,
                    "INSERT INTO chat.conversation(id, kind, title) VALUES "
                            + "(?, 'GROUP', 'Mapped Room'), "
                            + "(?, 'DIRECT', NULL), "
                            + "(?, 'GROUP', 'Second Room')",
                    room, friendship, secondRoom);
            execute(connection,
                    "INSERT INTO chat.legacy_v1_conversation_map"
                            + "(legacy_kind, legacy_conversation_id, conversation_id) "
                            + "VALUES ('ROOM', 7, ?), ('FRIENDSHIP', 7, ?)",
                    room, friendship);
        }
        assertEquals(2, count("SELECT count(*) FROM chat.legacy_v1_conversation_map "
                + "WHERE legacy_conversation_id = 7"));

        assertConversationMappingRejected("ROOM", 0, secondRoom, "23514");
        assertConversationMappingRejected("UNKNOWN", 8, secondRoom, "23514");
        assertConversationMappingRejected("FRIENDSHIP", 8, secondRoom, "23503");
        assertConversationMappingRejected("ROOM", 8, room, "23505");

        try (Connection connection = connect()) {
            execute(connection, "DELETE FROM chat.conversation WHERE id = ?", room);
        }
        assertEquals(0, count("SELECT count(*) FROM chat.legacy_v1_conversation_map "
                + "WHERE legacy_kind = 'ROOM' AND legacy_conversation_id = 7"));
        assertEquals(1, count("SELECT count(*) FROM chat.legacy_v1_conversation_map "
                + "WHERE legacy_kind = 'FRIENDSHIP' AND legacy_conversation_id = 7"));
    }

    private static void assertConversationMappingRejected(
            String kind, long legacyId, UUID conversationId, String sqlState)
            throws SQLException {
        try (Connection connection = connect();
                PreparedStatement statement = connection.prepareStatement(
                        "INSERT INTO chat.legacy_v1_conversation_map"
                                + "(legacy_kind, legacy_conversation_id, conversation_id) "
                                + "VALUES (?, ?, ?)")) {
            statement.setString(1, kind);
            statement.setLong(2, legacyId);
            statement.setObject(3, conversationId);
            SQLException exception = assertThrows(SQLException.class, statement::executeUpdate);
            assertEquals(sqlState, exception.getSQLState());
        }
    }

    private static void proveConversationImportAuditConstraints() throws SQLException {
        String sql = """
                INSERT INTO chat.conversation_import_run(
                    id, source_fingerprint_sha256, backup_file_sha256,
                    source_rooms, source_friendships, source_memberships,
                    inserted_conversations, already_imported_conversations,
                    inserted_memberships, already_imported_memberships,
                    backup_bytes, backup_created_at)
                VALUES (?, ?, ?, 1, 1, 4, 1, 0, 4, 0, 128, ?)
                """;
        try (Connection connection = connect();
                PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setObject(1, UUID.randomUUID());
            statement.setString(2, "a".repeat(64));
            statement.setString(3, "b".repeat(64));
            statement.setObject(4, OffsetDateTime.parse("2026-08-12T12:00:00Z"));
            SQLException exception = assertThrows(SQLException.class, statement::executeUpdate);
            assertEquals("23514", exception.getSQLState());
        }
    }

    private static int count(String sql) throws SQLException {
        try (Connection connection = connect();
                PreparedStatement statement = connection.prepareStatement(sql);
                ResultSet result = statement.executeQuery()) {
            assertTrue(result.next());
            return result.getInt(1);
        }
    }

    private static String storedBackupHash(UUID runId) throws SQLException {
        try (Connection connection = connect();
                PreparedStatement statement = connection.prepareStatement(
                        "SELECT backup_file_sha256 FROM chat.identity_import_run WHERE id = ?")) {
            statement.setObject(1, runId);
            try (ResultSet result = statement.executeQuery()) {
                assertTrue(result.next());
                return result.getString(1);
            }
        }
    }

    private static void deleteOneImportedAccountAndCorruptAnother(
            VerifiedV1IdentityImportInput input) throws SQLException {
        try (Connection connection = connect();
                PreparedStatement delete = connection.prepareStatement(
                        "DELETE FROM chat.account WHERE id = ?");
                PreparedStatement update = connection.prepareStatement(
                        "UPDATE chat.account SET display_name = 'Changed' WHERE id = ?")) {
            delete.setObject(1, input.plan().accounts().get(1).accountId());
            assertEquals(1, delete.executeUpdate());
            update.setObject(1, input.plan().accounts().get(0).accountId());
            assertEquals(1, update.executeUpdate());
        }
    }

    private static void requireDatabase() {
        assumeTrue(URL != null && !URL.isBlank(),
                "set CHATROOM_TEST_POSTGRES_URL to run PostgreSQL migration tests");
    }
}
