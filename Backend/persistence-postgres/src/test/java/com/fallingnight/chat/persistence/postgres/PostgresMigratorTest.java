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
import com.fallingnight.chat.application.attachment.AttachmentActor;
import com.fallingnight.chat.application.attachment.AttachmentCleanupCandidate;
import com.fallingnight.chat.application.attachment.AttachmentRegistration;
import com.fallingnight.chat.application.attachment.AttachmentRegistrationResult;
import com.fallingnight.chat.application.attachment.AttachmentReadyTransition;
import com.fallingnight.chat.application.attachment.AttachmentState;
import com.fallingnight.chat.application.identity.AccountCredential;
import com.fallingnight.chat.application.identity.ClientDescriptor;
import com.fallingnight.chat.application.identity.ClientPlatform;
import com.fallingnight.chat.application.identity.IssuedSession;
import com.fallingnight.chat.application.identity.StoredCredential;
import com.fallingnight.chat.application.compatibility.v1.LegacyV1FriendDirectoryState;
import com.fallingnight.chat.application.compatibility.v1.LegacyV1FriendRequestRejectionResult;
import com.fallingnight.chat.application.compatibility.v1.LegacyV1FriendRequestAcceptanceResult;
import com.fallingnight.chat.application.compatibility.v1.LegacyV1FriendRequestCreationResult;
import com.fallingnight.chat.application.compatibility.v1.LegacyV1FriendRemovalResult;
import com.fallingnight.chat.application.compatibility.v1.LegacyV1DirectMessageCommand;
import com.fallingnight.chat.application.compatibility.v1.LegacyV1DirectMessageResult;
import com.fallingnight.chat.application.compatibility.v1.LegacyV1DirectHistoryMessage;
import com.fallingnight.chat.application.compatibility.v1.LegacyV1DirectHistoryQuery;
import com.fallingnight.chat.application.compatibility.v1.LegacyV1DirectHistoryResult;
import com.fallingnight.chat.application.compatibility.v1.LegacyV1DirectRecallCommand;
import com.fallingnight.chat.application.compatibility.v1.LegacyV1DirectRecallResult;
import com.fallingnight.chat.application.compatibility.v1.LegacyV1DirectReadCommand;
import com.fallingnight.chat.application.compatibility.v1.LegacyV1DirectReadResult;
import com.fallingnight.chat.application.compatibility.v1.LegacyV1RoomMessageCommand;
import com.fallingnight.chat.application.compatibility.v1.LegacyV1RoomMessageResult;
import com.fallingnight.chat.application.compatibility.v1.LegacyV1RoomHistoryQuery;
import com.fallingnight.chat.application.compatibility.v1.LegacyV1RoomHistoryResult;
import com.fallingnight.chat.application.compatibility.v1.LegacyV1RoomRecallCommand;
import com.fallingnight.chat.application.compatibility.v1.LegacyV1RoomRecallResult;
import com.fallingnight.chat.application.compatibility.v1.LegacyV1RoomReadCommand;
import com.fallingnight.chat.application.compatibility.v1.LegacyV1RoomReadResult;
import com.fallingnight.chat.application.compatibility.v1.LegacyV1RoomCreationIntent;
import com.fallingnight.chat.application.compatibility.v1.LegacyV1RoomCreationResult;
import com.fallingnight.chat.application.compatibility.v1.LegacyV1RoomPasswordEncoding;
import com.fallingnight.chat.application.compatibility.v1.LegacyV1RoomJoinAccess;
import com.fallingnight.chat.application.compatibility.v1.LegacyV1RoomJoinIntent;
import com.fallingnight.chat.application.compatibility.v1.LegacyV1RoomJoinResult;
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
import com.fallingnight.chat.persistence.postgres.migration.PostgresV1ContactRequestImporter;
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
import com.fallingnight.chat.persistence.postgres.migration.V1ContactRequestImportException;
import com.fallingnight.chat.persistence.postgres.migration.V1ContactRequestImportInputVerifier;
import com.fallingnight.chat.persistence.postgres.migration.V1ContactRequestImportReport;
import com.fallingnight.chat.persistence.postgres.migration.V1ContactRequestImportPlanner;
import com.fallingnight.chat.persistence.postgres.migration.V1ContactRequestSourceException;
import com.fallingnight.chat.persistence.postgres.migration.V1IdentityImportException;
import com.fallingnight.chat.persistence.postgres.migration.V1IdentityImportInputVerifier;
import com.fallingnight.chat.persistence.postgres.migration.V1IdentityImportPlanner;
import com.fallingnight.chat.persistence.postgres.migration.V1IdentityImportReport;
import com.fallingnight.chat.persistence.postgres.migration.V1IdentitySourceException;
import com.fallingnight.chat.persistence.postgres.migration.V1SqliteIdentityBackup;
import com.fallingnight.chat.persistence.postgres.migration.VerifiedV1IdentityBackup;
import com.fallingnight.chat.persistence.postgres.migration.VerifiedV1IdentityImportInput;
import com.fallingnight.chat.persistence.postgres.migration.VerifiedV1ConversationImportInput;
import com.fallingnight.chat.persistence.postgres.migration.VerifiedV1ContactRequestImportInput;
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
        assertEquals(24, first.migrate());
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
                            "attachment", "contact_request",
                            "legacy_v1_contact_request_map",
                            "contact_request_import_run", "group_join_credential",
                            "legacy_v1_room_creation", "group_admission_policy"),
                    applicationTables(connection));
            assertEquals(1, count("SELECT count(*) FROM pg_sequences "
                    + "WHERE schemaname = 'chat' "
                    + "AND sequencename = 'legacy_v1_friendship_id_seq' "
                    + "AND increment_by = -1 AND min_value = 1 "
                    + "AND max_value = 2147483647"));
            assertEquals(1, count("SELECT count(*) FROM pg_sequences "
                    + "WHERE schemaname = 'chat' "
                    + "AND sequencename = 'legacy_v1_contact_request_id_seq' "
                    + "AND increment_by = -1 AND min_value = 1 "
                    + "AND max_value = 2147483647"));
            assertEquals(1, count("SELECT count(*) FROM pg_sequences "
                    + "WHERE schemaname = 'chat' "
                    + "AND sequencename = 'legacy_v1_friend_message_id_seq' "
                    + "AND increment_by = -1 AND min_value = 1 "
                    + "AND max_value = 2147483647"));
            assertEquals(1, count("SELECT count(*) FROM pg_sequences "
                    + "WHERE schemaname = 'chat' "
                    + "AND sequencename = 'legacy_v1_room_message_id_seq' "
                    + "AND increment_by = -1 AND min_value = 1 "
                    + "AND max_value = 2147483647"));
            assertEquals(1, count("SELECT count(*) FROM pg_sequences "
                    + "WHERE schemaname = 'chat' "
                    + "AND sequencename = 'legacy_v1_room_id_seq' "
                    + "AND increment_by = -1 AND min_value = 1 "
                    + "AND max_value = 2147483647"));
            proveSequenceAndIdempotencyConstraints(connection);
            proveDirectSelfConversationConstraint(connection);
            proveMessageImportAuditConstraints(connection);
            proveAttachmentRegistryConstraints(connection);
            proveContactRequestConstraints(connection);
            proveContactRequestImportAuditConstraints(connection);
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
        assertEquals(1, count("SELECT count(*) FROM chat.group_admission_policy policy "
                + "JOIN chat.legacy_v1_conversation_map mapping "
                + "ON mapping.conversation_id = policy.conversation_id "
                + "WHERE mapping.legacy_kind = 'ROOM' "
                + "AND mapping.legacy_conversation_id = 10 AND policy.max_members = 50"));
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
    @Order(90)
    void previewsAppliesReconcilesAndAuditsV1PendingContactRequests() throws Exception {
        requireDatabase();
        truncateApplicationData();
        Path source = temporary.resolve("contact-request-source.db");
        Path backup = temporary.resolve("contact-request-backup.db");
        createContactRequestSource(source);
        VerifiedV1IdentityBackup proof = new V1SqliteIdentityBackup(
                Clock.fixed(Instant.parse("2026-08-13T12:00:00Z"), ZoneOffset.UTC))
                .createVerified(source, backup);
        VerifiedV1ContactRequestImportInput input =
                new V1ContactRequestImportInputVerifier().verify(source, backup, proof);
        seedContactRequestImportAccounts();
        PostgresV1ContactRequestImporter importer =
                new PostgresV1ContactRequestImporter(dataSource());

        V1ContactRequestImportReport preview = importer.preview(input.plan());
        assertTrue(preview.readyToApply(), preview.issues().toString());
        assertEquals(2, preview.sourceRequests());
        assertEquals(1, preview.sourcePendingRequests());
        assertEquals(1, preview.sourceTerminalRequests());
        assertEquals(1, preview.insertablePendingRequests());

        V1ContactRequestImportReport applied = importer.apply(input);
        assertTrue(applied.applied());
        assertTrue(applied.reconciled());
        assertEquals(1, applied.insertablePendingRequests());
        assertEquals(1, count("SELECT count(*) FROM chat.contact_request "
                + "WHERE state = 'PENDING'"));
        assertEquals(1, count("SELECT count(*) FROM chat.legacy_v1_contact_request_map "
                + "WHERE legacy_request_id = 10"));
        assertEquals(1, count("SELECT count(*) FROM chat.contact_request_import_run"));

        V1ContactRequestImportReport rerun = importer.apply(input);
        assertEquals(0, rerun.insertablePendingRequests());
        assertEquals(1, rerun.alreadyImportedPendingRequests());
        assertEquals(2, count("SELECT count(*) FROM chat.contact_request_import_run"));

        try (Connection connection = connect()) {
            execute(connection,
                    "UPDATE chat.contact_request SET requester_account_id = ?, "
                            + "recipient_account_id = ? WHERE id = ?",
                    V1IdentityImportPlanner.deterministicUserId(2),
                    V1IdentityImportPlanner.deterministicUserId(1),
                    V1ContactRequestImportPlanner.deterministicRequestId(10));
        }
        V1ContactRequestImportReport targetConflict = importer.preview(input.plan());
        assertTrue(targetConflict.issues().stream().anyMatch(
                issue -> "TARGET_CONTACT_REQUEST_CONFLICT".equals(issue.code())));
        assertThrows(V1ContactRequestImportException.class, () -> importer.apply(input));
        assertEquals(2, count("SELECT count(*) FROM chat.contact_request_import_run"));

        try (Connection connection = connect()) {
            execute(connection,
                    "UPDATE chat.contact_request SET requester_account_id = ?, "
                            + "recipient_account_id = ? WHERE id = ?",
                    V1IdentityImportPlanner.deterministicUserId(1),
                    V1IdentityImportPlanner.deterministicUserId(2),
                    V1ContactRequestImportPlanner.deterministicRequestId(10));
            execute(connection,
                    "UPDATE chat.legacy_v1_contact_request_map SET legacy_request_id = 999 "
                            + "WHERE legacy_request_id = 10");
        }
        V1ContactRequestImportReport mappingConflict = importer.preview(input.plan());
        assertTrue(mappingConflict.issues().stream().anyMatch(
                issue -> "TARGET_CONTACT_REQUEST_MAPPING_CONFLICT".equals(issue.code())));
        assertThrows(V1ContactRequestImportException.class, () -> importer.apply(input));
        assertEquals(2, count("SELECT count(*) FROM chat.contact_request_import_run"));

        try (Connection connection = connect()) {
            execute(connection,
                    "UPDATE chat.legacy_v1_contact_request_map SET legacy_request_id = 10 "
                            + "WHERE legacy_request_id = 999");
        }
        try (Connection connection = DriverManager.getConnection(
                "jdbc:sqlite:" + source.toAbsolutePath())) {
            execute(connection,
                    "UPDATE friend_requests SET created_at = '2026-01-02 03:05:00' "
                            + "WHERE id = 10");
        }
        assertThrows(V1ContactRequestSourceException.class, () -> importer.apply(input));
        assertEquals(2, count("SELECT count(*) FROM chat.contact_request_import_run"));
    }

    @Test
    @Order(91)
    void readsCompleteV1FriendStateAndBatchAccountMappings() throws Exception {
        requireDatabase();
        truncateApplicationData();
        UUID owner = UUID.randomUUID();
        UUID peer = UUID.randomUUID();
        UUID conversation = UUID.randomUUID();
        UUID device = UUID.randomUUID();
        UUID firstMessage = UUID.randomUUID();
        UUID secondMessage = UUID.randomUUID();
        UUID requester = UUID.randomUUID();
        try (Connection connection = connect()) {
            execute(connection,
                    "INSERT INTO chat.account(id, username_key, display_name, password_hash) "
                            + "VALUES (?, 'friend-owner', 'Owner', "
                            + "'$argon2id$v=19$m=65536,t=2,p=1$test$fixture'), "
                            + "(?, 'friend-peer', 'Peer', "
                            + "'$argon2id$v=19$m=65536,t=2,p=1$test$fixture'), "
                            + "(?, 'friend-requester', 'Requester', "
                            + "'$argon2id$v=19$m=65536,t=2,p=1$test$fixture')",
                    owner, peer, requester);
            execute(connection,
                    "INSERT INTO chat.legacy_v1_account_map(legacy_user_id, account_id) "
                            + "VALUES (1, ?), (2, ?), (3, ?)", owner, peer, requester);
            execute(connection,
                    "INSERT INTO chat.device(id, account_id, client_device_id, platform) "
                            + "VALUES (?, ?, 'friend-device', 'LEGACY')", device, peer);
            execute(connection,
                    "INSERT INTO chat.conversation(id, kind, next_sequence) "
                            + "VALUES (?, 'DIRECT', 3)", conversation);
            UUID first = owner.toString().compareTo(peer.toString()) < 0 ? owner : peer;
            UUID second = first.equals(owner) ? peer : owner;
            execute(connection,
                    "INSERT INTO chat.direct_conversation("
                            + "conversation_id, first_account_id, second_account_id) "
                            + "VALUES (?, ?, ?)", conversation, first, second);
            execute(connection,
                    "INSERT INTO chat.conversation_member("
                            + "conversation_id, account_id, last_read_sequence) "
                            + "VALUES (?, ?, 0), (?, ?, 1)",
                    conversation, owner, conversation, peer);
            execute(connection,
                    "INSERT INTO chat.legacy_v1_conversation_map("
                            + "legacy_kind, legacy_conversation_id, conversation_id) "
                            + "VALUES ('FRIENDSHIP', 50, ?)", conversation);
            insertMessage(connection, firstMessage, conversation, 1, peer, device, "friend-1");
            insertMessage(connection, secondMessage, conversation, 2, peer, device, "friend-2");
            execute(connection,
                    "INSERT INTO chat.legacy_v1_message_map(legacy_kind, legacy_message_id, "
                            + "legacy_conversation_id, conversation_id, message_id) "
                            + "VALUES ('FRIENDSHIP', 202, 50, ?, ?), "
                            + "('FRIENDSHIP', 101, 50, ?, ?)",
                    conversation, firstMessage, conversation, secondMessage);
            execute(connection,
                    "INSERT INTO chat.contact_request("
                            + "id, requester_account_id, recipient_account_id) VALUES (?, ?, ?)",
                    UUID.fromString("70000000-0000-0000-0000-000000000070"), requester, owner);
            execute(connection,
                    "INSERT INTO chat.legacy_v1_contact_request_map("
                            + "legacy_request_id, contact_request_id) VALUES (70, ?)",
                    UUID.fromString("70000000-0000-0000-0000-000000000070"));
        }

        LegacyV1FriendDirectoryState state =
                new PostgresLegacyV1FriendDirectoryAdapter(dataSource()).read(owner, 10);

        assertEquals(1, state.friends().size());
        assertEquals(peer, state.friends().getFirst().peerAccountId());
        assertEquals("friend-peer", state.friends().getFirst().username());
        assertEquals("Peer", state.friends().getFirst().displayName());
        assertEquals(2, state.friends().getFirst().unread());
        assertEquals(202, state.friends().getFirst().peerLastReadMessageId());
        try (Connection connection = connect()) {
            execute(connection,
                    "UPDATE chat.conversation_member SET last_read_sequence = 2 "
                            + "WHERE conversation_id = ? AND account_id = ?",
                    conversation, peer);
        }
        assertEquals(101, new PostgresLegacyV1FriendDirectoryAdapter(dataSource())
                .read(owner, 10).friends().getFirst().peerLastReadMessageId());
        assertEquals(1, state.pendingFriendRequests());
        assertEquals(Set.of(peer, requester), new PostgresLegacyV1AccountProjection(dataSource())
                .findByAccountIds(Set.of(peer, requester)).keySet());
        var pending = new PostgresLegacyV1PendingFriendRequestAdapter(dataSource())
                .listIncoming(owner, 10);
        assertEquals(1, pending.size());
        assertEquals(70, pending.getFirst().requestId());
        assertEquals(3, pending.getFirst().fromUserId());
        assertEquals("friend-requester", pending.getFirst().fromUsername());
        assertEquals("Requester", pending.getFirst().fromDisplayName());
        assertThrows(IllegalArgumentException.class,
                () -> new PostgresLegacyV1FriendDirectoryAdapter(dataSource()).read(owner, 0));

        PostgresLegacyV1FriendRequestDecisionAdapter decisions =
                new PostgresLegacyV1FriendRequestDecisionAdapter(dataSource());
        assertEquals(LegacyV1FriendRequestRejectionResult.Rejected.INSTANCE,
                decisions.reject(70, peer));
        assertEquals(new LegacyV1FriendRequestRejectionResult.Accepted(false),
                decisions.reject(70, owner));
        assertEquals(new LegacyV1FriendRequestRejectionResult.Accepted(true),
                decisions.reject(70, owner));
        assertEquals(1, count("SELECT count(*) FROM chat.contact_request "
                + "WHERE id = '70000000-0000-0000-0000-000000000070' "
                + "AND state = 'REJECTED' AND resolved_at IS NOT NULL"));
        assertEquals(0, new PostgresLegacyV1PendingFriendRequestAdapter(dataSource())
                .listIncoming(owner, 10).size());

        try (Connection connection = connect()) {
            execute(connection,
                    "UPDATE chat.contact_request SET state = 'PENDING', resolved_at = NULL "
                            + "WHERE id = '70000000-0000-0000-0000-000000000070'");
            execute(connection,
                    "DELETE FROM chat.legacy_v1_contact_request_map WHERE legacy_request_id = 70");
        }
        assertEquals(LegacyV1FriendRequestRejectionResult.Rejected.INSTANCE,
                decisions.reject(70, owner));
        assertThrows(IllegalStateException.class,
                () -> new PostgresLegacyV1PendingFriendRequestAdapter(dataSource())
                        .listIncoming(owner, 10));

        try (Connection connection = connect()) {
            execute(connection,
                    "INSERT INTO chat.legacy_v1_contact_request_map("
                            + "legacy_request_id, contact_request_id) VALUES (70, "
                            + "'70000000-0000-0000-0000-000000000070')");
        }
        PostgresLegacyV1FriendRequestAcceptanceAdapter acceptance =
                new PostgresLegacyV1FriendRequestAcceptanceAdapter(dataSource());
        assertEquals(LegacyV1FriendRequestAcceptanceResult.Rejected.INSTANCE,
                acceptance.accept(70, peer));
        assertEquals(new LegacyV1FriendRequestAcceptanceResult.Accepted(false, requester),
                acceptance.accept(70, owner));
        assertEquals(new LegacyV1FriendRequestAcceptanceResult.Accepted(true, requester),
                acceptance.accept(70, owner));
        assertEquals(1, count("SELECT count(*) FROM chat.contact_request "
                + "WHERE id = '70000000-0000-0000-0000-000000000070' "
                + "AND state = 'ACCEPTED' AND resolved_at IS NOT NULL"));
        assertEquals(1, count("SELECT count(*) FROM chat.direct_conversation direct "
                + "WHERE direct.first_account_id IN ('" + owner + "', '" + requester + "') "
                + "AND direct.second_account_id IN ('" + owner + "', '" + requester + "') "
                + "AND (SELECT count(*) FROM chat.conversation_member member "
                + "WHERE member.conversation_id = direct.conversation_id "
                + "AND member.account_id IN ('" + owner + "', '" + requester + "') "
                + "AND member.left_at IS NULL) = 2"));
        assertEquals(1, count("SELECT count(*) FROM chat.legacy_v1_conversation_map mapping "
                + "JOIN chat.direct_conversation direct "
                + "ON direct.conversation_id = mapping.conversation_id "
                + "WHERE mapping.legacy_kind = 'FRIENDSHIP' "
                + "AND mapping.legacy_conversation_id = 2147483647 "
                + "AND direct.first_account_id IN ('" + owner + "', '" + requester + "') "
                + "AND direct.second_account_id IN ('" + owner + "', '" + requester + "')"));
    }

    @Test
    @Order(92)
    void searchesOnlyEnabledMappedV1AccountsWithLiteralWildcards() throws Exception {
        requireDatabase();
        truncateApplicationData();
        UUID owner = UUID.randomUUID();
        UUID peer = UUID.randomUUID();
        UUID wildcard = UUID.randomUUID();
        UUID disabled = UUID.randomUUID();
        UUID nativeV2 = UUID.randomUUID();
        try (Connection connection = connect()) {
            execute(connection,
                    "INSERT INTO chat.account(id, username_key, display_name, password_hash) "
                            + "VALUES (?, 'search-owner', 'Owner', "
                            + "'$argon2id$v=19$m=65536,t=2,p=1$test$fixture'), "
                            + "(?, 'friend-peer', 'Peer User', "
                            + "'$argon2id$v=19$m=65536,t=2,p=1$test$fixture'), "
                            + "(?, 'literal%peer', 'Wildcard', "
                            + "'$argon2id$v=19$m=65536,t=2,p=1$test$fixture'), "
                            + "(?, 'disabled-peer', 'Disabled', "
                            + "'$argon2id$v=19$m=65536,t=2,p=1$test$fixture'), "
                            + "(?, 'native-peer', 'Native', "
                            + "'$argon2id$v=19$m=65536,t=2,p=1$test$fixture')",
                    owner, peer, wildcard, disabled, nativeV2);
            execute(connection,
                    "UPDATE chat.account SET disabled_at = transaction_timestamp() WHERE id = ?",
                    disabled);
            execute(connection,
                    "INSERT INTO chat.legacy_v1_account_map(legacy_user_id, account_id) "
                            + "VALUES (1, ?), (2, ?), (3, ?), (4, ?)",
                    owner, peer, wildcard, disabled);
        }

        PostgresLegacyV1UserSearchAdapter search =
                new PostgresLegacyV1UserSearchAdapter(dataSource());
        var peers = search.search(owner, "PEER", 20);
        assertEquals(List.of("friend-peer", "literal%peer"),
                peers.stream().map(entry -> entry.username()).toList());
        assertEquals(List.of(2L, 3L),
                peers.stream().map(entry -> entry.legacyUserId()).toList());
        assertEquals(List.of("literal%peer"), search.search(owner, "%", 20).stream()
                .map(entry -> entry.username()).toList());
        assertEquals(List.of(), search.search(owner, "native", 20));
        assertThrows(IllegalArgumentException.class, () -> search.search(owner, "peer", 0));
    }

    @Test
    @Order(92)
    void searchesMappedV1RoomsByExactIdOrLiteralTitle() throws Exception {
        requireDatabase();
        truncateApplicationData();
        UUID actor = UUID.randomUUID(), creator = UUID.randomUUID(), nativeOwner = UUID.randomUUID();
        UUID project = UUID.randomUUID(), numericTitle = UUID.randomUUID(), broken = UUID.randomUUID();
        try (Connection connection = connect()) {
            execute(connection,
                    "INSERT INTO chat.account(id, username_key, display_name, password_hash) "
                            + "VALUES (?, 'room-searcher', 'Searcher', "
                            + "'$argon2id$v=19$m=65536,t=2,p=1$test$fixture'), "
                            + "(?, 'room-creator', 'Creator', "
                            + "'$argon2id$v=19$m=65536,t=2,p=1$test$fixture'), "
                            + "(?, 'room-native', 'Native', "
                            + "'$argon2id$v=19$m=65536,t=2,p=1$test$fixture')",
                    actor, creator, nativeOwner);
            execute(connection,
                    "INSERT INTO chat.legacy_v1_account_map(legacy_user_id, account_id) "
                            + "VALUES (41, ?), (42, ?)", actor, creator);
            execute(connection,
                    "INSERT INTO chat.conversation(id, kind, title) VALUES "
                            + "(?, 'GROUP', 'Project %_ Alpha'), "
                            + "(?, 'GROUP', '7'), (?, 'GROUP', 'Broken Room')",
                    project, numericTitle, broken);
            execute(connection,
                    "INSERT INTO chat.conversation_member(conversation_id, account_id, role) "
                            + "VALUES (?, ?, 'OWNER'), (?, ?, 'MEMBER'), "
                            + "(?, ?, 'OWNER'), (?, ?, 'OWNER')",
                    project, creator, project, actor,
                    numericTitle, creator, broken, nativeOwner);
            execute(connection,
                    "INSERT INTO chat.conversation_member(conversation_id, account_id, role, "
                            + "left_at) VALUES (?, ?, 'MEMBER', transaction_timestamp())",
                    project, nativeOwner);
            execute(connection,
                    "INSERT INTO chat.legacy_v1_conversation_map(legacy_kind, "
                            + "legacy_conversation_id, conversation_id) VALUES "
                            + "('ROOM', 7, ?), ('ROOM', 8, ?), ('ROOM', 9, ?)",
                    project, numericTitle, broken);
        }

        PostgresLegacyV1RoomSearchAdapter search =
                new PostgresLegacyV1RoomSearchAdapter(dataSource());
        var byId = search.search(actor, "7", 20);
        assertEquals(1, byId.size()); assertEquals(7, byId.getFirst().legacyRoomId());
        assertEquals("Project %_ Alpha", byId.getFirst().roomName());
        assertEquals(42, byId.getFirst().legacyCreatorId());
        assertEquals(2, byId.getFirst().memberCount());
        assertEquals(List.of(7L), search.search(actor, "%_", 20).stream()
                .map(entry -> entry.legacyRoomId()).toList());
        assertEquals(List.of(7L), search.search(actor, "project", 20).stream()
                .map(entry -> entry.legacyRoomId()).toList());
        assertThrows(ConversationPersistenceException.class,
                () -> search.search(actor, "broken", 20));
        try (Connection connection = connect()) {
            execute(connection, "UPDATE chat.account SET disabled_at = transaction_timestamp() "
                    + "WHERE id = ?", actor);
        }
        assertThrows(ConversationPersistenceException.class,
                () -> search.search(actor, "project", 20));
        assertThrows(IllegalArgumentException.class, () -> search.search(actor, "room", 0));
    }

    @Test
    @Order(92)
    void createsProtectedV1RoomsAtomicallyAndConvergesConcurrentRetry() throws Exception {
        requireDatabase(); truncateApplicationData();
        UUID actor = UUID.randomUUID(), disabled = UUID.randomUUID(), occupied = UUID.randomUUID();
        try (Connection connection = connect()) {
            execute(connection,
                    "INSERT INTO chat.account(id, username_key, display_name, password_hash) "
                            + "VALUES (?, 'room-create-owner', 'Owner', "
                            + "'$argon2id$v=19$m=65536,t=2,p=1$test$fixture'), "
                            + "(?, 'room-create-disabled', 'Disabled', "
                            + "'$argon2id$v=19$m=65536,t=2,p=1$test$fixture')",
                    actor, disabled);
            execute(connection, "UPDATE chat.account SET disabled_at = transaction_timestamp() "
                    + "WHERE id = ?", disabled);
            execute(connection,
                    "INSERT INTO chat.legacy_v1_account_map(legacy_user_id, account_id) "
                            + "VALUES (51, ?), (52, ?)", actor, disabled);
            execute(connection,
                    "INSERT INTO chat.conversation(id, kind, title) VALUES (?, 'GROUP', 'Old')",
                    occupied);
            execute(connection,
                    "INSERT INTO chat.legacy_v1_conversation_map(legacy_kind, "
                            + "legacy_conversation_id, conversation_id) "
                            + "VALUES ('ROOM', 2147483647, ?)", occupied);
        }
        var password = new LegacyV1RoomPasswordEncoding(
                "$argon2id$v=19$m=65536,t=2,p=1$c2FsdA$Zml4dHVyZQ",
                "hmac-sha256:v1:AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA");
        LegacyV1RoomCreationIntent intent = new LegacyV1RoomCreationIntent(
                actor, "create-race", "Protected Room", Optional.of(password));
        PostgresLegacyV1RoomCreationAdapter adapter =
                new PostgresLegacyV1RoomCreationAdapter(dataSource());
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2), start = new CountDownLatch(1);
        List<LegacyV1RoomCreationResult> results;
        try {
            var task = (java.util.concurrent.Callable<LegacyV1RoomCreationResult>) () -> {
                ready.countDown(); assertTrue(start.await(5, TimeUnit.SECONDS));
                return adapter.create(intent);
            };
            Future<LegacyV1RoomCreationResult> left = executor.submit(task);
            Future<LegacyV1RoomCreationResult> right = executor.submit(task);
            assertTrue(ready.await(5, TimeUnit.SECONDS)); start.countDown();
            results = List.of(left.get(10, TimeUnit.SECONDS), right.get(10, TimeUnit.SECONDS));
        } finally { executor.shutdownNow(); }
        var created = results.stream().map(LegacyV1RoomCreationResult.Created.class::cast).toList();
        assertEquals(1, created.stream().filter(result -> !result.duplicate()).count());
        assertEquals(1, created.stream().filter(LegacyV1RoomCreationResult.Created::duplicate).count());
        assertEquals(created.get(0).conversationId(), created.get(1).conversationId());
        assertEquals(created.get(0).legacyRoomId(), created.get(1).legacyRoomId());
        assertNotEquals(2147483647L, created.get(0).legacyRoomId());
        assertEquals(1, count("SELECT count(*) FROM chat.conversation WHERE id = '"
                + created.get(0).conversationId() + "' AND kind = 'GROUP' "
                + "AND title = 'Protected Room'"));
        assertEquals(1, count("SELECT count(*) FROM chat.conversation_member WHERE conversation_id = '"
                + created.get(0).conversationId() + "' AND account_id = '" + actor
                + "' AND role = 'OWNER' AND left_at IS NULL"));
        assertEquals(1, count("SELECT count(*) FROM chat.group_join_credential WHERE conversation_id = '"
                + created.get(0).conversationId() + "' AND encoded_password LIKE '$argon2id$%'"));
        assertEquals(LegacyV1RoomCreationResult.Rejected.CLIENT_REQUEST_ID_CONFLICT,
                adapter.create(new LegacyV1RoomCreationIntent(actor, "create-race",
                        "Other Room", Optional.of(password))));
        var otherPassword = new LegacyV1RoomPasswordEncoding(password.encodedHash(),
                "hmac-sha256:v1:BBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBB");
        assertEquals(LegacyV1RoomCreationResult.Rejected.CLIENT_REQUEST_ID_CONFLICT,
                adapter.create(new LegacyV1RoomCreationIntent(actor, "create-race",
                        "Protected Room", Optional.of(otherPassword))));
        assertEquals(LegacyV1RoomCreationResult.Rejected.CREATION_DENIED,
                adapter.create(new LegacyV1RoomCreationIntent(disabled, "disabled-create",
                        "Denied", Optional.empty())));
        LegacyV1RoomCreationResult.Created open = (LegacyV1RoomCreationResult.Created)
                adapter.create(new LegacyV1RoomCreationIntent(actor, "open-create",
                        "Open Room", Optional.empty()));
        assertFalse(open.duplicate());
        assertTrue(((LegacyV1RoomCreationResult.Created) adapter.create(
                new LegacyV1RoomCreationIntent(actor, "open-create",
                        "Open Room", Optional.empty()))).duplicate());
        assertEquals(0, count("SELECT count(*) FROM chat.group_join_credential "
                + "WHERE conversation_id = '" + open.conversationId() + "'"));
        try (Connection connection = connect()) {
            SQLException invalidTag = assertThrows(SQLException.class, () -> execute(connection,
                    "UPDATE chat.legacy_v1_room_creation "
                            + "SET password_idempotency_tag = 'plain-sha256' "
                            + "WHERE conversation_id = ?", created.get(0).conversationId()));
            assertEquals("23514", invalidTag.getSQLState());
            SQLException invalidHash = assertThrows(SQLException.class, () -> execute(connection,
                    "UPDATE chat.group_join_credential SET encoded_password = 'plaintext' "
                            + "WHERE conversation_id = ?", created.get(0).conversationId()));
            assertEquals("23514", invalidHash.getSQLState());
            execute(connection, "DELETE FROM chat.group_join_credential WHERE conversation_id = ?",
                    created.get(0).conversationId());
        }
        assertThrows(ConversationPersistenceException.class, () -> adapter.create(intent));
        assertEquals(3, count("SELECT count(*) FROM chat.conversation"));
    }

    @Test
    @Order(93)
    void joinsV1RoomsAtomicallyWithSnapshotAndConcurrentCapacityEnforcement() throws Exception {
        requireDatabase(); truncateApplicationData();
        UUID owner = UUID.randomUUID(), first = UUID.randomUUID(), second = UUID.randomUUID();
        try (Connection connection = connect()) {
            execute(connection,
                    "INSERT INTO chat.account(id, username_key, display_name, password_hash) "
                            + "VALUES (?, 'join-owner', 'Owner', "
                            + "'$argon2id$v=19$m=65536,t=2,p=1$test$fixture'), "
                            + "(?, 'join-first', 'First', "
                            + "'$argon2id$v=19$m=65536,t=2,p=1$test$fixture'), "
                            + "(?, 'join-second', 'Second', "
                            + "'$argon2id$v=19$m=65536,t=2,p=1$test$fixture')",
                    owner, first, second);
            execute(connection,
                    "INSERT INTO chat.legacy_v1_account_map(legacy_user_id, account_id) "
                            + "VALUES (61, ?), (62, ?), (63, ?)", owner, first, second);
        }
        var encoding = new LegacyV1RoomPasswordEncoding(
                "$argon2id$v=19$m=65536,t=2,p=1$c2FsdA$Zml4dHVyZQ",
                "hmac-sha256:v1:CCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCC");
        var created = (LegacyV1RoomCreationResult.Created)
                new PostgresLegacyV1RoomCreationAdapter(dataSource()).create(
                        new LegacyV1RoomCreationIntent(owner, "join-room-create",
                                "Join Room", Optional.of(encoding)));
        try (Connection connection = connect()) {
            execute(connection, "UPDATE chat.group_admission_policy SET max_members = 2 "
                    + "WHERE conversation_id = ?", created.conversationId());
        }
        PostgresLegacyV1RoomJoinAdapter adapter =
                new PostgresLegacyV1RoomJoinAdapter(dataSource());
        var firstCandidate = (LegacyV1RoomJoinAccess.Candidate)
                adapter.inspect(first, created.legacyRoomId());
        assertEquals(Optional.of(new StoredCredential.Argon2id(encoding.encodedHash())),
                firstCandidate.joinCredential());

        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2), start = new CountDownLatch(1);
        List<LegacyV1RoomJoinResult> results;
        try {
            var firstIntent = new LegacyV1RoomJoinIntent(first, created.conversationId(),
                    created.legacyRoomId(), firstCandidate.joinCredential());
            var secondCandidate = (LegacyV1RoomJoinAccess.Candidate)
                    adapter.inspect(second, created.legacyRoomId());
            var secondIntent = new LegacyV1RoomJoinIntent(second, created.conversationId(),
                    created.legacyRoomId(), secondCandidate.joinCredential());
            var left = executor.submit(() -> {
                ready.countDown(); assertTrue(start.await(5, TimeUnit.SECONDS));
                return adapter.join(firstIntent);
            });
            var right = executor.submit(() -> {
                ready.countDown(); assertTrue(start.await(5, TimeUnit.SECONDS));
                return adapter.join(secondIntent);
            });
            assertTrue(ready.await(5, TimeUnit.SECONDS)); start.countDown();
            results = List.of(left.get(10, TimeUnit.SECONDS),
                    right.get(10, TimeUnit.SECONDS));
        } finally { executor.shutdownNow(); }
        assertEquals(1, results.stream().filter(
                LegacyV1RoomJoinResult.Joined.class::isInstance).count());
        assertEquals(1, results.stream().filter(
                LegacyV1RoomJoinResult.Rejected.ROOM_FULL::equals).count());
        assertEquals(2, count("SELECT count(*) FROM chat.conversation_member "
                + "WHERE conversation_id = '" + created.conversationId()
                + "' AND left_at IS NULL"));

        LegacyV1RoomJoinResult.Joined admitted = results.stream()
                .filter(LegacyV1RoomJoinResult.Joined.class::isInstance)
                .map(LegacyV1RoomJoinResult.Joined.class::cast).findFirst().orElseThrow();
        var existing = (LegacyV1RoomJoinAccess.AlreadyMember)
                adapter.inspect(admitted.actorAccountId(), created.legacyRoomId());
        assertFalse(existing.membership().newJoin());

        UUID rejectedActor = admitted.actorAccountId().equals(first) ? second : first;
        var stale = (LegacyV1RoomJoinAccess.Candidate)
                adapter.inspect(rejectedActor, created.legacyRoomId());
        try (Connection connection = connect()) {
            execute(connection, "UPDATE chat.group_join_credential SET encoded_password = "
                    + "'$argon2id$v=19$m=65536,t=2,p=1$bmV3$Zml4dHVyZQ' "
                    + "WHERE conversation_id = ?", created.conversationId());
            SQLException invalidLimit = assertThrows(SQLException.class, () -> execute(connection,
                    "UPDATE chat.group_admission_policy SET max_members = 0 "
                            + "WHERE conversation_id = ?", created.conversationId()));
            assertEquals("23514", invalidLimit.getSQLState());
        }
        assertEquals(LegacyV1RoomJoinResult.Rejected.ACCESS_CHANGED,
                adapter.join(new LegacyV1RoomJoinIntent(rejectedActor, created.conversationId(),
                        created.legacyRoomId(), stale.joinCredential())));
    }

    @Test
    @Order(94)
    void createsV1FriendRequestsWithConcurrentRetryAndReverseDetection() throws Exception {
        requireDatabase();
        truncateApplicationData();
        UUID requester = UUID.randomUUID();
        UUID recipient = UUID.randomUUID();
        try (Connection connection = connect()) {
            execute(connection,
                    "INSERT INTO chat.account(id, username_key, display_name, password_hash) "
                            + "VALUES (?, 'request-owner', 'Owner', "
                            + "'$argon2id$v=19$m=65536,t=2,p=1$test$fixture'), "
                            + "(?, 'request-peer', 'Peer', "
                            + "'$argon2id$v=19$m=65536,t=2,p=1$test$fixture')",
                    requester, recipient);
            execute(connection,
                    "INSERT INTO chat.legacy_v1_account_map(legacy_user_id, account_id) "
                            + "VALUES (1, ?), (2, ?)", requester, recipient);
        }
        PostgresLegacyV1FriendRequestCreationAdapter adapter =
                new PostgresLegacyV1FriendRequestCreationAdapter(dataSource());
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            var task = (java.util.concurrent.Callable<LegacyV1FriendRequestCreationResult>) () -> {
                ready.countDown();
                assertTrue(start.await(5, TimeUnit.SECONDS));
                return adapter.create(requester, "request-peer");
            };
            Future<LegacyV1FriendRequestCreationResult> first = executor.submit(task);
            Future<LegacyV1FriendRequestCreationResult> second = executor.submit(task);
            assertTrue(ready.await(5, TimeUnit.SECONDS));
            start.countDown();
            var results = List.of(first.get(10, TimeUnit.SECONDS),
                    second.get(10, TimeUnit.SECONDS));
            assertEquals(1, results.stream().filter(result -> result.equals(
                    new LegacyV1FriendRequestCreationResult.Accepted(false, recipient))).count());
            assertEquals(1, results.stream().filter(result -> result.equals(
                    new LegacyV1FriendRequestCreationResult.Accepted(true, recipient))).count());
        } finally {
            executor.shutdownNow();
        }
        assertEquals(LegacyV1FriendRequestCreationResult.Rejected.REVERSE_PENDING,
                adapter.create(recipient, "request-owner"));
        assertEquals(LegacyV1FriendRequestCreationResult.Rejected.SELF_REQUEST,
                adapter.create(requester, "request-owner"));
        assertEquals(LegacyV1FriendRequestCreationResult.Rejected.USER_NOT_FOUND,
                adapter.create(requester, "Request-Peer"));
        assertEquals(1, count("SELECT count(*) FROM chat.contact_request "
                + "WHERE requester_account_id = '" + requester + "' "
                + "AND recipient_account_id = '" + recipient + "' AND state = 'PENDING'"));
        assertEquals(1, count("SELECT count(*) FROM chat.legacy_v1_contact_request_map mapping "
                + "JOIN chat.contact_request request ON request.id = mapping.contact_request_id "
                + "WHERE request.requester_account_id = '" + requester + "' "
                + "AND mapping.legacy_request_id BETWEEN 1 AND 2147483647"));
    }

    @Test
    @Order(95)
    void removesV1FriendshipIdempotentlyWithoutDeletingDurableHistory() throws Exception {
        requireDatabase();
        truncateApplicationData();
        UUID actor = UUID.randomUUID();
        UUID target = UUID.randomUUID();
        UUID stranger = UUID.randomUUID();
        UUID conversation = UUID.randomUUID();
        UUID first = actor.toString().compareTo(target.toString()) < 0 ? actor : target;
        UUID second = first.equals(actor) ? target : actor;
        try (Connection connection = connect()) {
            execute(connection,
                    "INSERT INTO chat.account(id, username_key, display_name, password_hash) "
                            + "VALUES (?, 'remove-owner', 'Owner', "
                            + "'$argon2id$v=19$m=65536,t=2,p=1$test$fixture'), "
                            + "(?, 'remove-peer', 'Peer', "
                            + "'$argon2id$v=19$m=65536,t=2,p=1$test$fixture'), "
                            + "(?, 'remove-stranger', 'Stranger', "
                            + "'$argon2id$v=19$m=65536,t=2,p=1$test$fixture')",
                    actor, target, stranger);
            execute(connection,
                    "INSERT INTO chat.legacy_v1_account_map(legacy_user_id, account_id) "
                            + "VALUES (1, ?), (2, ?), (3, ?)", actor, target, stranger);
            execute(connection,
                    "INSERT INTO chat.conversation(id, kind, next_sequence) "
                            + "VALUES (?, 'DIRECT', 7)", conversation);
            execute(connection,
                    "INSERT INTO chat.direct_conversation("
                            + "conversation_id, first_account_id, second_account_id) "
                            + "VALUES (?, ?, ?)", conversation, first, second);
            execute(connection,
                    "INSERT INTO chat.conversation_member("
                            + "conversation_id, account_id, last_read_sequence) "
                            + "VALUES (?, ?, 4), (?, ?, 5)",
                    conversation, actor, conversation, target);
            execute(connection,
                    "INSERT INTO chat.legacy_v1_conversation_map("
                            + "legacy_kind, legacy_conversation_id, conversation_id) "
                            + "VALUES ('FRIENDSHIP', 88, ?)", conversation);
        }

        PostgresLegacyV1FriendRemovalAdapter adapter =
                new PostgresLegacyV1FriendRemovalAdapter(dataSource());
        assertEquals(LegacyV1FriendRemovalResult.Rejected.TARGET_NOT_FOUND,
                adapter.remove(actor, "missing"));
        assertEquals(LegacyV1FriendRemovalResult.Rejected.SELF_REMOVAL,
                adapter.remove(actor, "remove-owner"));
        assertEquals(LegacyV1FriendRemovalResult.Rejected.NOT_FRIENDS,
                adapter.remove(actor, "remove-stranger"));
        assertEquals(new LegacyV1FriendRemovalResult.Removed(
                        false, target, "remove-peer"),
                adapter.remove(actor, "remove-peer"));
        assertEquals(new LegacyV1FriendRemovalResult.Removed(
                        true, target, "remove-peer"),
                adapter.remove(actor, "remove-peer"));

        assertEquals(2, count("SELECT count(*) FROM chat.conversation_member "
                + "WHERE conversation_id = '" + conversation + "' AND left_at IS NOT NULL"));
        assertEquals(1, count("SELECT count(DISTINCT left_at) "
                + "FROM chat.conversation_member WHERE conversation_id = '"
                + conversation + "'"));
        assertEquals(1, count("SELECT count(*) FROM chat.conversation "
                + "WHERE id = '" + conversation + "' AND next_sequence = 7"));
        assertEquals(2, count("SELECT count(*) FROM chat.conversation_member "
                + "WHERE conversation_id = '" + conversation
                + "' AND last_read_sequence IN (4, 5)"));
        assertEquals(1, count("SELECT count(*) FROM chat.legacy_v1_conversation_map "
                + "WHERE legacy_kind = 'FRIENDSHIP' AND legacy_conversation_id = 88 "
                + "AND conversation_id = '" + conversation + "'"));

        try (Connection connection = connect()) {
            execute(connection,
                    "UPDATE chat.conversation_member SET left_at = NULL "
                            + "WHERE conversation_id = ? AND account_id = ?",
                    conversation, actor);
        }
        assertThrows(ConversationPersistenceException.class,
                () -> adapter.remove(actor, "remove-peer"));
        assertEquals(1, count("SELECT count(*) FROM chat.conversation_member "
                + "WHERE conversation_id = '" + conversation + "' AND left_at IS NULL"));
    }

    @Test
    @Order(95)
    void submitsMappedV1DirectMessagesIdempotently() throws Exception {
        requireDatabase();
        truncateApplicationData();
        UUID sender = UUID.randomUUID();
        UUID target = UUID.randomUUID();
        UUID outsider = UUID.randomUUID();
        UUID device = UUID.randomUUID();
        UUID outsiderDevice = UUID.randomUUID();
        UUID conversation = UUID.randomUUID();
        UUID first = sender.toString().compareTo(target.toString()) < 0 ? sender : target;
        UUID second = first.equals(sender) ? target : sender;
        try (Connection connection = connect()) {
            execute(connection,
                    "INSERT INTO chat.account(id, username_key, display_name, password_hash) "
                            + "VALUES (?, 'message-sender', 'Sender', "
                            + "'$argon2id$v=19$m=65536,t=2,p=1$test$fixture'), "
                            + "(?, 'message-target', 'Target', "
                            + "'$argon2id$v=19$m=65536,t=2,p=1$test$fixture'), "
                            + "(?, 'message-outsider', 'Outsider', "
                            + "'$argon2id$v=19$m=65536,t=2,p=1$test$fixture')",
                    sender, target, outsider);
            execute(connection,
                    "INSERT INTO chat.legacy_v1_account_map(legacy_user_id, account_id) "
                            + "VALUES (1, ?), (2, ?), (3, ?)", sender, target, outsider);
            execute(connection,
                    "INSERT INTO chat.device(id, account_id, client_device_id, platform) "
                            + "VALUES (?, ?, 'message-device', 'LEGACY'), "
                            + "(?, ?, 'outsider-device', 'LEGACY')",
                    device, sender, outsiderDevice, outsider);
            execute(connection,
                    "INSERT INTO chat.conversation(id, kind) VALUES (?, 'DIRECT')", conversation);
            execute(connection,
                    "INSERT INTO chat.direct_conversation VALUES (?, ?, ?)",
                    conversation, first, second);
            execute(connection,
                    "INSERT INTO chat.conversation_member(conversation_id, account_id) "
                            + "VALUES (?, ?), (?, ?)",
                    conversation, sender, conversation, target);
            execute(connection,
                    "INSERT INTO chat.legacy_v1_conversation_map(legacy_kind, "
                            + "legacy_conversation_id, conversation_id) "
                            + "VALUES ('FRIENDSHIP', 99, ?)", conversation);
        }

        PostgresLegacyV1DirectMessageAdapter adapter =
                new PostgresLegacyV1DirectMessageAdapter(dataSource());
        LegacyV1DirectMessageCommand command = new LegacyV1DirectMessageCommand(
                sender, device, "message-target", "client-v1-1", "hello", "text");
        LegacyV1DirectMessageResult.Accepted firstResult =
                (LegacyV1DirectMessageResult.Accepted) adapter.submit(command);
        LegacyV1DirectMessageResult.Accepted duplicate =
                (LegacyV1DirectMessageResult.Accepted) adapter.submit(command);
        assertFalse(firstResult.duplicate());
        assertTrue(duplicate.duplicate());
        assertEquals(firstResult.legacyFriendshipId(), duplicate.legacyFriendshipId());
        assertEquals(firstResult.legacyMessageId(), duplicate.legacyMessageId());
        assertEquals(firstResult.sequence(), duplicate.sequence());
        assertEquals(firstResult.acceptedAt(), duplicate.acceptedAt());
        assertEquals(target, firstResult.targetAccountId());
        assertEquals(99, firstResult.legacyFriendshipId());
        assertEquals(LegacyV1DirectMessageResult.Rejected.CLIENT_MESSAGE_ID_CONFLICT,
                adapter.submit(new LegacyV1DirectMessageCommand(sender, device,
                        "message-target", "client-v1-1", "changed", "text")));
        assertEquals(LegacyV1DirectMessageResult.Rejected.FRIENDSHIP_ACCESS_DENIED,
                adapter.submit(new LegacyV1DirectMessageCommand(outsider, outsiderDevice,
                        "message-target", "client-v1-2", "hello", "text")));
        assertEquals(1, count("SELECT count(*) FROM chat.message WHERE conversation_id = '"
                + conversation + "' AND client_message_id = 'client-v1-1'"));
        assertEquals(1, count("SELECT count(*) FROM chat.legacy_v1_message_map "
                + "WHERE legacy_kind = 'FRIENDSHIP' AND legacy_conversation_id = 99 "
                + "AND legacy_message_id = " + firstResult.legacyMessageId()));
        assertEquals(2, count("SELECT next_sequence FROM chat.conversation WHERE id = '"
                + conversation + "'"));

        try (Connection connection = connect()) {
            execute(connection, "UPDATE chat.conversation_member "
                    + "SET left_at = transaction_timestamp() WHERE conversation_id = ?",
                    conversation);
        }
        assertEquals(LegacyV1DirectMessageResult.Rejected.FRIENDSHIP_ACCESS_DENIED,
                adapter.submit(new LegacyV1DirectMessageCommand(sender, device,
                        "message-target", "client-v1-3", "after removal", "text")));
    }

    @Test
    @Order(96)
    void readsCompleteMappedV1DirectHistoryWithSequencePaging() throws Exception {
        requireDatabase();
        truncateApplicationData();
        UUID sender = UUID.randomUUID();
        UUID target = UUID.randomUUID();
        UUID outsider = UUID.randomUUID();
        UUID senderDevice = UUID.randomUUID();
        UUID targetDevice = UUID.randomUUID();
        UUID conversation = UUID.randomUUID();
        UUID firstAccount = sender.toString().compareTo(target.toString()) < 0 ? sender : target;
        UUID secondAccount = firstAccount.equals(sender) ? target : sender;
        try (Connection connection = connect()) {
            execute(connection,
                    "INSERT INTO chat.account(id, username_key, display_name, password_hash) "
                            + "VALUES (?, 'history-sender', 'History Sender', "
                            + "'$argon2id$v=19$m=65536,t=2,p=1$test$fixture'), "
                            + "(?, 'history-target', 'History Target', "
                            + "'$argon2id$v=19$m=65536,t=2,p=1$test$fixture'), "
                            + "(?, 'history-outsider', 'History Outsider', "
                            + "'$argon2id$v=19$m=65536,t=2,p=1$test$fixture')",
                    sender, target, outsider);
            execute(connection,
                    "INSERT INTO chat.legacy_v1_account_map(legacy_user_id, account_id) "
                            + "VALUES (11, ?), (12, ?), (13, ?)", sender, target, outsider);
            execute(connection,
                    "INSERT INTO chat.device(id, account_id, client_device_id, platform) "
                            + "VALUES (?, ?, 'history-sender-device', 'LEGACY'), "
                            + "(?, ?, 'history-target-device', 'LEGACY')",
                    senderDevice, sender, targetDevice, target);
            execute(connection,
                    "INSERT INTO chat.conversation(id, kind) VALUES (?, 'DIRECT')", conversation);
            execute(connection,
                    "INSERT INTO chat.direct_conversation VALUES (?, ?, ?)",
                    conversation, firstAccount, secondAccount);
            execute(connection,
                    "INSERT INTO chat.conversation_member(conversation_id, account_id) "
                            + "VALUES (?, ?), (?, ?)",
                    conversation, sender, conversation, target);
            execute(connection,
                    "INSERT INTO chat.legacy_v1_conversation_map(legacy_kind, "
                            + "legacy_conversation_id, conversation_id) "
                            + "VALUES ('FRIENDSHIP', 199, ?)", conversation);
        }

        PostgresLegacyV1DirectMessageAdapter writer =
                new PostgresLegacyV1DirectMessageAdapter(dataSource());
        LegacyV1DirectMessageResult.Accepted first =
                (LegacyV1DirectMessageResult.Accepted) writer.submit(
                        new LegacyV1DirectMessageCommand(sender, senderDevice,
                                "history-target", "history-client-1", "first", "text"));
        LegacyV1DirectMessageResult.Accepted second =
                (LegacyV1DirectMessageResult.Accepted) writer.submit(
                        new LegacyV1DirectMessageCommand(target, targetDevice,
                                "history-sender", "history-client-2", "smile", "emoji"));
        try (Connection connection = connect()) {
            UUID firstMessage;
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT id FROM chat.message WHERE conversation_id = ? "
                            + "AND client_message_id = 'history-client-1'")) {
                statement.setObject(1, conversation);
                try (ResultSet row = statement.executeQuery()) {
                    assertTrue(row.next());
                    firstMessage = row.getObject(1, UUID.class);
                }
            }
            execute(connection,
                    "UPDATE chat.conversation SET next_sequence = 4 WHERE id = ?",
                    conversation);
            execute(connection,
                    "INSERT INTO chat.conversation_entry(conversation_id, "
                            + "conversation_sequence, entry_kind, occurred_at) "
                            + "VALUES (?, 3, 'MESSAGE_RECALLED', transaction_timestamp())",
                    conversation);
            execute(connection,
                    "INSERT INTO chat.message_recall_event(conversation_id, "
                            + "conversation_sequence, message_id, actor_account_id, source) "
                            + "VALUES (?, 3, ?, ?, 'V2')",
                    conversation, firstMessage, sender);
        }

        PostgresLegacyV1DirectHistoryAdapter reader =
                new PostgresLegacyV1DirectHistoryAdapter(dataSource());
        LegacyV1DirectHistoryResult.Page latest =
                (LegacyV1DirectHistoryResult.Page) reader.read(
                        new LegacyV1DirectHistoryQuery(sender, "history-target", 1, 0, null));
        assertFalse(latest.sequenceMode());
        assertFalse(latest.hasMore());
        assertEquals(3, latest.nextSequence());
        assertEquals(List.of(second.legacyMessageId()), latest.messages().stream()
                .map(LegacyV1DirectHistoryMessage::legacyMessageId).toList());
        assertEquals("emoji", latest.messages().getFirst().contentType());
        assertEquals("History Target", latest.messages().getFirst().senderDisplayName());

        LegacyV1DirectHistoryResult.Page pageOne =
                (LegacyV1DirectHistoryResult.Page) reader.read(
                        new LegacyV1DirectHistoryQuery(sender, "history-target", 1, 0, 0L));
        assertTrue(pageOne.sequenceMode());
        assertTrue(pageOne.hasMore());
        assertEquals(2, pageOne.nextSequence());
        assertEquals(3, pageOne.lastSequence());
        assertEquals(second.legacyMessageId(), pageOne.messages().getFirst().legacyMessageId());
        assertFalse(pageOne.messages().getFirst().recalled());

        LegacyV1DirectHistoryResult.Page pageTwo =
                (LegacyV1DirectHistoryResult.Page) reader.read(
                        new LegacyV1DirectHistoryQuery(sender, "history-target", 1, 0,
                                pageOne.nextSequence()));
        assertFalse(pageTwo.hasMore());
        assertEquals(3, pageTwo.nextSequence());
        LegacyV1DirectHistoryMessage recalled = pageTwo.messages().getFirst();
        assertEquals(first.legacyMessageId(), recalled.legacyMessageId());
        assertEquals(1, recalled.sequence());
        assertEquals(3L, recalled.mutationSequence());
        assertEquals(3, recalled.syncSequence());
        assertTrue(recalled.recalled());
        assertEquals("text", recalled.contentType());
        assertEquals("first", recalled.content());
        assertEquals(LegacyV1DirectHistoryResult.Rejected.INVALID_SEQUENCE_CURSOR,
                reader.read(new LegacyV1DirectHistoryQuery(
                        sender, "history-target", 10, 0, 4L)));
        assertEquals(LegacyV1DirectHistoryResult.Rejected.FRIENDSHIP_ACCESS_DENIED,
                reader.read(new LegacyV1DirectHistoryQuery(
                        outsider, "history-target", 10, 0, null)));

        try (Connection connection = connect()) {
            execute(connection,
                    "UPDATE chat.legacy_v1_message_map SET legacy_content_type = NULL "
                            + "WHERE legacy_message_id = ?", second.legacyMessageId());
        }
        assertThrows(MessagePersistenceException.class,
                () -> reader.read(new LegacyV1DirectHistoryQuery(
                        sender, "history-target", 10, 0, null)));
        try (Connection connection = connect()) {
            execute(connection,
                    "UPDATE chat.legacy_v1_message_map SET legacy_content_type = 'emoji' "
                            + "WHERE legacy_message_id = ?", second.legacyMessageId());
            execute(connection,
                    "UPDATE chat.conversation SET next_sequence = 5 WHERE id = ?",
                    conversation);
            execute(connection,
                    "INSERT INTO chat.conversation_entry(conversation_id, "
                            + "conversation_sequence, entry_kind) "
                            + "VALUES (?, 4, 'MESSAGE_RECALLED')", conversation);
        }
        assertThrows(MessagePersistenceException.class,
                () -> reader.read(new LegacyV1DirectHistoryQuery(
                        sender, "history-target", 10, 0, 3L)));
    }

    @Test
    @Order(97)
    void recallsOwnedMappedV1DirectMessageExactlyOnce() throws Exception {
        requireDatabase();
        truncateApplicationData();
        UUID sender = UUID.randomUUID();
        UUID target = UUID.randomUUID();
        UUID outsider = UUID.randomUUID();
        UUID senderDevice = UUID.randomUUID();
        UUID conversation = UUID.randomUUID();
        UUID firstAccount = sender.toString().compareTo(target.toString()) < 0 ? sender : target;
        UUID secondAccount = firstAccount.equals(sender) ? target : sender;
        try (Connection connection = connect()) {
            execute(connection,
                    "INSERT INTO chat.account(id, username_key, display_name, password_hash) "
                            + "VALUES (?, 'recall-sender', 'Recall Sender', "
                            + "'$argon2id$v=19$m=65536,t=2,p=1$test$fixture'), "
                            + "(?, 'recall-target', 'Recall Target', "
                            + "'$argon2id$v=19$m=65536,t=2,p=1$test$fixture'), "
                            + "(?, 'recall-outsider', 'Recall Outsider', "
                            + "'$argon2id$v=19$m=65536,t=2,p=1$test$fixture')",
                    sender, target, outsider);
            execute(connection,
                    "INSERT INTO chat.legacy_v1_account_map(legacy_user_id, account_id) "
                            + "VALUES (21, ?), (22, ?), (23, ?)", sender, target, outsider);
            execute(connection,
                    "INSERT INTO chat.device(id, account_id, client_device_id, platform) "
                            + "VALUES (?, ?, 'recall-device', 'LEGACY')",
                    senderDevice, sender);
            execute(connection,
                    "INSERT INTO chat.conversation(id, kind) VALUES (?, 'DIRECT')", conversation);
            execute(connection,
                    "INSERT INTO chat.direct_conversation VALUES (?, ?, ?)",
                    conversation, firstAccount, secondAccount);
            execute(connection,
                    "INSERT INTO chat.conversation_member(conversation_id, account_id) "
                            + "VALUES (?, ?), (?, ?)",
                    conversation, sender, conversation, target);
            execute(connection,
                    "INSERT INTO chat.legacy_v1_conversation_map(legacy_kind, "
                            + "legacy_conversation_id, conversation_id) "
                            + "VALUES ('FRIENDSHIP', 299, ?)", conversation);
        }
        PostgresLegacyV1DirectMessageAdapter writer =
                new PostgresLegacyV1DirectMessageAdapter(dataSource());
        LegacyV1DirectMessageResult.Accepted first =
                (LegacyV1DirectMessageResult.Accepted) writer.submit(
                        new LegacyV1DirectMessageCommand(sender, senderDevice,
                                "recall-target", "recall-client-1", "first", "text"));
        LegacyV1DirectMessageResult.Accepted expired =
                (LegacyV1DirectMessageResult.Accepted) writer.submit(
                        new LegacyV1DirectMessageCommand(sender, senderDevice,
                                "recall-target", "recall-client-2", "old", "text"));
        try (Connection connection = connect()) {
            execute(connection,
                    "UPDATE chat.message SET accepted_at = transaction_timestamp() "
                            + "- interval '121 seconds' WHERE client_message_id = "
                            + "'recall-client-2'");
            UUID firstMessage;
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT id FROM chat.message WHERE client_message_id = 'recall-client-1'")) {
                try (ResultSet row = statement.executeQuery()) {
                    assertTrue(row.next());
                    firstMessage = row.getObject(1, UUID.class);
                }
            }
            SQLException wrongEntryKind = assertThrows(SQLException.class, () -> execute(
                    connection,
                    "INSERT INTO chat.message_recall_event(conversation_id, "
                            + "conversation_sequence, message_id, actor_account_id, source) "
                            + "VALUES (?, 1, ?, ?, 'V2')",
                    conversation, firstMessage, sender));
            assertEquals("23503", wrongEntryKind.getSQLState());
        }

        PostgresLegacyV1DirectRecallAdapter adapter =
                new PostgresLegacyV1DirectRecallAdapter(dataSource());
        LegacyV1DirectRecallCommand command =
                new LegacyV1DirectRecallCommand(sender, first.legacyMessageId());
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        List<LegacyV1DirectRecallResult> results;
        try {
            var task = (java.util.concurrent.Callable<LegacyV1DirectRecallResult>) () -> {
                ready.countDown();
                assertTrue(start.await(5, TimeUnit.SECONDS));
                return adapter.recall(command);
            };
            Future<LegacyV1DirectRecallResult> left = executor.submit(task);
            Future<LegacyV1DirectRecallResult> right = executor.submit(task);
            assertTrue(ready.await(5, TimeUnit.SECONDS));
            start.countDown();
            results = List.of(left.get(10, TimeUnit.SECONDS),
                    right.get(10, TimeUnit.SECONDS));
        } finally {
            executor.shutdownNow();
        }
        assertEquals(1, results.stream()
                .map(LegacyV1DirectRecallResult.Recalled.class::cast)
                .filter(result -> !result.duplicate()).count());
        assertEquals(1, results.stream()
                .map(LegacyV1DirectRecallResult.Recalled.class::cast)
                .filter(LegacyV1DirectRecallResult.Recalled::duplicate).count());
        LegacyV1DirectRecallResult.Recalled recalled = results.stream()
                .map(LegacyV1DirectRecallResult.Recalled.class::cast)
                .findFirst().orElseThrow();
        assertEquals(299, recalled.legacyFriendshipId());
        assertEquals(first.legacyMessageId(), recalled.legacyMessageId());
        assertEquals(3, recalled.mutationSequence());
        assertEquals(target, recalled.targetAccountId());
        assertEquals("recall-target", recalled.targetUsername());
        assertEquals(LegacyV1DirectRecallResult.Rejected.RECALL_DENIED,
                adapter.recall(new LegacyV1DirectRecallCommand(
                        outsider, first.legacyMessageId())));
        assertEquals(LegacyV1DirectRecallResult.Rejected.RECALL_DENIED,
                adapter.recall(new LegacyV1DirectRecallCommand(
                        sender, expired.legacyMessageId())));
        assertEquals(1, count("SELECT count(*) FROM chat.message_recall_event "
                + "WHERE conversation_id = '" + conversation + "'"));
        assertEquals(4, count("SELECT next_sequence FROM chat.conversation WHERE id = '"
                + conversation + "'"));

        PostgresLegacyV1DirectReadAdapter reads =
                new PostgresLegacyV1DirectReadAdapter(dataSource());
        LegacyV1DirectReadResult.Marked marked =
                (LegacyV1DirectReadResult.Marked) reads.markRead(
                        new LegacyV1DirectReadCommand(sender, 299));
        assertEquals(conversation, marked.conversationId());
        assertEquals(0, marked.previousSequence());
        assertEquals(3, marked.lastReadSequence());
        assertTrue(marked.changed());
        assertEquals(expired.legacyMessageId(), marked.legacyLastReadMessageId());
        assertEquals(target, marked.targetAccountId());
        assertEquals("recall-target", marked.targetUsername());
        LegacyV1DirectReadResult.Marked duplicate =
                (LegacyV1DirectReadResult.Marked) reads.markRead(
                        new LegacyV1DirectReadCommand(sender, 299));
        assertEquals(3, duplicate.previousSequence());
        assertEquals(3, duplicate.lastReadSequence());
        assertFalse(duplicate.changed());
        assertEquals(expired.legacyMessageId(), duplicate.legacyLastReadMessageId());
        assertEquals(LegacyV1DirectReadResult.Rejected.FRIENDSHIP_ACCESS_DENIED,
                reads.markRead(new LegacyV1DirectReadCommand(outsider, 299)));
        assertEquals(0, count("SELECT last_read_sequence FROM chat.conversation_member "
                + "WHERE conversation_id = '" + conversation + "' AND account_id = '"
                + target + "'"));

        try (Connection connection = connect()) {
            execute(connection,
                    "UPDATE chat.conversation_member SET left_at = transaction_timestamp() "
                            + "WHERE conversation_id = ?", conversation);
        }
        LegacyV1DirectRecallResult.Recalled retryAfterRemoval =
                (LegacyV1DirectRecallResult.Recalled) adapter.recall(command);
        assertTrue(retryAfterRemoval.duplicate());
        assertEquals(recalled.mutationSequence(), retryAfterRemoval.mutationSequence());
        assertEquals(recalled.occurredAt(), retryAfterRemoval.occurredAt());
    }

    @Test
    @Order(98)
    void submitsMappedV1RoomMessagesWithConcurrentRetry() throws Exception {
        requireDatabase();
        truncateApplicationData();
        UUID sender = UUID.randomUUID(), outsider = UUID.randomUUID();
        UUID senderDevice = UUID.randomUUID(), outsiderDevice = UUID.randomUUID();
        UUID room = UUID.randomUUID();
        try (Connection connection = connect()) {
            execute(connection,
                    "INSERT INTO chat.account(id, username_key, display_name, password_hash) "
                            + "VALUES (?, 'room-sender', 'Room Sender', "
                            + "'$argon2id$v=19$m=65536,t=2,p=1$test$fixture'), "
                            + "(?, 'room-outsider', 'Room Outsider', "
                            + "'$argon2id$v=19$m=65536,t=2,p=1$test$fixture')",
                    sender, outsider);
            execute(connection,
                    "INSERT INTO chat.legacy_v1_account_map(legacy_user_id, account_id) "
                            + "VALUES (31, ?), (32, ?)", sender, outsider);
            execute(connection,
                    "INSERT INTO chat.device(id, account_id, client_device_id, platform) "
                            + "VALUES (?, ?, 'room-sender-device', 'LEGACY'), "
                            + "(?, ?, 'room-outsider-device', 'LEGACY')",
                    senderDevice, sender, outsiderDevice, outsider);
            execute(connection,
                    "INSERT INTO chat.conversation(id, kind, title) "
                            + "VALUES (?, 'GROUP', 'Mapped Room')", room);
            execute(connection,
                    "INSERT INTO chat.conversation_member(conversation_id, account_id) "
                            + "VALUES (?, ?)", room, sender);
            execute(connection,
                    "INSERT INTO chat.legacy_v1_conversation_map(legacy_kind, "
                            + "legacy_conversation_id, conversation_id) "
                            + "VALUES ('ROOM', 77, ?)", room);
        }
        PostgresLegacyV1RoomMessageAdapter adapter =
                new PostgresLegacyV1RoomMessageAdapter(dataSource());
        LegacyV1RoomMessageCommand command = new LegacyV1RoomMessageCommand(
                sender, senderDevice, 77, "room-client-1", "hello room", "text");
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2), start = new CountDownLatch(1);
        List<LegacyV1RoomMessageResult> results;
        try {
            var task = (java.util.concurrent.Callable<LegacyV1RoomMessageResult>) () -> {
                ready.countDown(); assertTrue(start.await(5, TimeUnit.SECONDS));
                return adapter.submit(command);
            };
            Future<LegacyV1RoomMessageResult> left = executor.submit(task);
            Future<LegacyV1RoomMessageResult> right = executor.submit(task);
            assertTrue(ready.await(5, TimeUnit.SECONDS)); start.countDown();
            results = List.of(left.get(10, TimeUnit.SECONDS), right.get(10, TimeUnit.SECONDS));
        } finally { executor.shutdownNow(); }
        List<LegacyV1RoomMessageResult.Accepted> accepted = results.stream()
                .map(LegacyV1RoomMessageResult.Accepted.class::cast).toList();
        assertEquals(1, accepted.stream().filter(result -> !result.duplicate()).count());
        assertEquals(1, accepted.stream().filter(LegacyV1RoomMessageResult.Accepted::duplicate)
                .count());
        assertEquals(accepted.getFirst().legacyMessageId(), accepted.getLast().legacyMessageId());
        assertEquals(1, accepted.getFirst().sequence());
        assertEquals(room, accepted.getFirst().conversationId());
        assertEquals(LegacyV1RoomMessageResult.Rejected.CLIENT_MESSAGE_ID_CONFLICT,
                adapter.submit(new LegacyV1RoomMessageCommand(sender, senderDevice, 77,
                        "room-client-1", "changed", "text")));
        assertEquals(LegacyV1RoomMessageResult.Rejected.ROOM_ACCESS_DENIED,
                adapter.submit(new LegacyV1RoomMessageCommand(outsider, outsiderDevice, 77,
                        "room-client-2", "outsider", "text")));
        assertEquals(1, count("SELECT count(*) FROM chat.message WHERE conversation_id = '"
                + room + "' AND message_type = 1 AND convert_from(payload, 'UTF8') = 'hello room'"));
        assertEquals(1, count("SELECT count(*) FROM chat.legacy_v1_message_map "
                + "WHERE legacy_kind = 'ROOM' AND legacy_conversation_id = 77 "
                + "AND legacy_content_type = 'text'"));
        assertEquals(2, count("SELECT next_sequence FROM chat.conversation WHERE id = '"
                + room + "'"));
        assertEquals(Set.of(sender), new PostgresLegacyV1RoomAudienceAdapter(dataSource())
                .activeMappedMembers(room, Set.of(sender, outsider)));
        try (Connection connection = connect()) {
            execute(connection,
                    "INSERT INTO chat.conversation_member(conversation_id, account_id) "
                            + "VALUES (?, ?)", room, outsider);
        }
        PostgresLegacyV1RoomRecallAdapter recalls =
                new PostgresLegacyV1RoomRecallAdapter(dataSource());
        LegacyV1RoomRecallCommand recallCommand = new LegacyV1RoomRecallCommand(
                sender, 77, accepted.getFirst().legacyMessageId());
        ExecutorService recallExecutor = Executors.newFixedThreadPool(2);
        CountDownLatch recallReady = new CountDownLatch(2), recallStart = new CountDownLatch(1);
        List<LegacyV1RoomRecallResult> recallResults;
        try {
            var task = (java.util.concurrent.Callable<LegacyV1RoomRecallResult>) () -> {
                recallReady.countDown(); assertTrue(recallStart.await(5, TimeUnit.SECONDS));
                return recalls.recall(recallCommand);
            };
            Future<LegacyV1RoomRecallResult> left = recallExecutor.submit(task);
            Future<LegacyV1RoomRecallResult> right = recallExecutor.submit(task);
            assertTrue(recallReady.await(5, TimeUnit.SECONDS)); recallStart.countDown();
            recallResults = List.of(left.get(10, TimeUnit.SECONDS),
                    right.get(10, TimeUnit.SECONDS));
        } finally { recallExecutor.shutdownNow(); }
        List<LegacyV1RoomRecallResult.Recalled> recalled = recallResults.stream()
                .map(LegacyV1RoomRecallResult.Recalled.class::cast).toList();
        assertEquals(1, recalled.stream().filter(result -> !result.duplicate()).count());
        assertEquals(1, recalled.stream().filter(LegacyV1RoomRecallResult.Recalled::duplicate)
                .count());
        assertEquals(2, recalled.getFirst().mutationSequence());
        assertEquals(LegacyV1RoomRecallResult.Rejected.ROOM_ACCESS_DENIED,
                recalls.recall(new LegacyV1RoomRecallCommand(sender, 78,
                        accepted.getFirst().legacyMessageId())));
        assertEquals(LegacyV1RoomRecallResult.Rejected.RECALL_REJECTED,
                recalls.recall(new LegacyV1RoomRecallCommand(outsider, 77,
                        accepted.getFirst().legacyMessageId())));
        LegacyV1RoomMessageResult.Accepted expired = (LegacyV1RoomMessageResult.Accepted)
                adapter.submit(new LegacyV1RoomMessageCommand(sender, senderDevice, 77,
                        "room-client-expired", "expired", "text"));
        try (Connection connection = connect()) {
            execute(connection, "UPDATE chat.message SET accepted_at = "
                    + "transaction_timestamp() - interval '121 seconds' WHERE id = "
                    + "(SELECT message_id FROM chat.legacy_v1_message_map "
                    + "WHERE legacy_kind = 'ROOM' AND legacy_message_id = ?)",
                    expired.legacyMessageId());
        }
        assertEquals(LegacyV1RoomRecallResult.Rejected.RECALL_REJECTED,
                recalls.recall(new LegacyV1RoomRecallCommand(
                        sender, 77, expired.legacyMessageId())));
        assertEquals(1, count("SELECT count(*) FROM chat.message_recall_event "
                + "WHERE conversation_id = '" + room + "'"));
        assertEquals(4, count("SELECT next_sequence FROM chat.conversation WHERE id = '"
                + room + "'"));
        PostgresLegacyV1RoomReadAdapter roomReads =
                new PostgresLegacyV1RoomReadAdapter(dataSource());
        LegacyV1RoomReadResult.Marked marked = (LegacyV1RoomReadResult.Marked)
                roomReads.markRead(new LegacyV1RoomReadCommand(sender, 77));
        assertEquals(0, marked.previousSequence());
        assertEquals(3, marked.lastReadSequence());
        assertTrue(marked.changed());
        LegacyV1RoomReadResult.Marked repeated = (LegacyV1RoomReadResult.Marked)
                roomReads.markRead(new LegacyV1RoomReadCommand(sender, 77));
        assertEquals(3, repeated.previousSequence());
        assertEquals(3, repeated.lastReadSequence());
        assertFalse(repeated.changed());
        assertEquals(LegacyV1RoomReadResult.Rejected.ROOM_ACCESS_DENIED,
                roomReads.markRead(new LegacyV1RoomReadCommand(sender, 78)));
        assertEquals(3, count("SELECT last_read_sequence FROM chat.conversation_member "
                + "WHERE conversation_id = '" + room + "' AND account_id = '" + sender + "'"));
        try (Connection connection = connect()) {
            execute(connection, "UPDATE chat.conversation_member SET left_at = "
                    + "transaction_timestamp() WHERE conversation_id = ?", room);
        }
        LegacyV1RoomRecallResult.Recalled retryAfterLeave =
                (LegacyV1RoomRecallResult.Recalled) recalls.recall(recallCommand);
        assertTrue(retryAfterLeave.duplicate());
        assertEquals(recalled.getFirst().mutationSequence(), retryAfterLeave.mutationSequence());
        assertEquals(recalled.getFirst().occurredAt(), retryAfterLeave.occurredAt());
        assertEquals(LegacyV1RoomMessageResult.Rejected.ROOM_ACCESS_DENIED,
                adapter.submit(new LegacyV1RoomMessageCommand(sender, senderDevice, 77,
                        "room-client-3", "after leave", "emoji")));
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
                        null, account, device, "v1-import-room-501", 1, "text", "hello",
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
                    "INSERT INTO chat.legacy_v1_account_map(legacy_user_id, account_id) "
                            + "VALUES (1, ?)", account);
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
        assertEquals(2, count("SELECT count(*) FROM chat.legacy_v1_message_map "
                + "WHERE legacy_content_type IN ('text', 'emoji')"));
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

        PostgresLegacyV1RoomHistoryAdapter roomHistory =
                new PostgresLegacyV1RoomHistoryAdapter(dataSource());
        LegacyV1RoomHistoryResult.Page roomPage = (LegacyV1RoomHistoryResult.Page)
                roomHistory.read(new LegacyV1RoomHistoryQuery(account, 77, 2, 0, 0L));
        assertEquals(List.of(1L, 3L), roomPage.messages().stream()
                .map(message -> message.syncSequence()).toList());
        assertTrue(roomPage.messages().getLast().recalled());
        assertTrue(roomPage.events().isEmpty());
        assertEquals(3, roomPage.nextSequence());
        assertTrue(roomPage.hasMore());
        LegacyV1RoomHistoryResult.Page roomTail = (LegacyV1RoomHistoryResult.Page)
                roomHistory.read(new LegacyV1RoomHistoryQuery(account, 77, 2, 0,
                        roomPage.nextSequence()));
        assertTrue(roomTail.messages().isEmpty());
        assertEquals(1, roomTail.events().size());
        assertEquals(900, roomTail.events().getFirst().legacyEventId());
        assertEquals(List.of(100L), roomTail.events().getFirst().legacyMessageIds());
        assertEquals(4, roomTail.nextSequence());
        assertFalse(roomTail.hasMore());
        assertEquals(LegacyV1RoomHistoryResult.Rejected.INVALID_SEQUENCE_CURSOR,
                roomHistory.read(new LegacyV1RoomHistoryQuery(account, 77, 2, 0, 5L)));
        assertEquals(LegacyV1RoomHistoryResult.Rejected.ROOM_ACCESS_DENIED,
                roomHistory.read(new LegacyV1RoomHistoryQuery(UUID.randomUUID(), 77,
                        2, 0, 0L)));
        LegacyV1RoomHistoryResult.Page latest = (LegacyV1RoomHistoryResult.Page)
                roomHistory.read(new LegacyV1RoomHistoryQuery(account, 77, 1, 0, null));
        assertEquals(List.of(2L), latest.messages().stream()
                .map(message -> message.sequence()).toList());
        assertTrue(latest.events().isEmpty());

        try (Connection connection = connect()) {
            SQLException invalidType = assertThrows(SQLException.class, () -> execute(connection,
                    "UPDATE chat.legacy_v1_message_map SET legacy_content_type = 'file' "
                            + "WHERE legacy_message_id = 100"));
            assertEquals("23514", invalidType.getSQLState());
            execute(connection,
                    "UPDATE chat.legacy_v1_message_map SET legacy_content_type = NULL "
                            + "WHERE legacy_message_id = 100");
        }
        assertEquals(1, count("SELECT count(*) FROM chat.legacy_v1_message_map "
                + "WHERE legacy_content_type IS NULL"));

        V1MessageImportReport rerun = importer.apply(bundle);
        assertEquals(0, rerun.insertableMessages());
        assertEquals(2, rerun.alreadyImportedMessages());
        assertEquals(0, rerun.insertableEntries());
        assertEquals(4, rerun.alreadyImportedEntries());
        assertEquals(0, count("SELECT count(*) FROM chat.legacy_v1_message_map "
                + "WHERE legacy_content_type IS NULL"));
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
    @Order(11)
    void registersAttachmentMetadataWithExactConcurrentIdempotencyAndAuthorization()
            throws Exception {
        requireDatabase();
        truncateApplicationData();
        UUID account = UUID.randomUUID();
        UUID device = UUID.randomUUID();
        UUID conversation = UUID.randomUUID();
        seedMessageOwner(account, device, conversation);
        PostgresAttachmentAdapter adapter = new PostgresAttachmentAdapter(dataSource());
        byte[] hash = new byte[32];
        hash[0] = 7;
        AttachmentRegistration registration = new AttachmentRegistration(
                conversation, account, device, "client-file-1", "报告.pdf",
                "application/pdf", 1024, hash);

        List<AttachmentRegistrationResult.Accepted> raced =
                raceAttachmentRegistration(adapter, registration);

        assertEquals(2, raced.size());
        assertEquals(1, raced.stream().filter(value -> !value.duplicate()).count());
        assertEquals(1, raced.stream().filter(
                AttachmentRegistrationResult.Accepted::duplicate).count());
        assertEquals(raced.getFirst().attachment().attachmentId(),
                raced.getLast().attachment().attachmentId());
        assertEquals(AttachmentState.UPLOAD_PENDING,
                raced.getFirst().attachment().state());
        assertTrue(raced.getFirst().attachment().objectKey().startsWith("attachments/"));
        assertFalse(raced.getFirst().attachment().objectKey().contains("报告.pdf"));
        assertEquals(1, count("SELECT count(*) FROM chat.attachment"));

        AttachmentRegistration conflict = new AttachmentRegistration(
                conversation, account, device, "client-file-1", "报告.pdf",
                "application/pdf", 2048, hash);
        assertEquals(AttachmentRegistrationResult.Rejected.IDEMPOTENCY_CONFLICT,
                adapter.register(conflict));
        assertEquals(1, count("SELECT count(*) FROM chat.attachment"));

        AttachmentRegistration wrongAccount = new AttachmentRegistration(
                conversation, UUID.randomUUID(), UUID.randomUUID(), "client-file-2",
                "file.txt", "text/plain", 1, new byte[32]);
        assertEquals(AttachmentRegistrationResult.Rejected.NOT_AUTHORIZED,
                adapter.register(wrongAccount));

        AttachmentActor actor = new AttachmentActor(account, device);
        assertEquals(raced.getFirst().attachment().attachmentId(),
                adapter.findAuthorized(raced.getFirst().attachment().attachmentId(), actor)
                        .orElseThrow().attachmentId());
        assertTrue(adapter.findAuthorized(
                raced.getFirst().attachment().attachmentId(),
                new AttachmentActor(account, UUID.randomUUID())).isEmpty());
        Instant readyAt = raced.getFirst().attachment().createdAt().plusSeconds(1);
        List<AttachmentReadyTransition.Ready> readyRace = raceAttachmentReady(
                adapter, raced.getFirst().attachment().attachmentId(), actor, readyAt);
        assertEquals(1, readyRace.stream().filter(
                AttachmentReadyTransition.Ready::changed).count());
        assertEquals(1, readyRace.stream().filter(value -> !value.changed()).count());
        assertEquals(readyRace.getFirst().attachment().attachmentId(),
                readyRace.getLast().attachment().attachmentId());
        assertEquals(readyAt, readyRace.getFirst().attachment().readyAt().orElseThrow());
        AttachmentRegistrationResult.Accepted completedRetry =
                (AttachmentRegistrationResult.Accepted) adapter.register(registration);
        assertTrue(completedRetry.duplicate());
        assertEquals(AttachmentState.READY, completedRetry.attachment().state());

        try (Connection connection = connect()) {
            execute(connection, "UPDATE chat.device SET revoked_at = transaction_timestamp() "
                    + "WHERE id = ?", device);
        }
        AttachmentRegistration afterRevocation = new AttachmentRegistration(
                conversation, account, device, "client-file-3", "file.txt",
                "text/plain", 1, new byte[32]);
        assertEquals(AttachmentRegistrationResult.Rejected.NOT_AUTHORIZED,
                adapter.register(afterRevocation));
        assertTrue(adapter.findAuthorized(
                raced.getFirst().attachment().attachmentId(), actor).isEmpty());
        assertEquals(AttachmentReadyTransition.Rejected.NOT_AVAILABLE,
                adapter.markReadyIfAuthorized(
                        raced.getFirst().attachment().attachmentId(), actor,
                        readyAt.plusSeconds(1)));
        assertEquals(1, count("SELECT count(*) FROM chat.attachment"));
    }

    @Test
    @Order(12)
    void revokesExpiredAttachmentsAndConfirmsObjectDeletionIdempotently()
            throws Exception {
        requireDatabase();
        truncateApplicationData();
        UUID account = UUID.randomUUID();
        UUID device = UUID.randomUUID();
        UUID conversation = UUID.randomUUID();
        seedMessageOwner(account, device, conversation);
        Instant now = Instant.parse("2026-08-12T12:00:00Z");
        Instant cutoff = now.minusSeconds(3_600);
        UUID first = insertCleanupAttachment(
                account, device, conversation, "cleanup-1", cutoff.minusSeconds(2), false);
        UUID second = insertCleanupAttachment(
                account, device, conversation, "cleanup-2", cutoff.minusSeconds(1), false);
        UUID boundary = insertCleanupAttachment(
                account, device, conversation, "cleanup-3", cutoff, false);
        insertCleanupAttachment(
                account, device, conversation, "recent", cutoff.plusSeconds(1), false);
        insertCleanupAttachment(
                account, device, conversation, "ready", cutoff.minusSeconds(10), true);
        PostgresAttachmentAdapter adapter = new PostgresAttachmentAdapter(dataSource());

        List<Integer> revoked = raceExpiredAttachmentRevocation(
                adapter, cutoff, now, 10);

        assertEquals(3, revoked.stream().mapToInt(Integer::intValue).sum());
        assertEquals(3, count("SELECT count(*) FROM chat.attachment "
                + "WHERE state = 'REVOKED' AND object_deleted_at IS NULL"));
        List<AttachmentCleanupCandidate> firstPage =
                adapter.findObjectCleanupRequired(2);
        assertEquals(2, firstPage.size());
        assertTrue(firstPage.stream().allMatch(candidate ->
                Set.of(first, second, boundary).contains(candidate.attachmentId())));

        assertFalse(adapter.confirmObjectDeleted(first, now.minusSeconds(1)));
        assertTrue(adapter.confirmObjectDeleted(first, now.plusSeconds(1)));
        assertTrue(adapter.confirmObjectDeleted(first, now.plusSeconds(2)));
        assertEquals(2, adapter.findObjectCleanupRequired(10).size());
        assertTrue(adapter.findObjectCleanupRequired(10).stream()
                .anyMatch(candidate -> candidate.attachmentId().equals(boundary)));
        assertFalse(adapter.confirmObjectDeleted(UUID.randomUUID(), now.plusSeconds(1)));
        assertThrows(IllegalArgumentException.class,
                () -> adapter.findObjectCleanupRequired(0));
        assertThrows(IllegalArgumentException.class,
                () -> adapter.revokeExpiredPending(now, cutoff, 1));
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
        SQLException deletedWhileReady = assertThrows(SQLException.class, () -> execute(
                connection,
                "UPDATE chat.attachment SET object_deleted_at = transaction_timestamp() "
                        + "WHERE id = ?",
                attachment));
        assertEquals("23514", deletedWhileReady.getSQLState());
        execute(connection,
                "UPDATE chat.attachment SET state = 'REVOKED', "
                        + "revoked_at = transaction_timestamp(), "
                        + "object_deleted_at = transaction_timestamp() WHERE id = ?",
                attachment);
    }

    private static void proveContactRequestConstraints(Connection connection)
            throws SQLException {
        UUID requester = UUID.randomUUID();
        UUID recipient = UUID.randomUUID();
        UUID request = UUID.randomUUID();
        execute(connection,
                "INSERT INTO chat.account(id, username_key, display_name, password_hash) "
                        + "VALUES (?, 'contact-requester', 'Requester', "
                        + "'$argon2id$v=19$m=65536,t=2,p=1$test$fixture'), "
                        + "(?, 'contact-recipient', 'Recipient', "
                        + "'$argon2id$v=19$m=65536,t=2,p=1$test$fixture')",
                requester, recipient);
        execute(connection,
                "INSERT INTO chat.contact_request("
                        + "id, requester_account_id, recipient_account_id) VALUES (?, ?, ?)",
                request, requester, recipient);
        execute(connection,
                "INSERT INTO chat.legacy_v1_contact_request_map("
                        + "legacy_request_id, contact_request_id) VALUES (71, ?)",
                request);

        SQLException selfRequest = assertThrows(SQLException.class, () -> execute(
                connection,
                "INSERT INTO chat.contact_request("
                        + "id, requester_account_id, recipient_account_id) VALUES (?, ?, ?)",
                UUID.randomUUID(), requester, requester));
        assertEquals("23514", selfRequest.getSQLState());
        SQLException reversePending = assertThrows(SQLException.class, () -> execute(
                connection,
                "INSERT INTO chat.contact_request("
                        + "id, requester_account_id, recipient_account_id) VALUES (?, ?, ?)",
                UUID.randomUUID(), recipient, requester));
        assertEquals("23505", reversePending.getSQLState());
        SQLException unresolvedTerminal = assertThrows(SQLException.class, () -> execute(
                connection,
                "UPDATE chat.contact_request SET state = 'ACCEPTED' WHERE id = ?",
                request));
        assertEquals("23514", unresolvedTerminal.getSQLState());
        execute(connection,
                "UPDATE chat.contact_request SET state = 'ACCEPTED', "
                        + "resolved_at = transaction_timestamp() WHERE id = ?",
                request);
        execute(connection,
                "INSERT INTO chat.contact_request("
                        + "id, requester_account_id, recipient_account_id) VALUES (?, ?, ?)",
                UUID.randomUUID(), recipient, requester);
        SQLException duplicateLegacyTarget = assertThrows(SQLException.class, () -> execute(
                connection,
                "INSERT INTO chat.legacy_v1_contact_request_map("
                        + "legacy_request_id, contact_request_id) VALUES (72, ?)",
                request));
        assertEquals("23505", duplicateLegacyTarget.getSQLState());
        SQLException invalidLegacyId = assertThrows(SQLException.class, () -> execute(
                connection,
                "INSERT INTO chat.legacy_v1_contact_request_map("
                        + "legacy_request_id, contact_request_id) VALUES (0, ?)",
                request));
        assertEquals("23514", invalidLegacyId.getSQLState());
    }

    private static void proveDirectSelfConversationConstraint(Connection connection)
            throws SQLException {
        UUID account = UUID.randomUUID();
        UUID conversation = UUID.randomUUID();
        execute(connection,
                "INSERT INTO chat.account(id, username_key, display_name, password_hash) "
                        + "VALUES (?, 'self-chat-user', 'Self Chat', "
                        + "'$argon2id$v=19$m=65536,t=2,p=1$test$fixture')",
                account);
        execute(connection,
                "INSERT INTO chat.conversation(id, kind) VALUES (?, 'DIRECT')",
                conversation);
        execute(connection,
                "INSERT INTO chat.direct_conversation("
                        + "conversation_id, first_account_id, second_account_id) "
                        + "VALUES (?, ?, ?)",
                conversation, account, account);
        SQLException duplicate = assertThrows(SQLException.class, () -> execute(
                connection,
                "INSERT INTO chat.direct_conversation("
                        + "conversation_id, first_account_id, second_account_id) "
                        + "VALUES (?, ?, ?)",
                conversation, account, account));
        assertEquals("23505", duplicate.getSQLState());
    }

    private static void proveContactRequestImportAuditConstraints(Connection connection) {
        SQLException mismatch = assertThrows(SQLException.class, () -> execute(
                connection,
                "INSERT INTO chat.contact_request_import_run("
                        + "id, source_fingerprint_sha256, backup_file_sha256, "
                        + "source_requests, source_pending_requests, source_terminal_requests, "
                        + "inserted_pending_requests, already_imported_pending_requests, "
                        + "backup_bytes, backup_created_at) "
                        + "VALUES (?, ?, ?, 2, 1, 1, 0, 0, 1024, transaction_timestamp())",
                UUID.randomUUID(), "a".repeat(64), "b".repeat(64)));
        assertEquals("23514", mismatch.getSQLState());
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

    private static List<AttachmentRegistrationResult.Accepted> raceAttachmentRegistration(
            PostgresAttachmentAdapter adapter,
            AttachmentRegistration registration) throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<AttachmentRegistrationResult>> futures =
                    java.util.stream.IntStream.range(0, 2)
                            .mapToObj(index -> executor.submit(() -> {
                                ready.countDown();
                                assertTrue(start.await(2, TimeUnit.SECONDS));
                                return adapter.register(registration);
                            }))
                            .toList();
            assertTrue(ready.await(2, TimeUnit.SECONDS));
            start.countDown();
            return List.of(
                    (AttachmentRegistrationResult.Accepted) futures.get(0).get(),
                    (AttachmentRegistrationResult.Accepted) futures.get(1).get());
        } finally {
            start.countDown();
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(2, TimeUnit.SECONDS));
        }
    }

    private static List<AttachmentReadyTransition.Ready> raceAttachmentReady(
            PostgresAttachmentAdapter adapter,
            UUID attachmentId,
            AttachmentActor actor,
            Instant readyAt) throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<AttachmentReadyTransition>> futures =
                    java.util.stream.IntStream.range(0, 2)
                            .mapToObj(index -> executor.submit(() -> {
                                ready.countDown();
                                assertTrue(start.await(2, TimeUnit.SECONDS));
                                return adapter.markReadyIfAuthorized(
                                        attachmentId, actor, readyAt);
                            }))
                            .toList();
            assertTrue(ready.await(2, TimeUnit.SECONDS));
            start.countDown();
            return List.of(
                    (AttachmentReadyTransition.Ready) futures.get(0).get(),
                    (AttachmentReadyTransition.Ready) futures.get(1).get());
        } finally {
            start.countDown();
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(2, TimeUnit.SECONDS));
        }
    }

    private static List<Integer> raceExpiredAttachmentRevocation(
            PostgresAttachmentAdapter adapter,
            Instant cutoff,
            Instant revokedAt,
            int limit) throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<Integer>> futures = java.util.stream.IntStream.range(0, 2)
                    .mapToObj(index -> executor.submit(() -> {
                        ready.countDown();
                        assertTrue(start.await(2, TimeUnit.SECONDS));
                        return adapter.revokeExpiredPending(cutoff, revokedAt, limit);
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

    private static UUID insertCleanupAttachment(
            UUID account,
            UUID device,
            UUID conversation,
            String clientId,
            Instant createdAt,
            boolean ready) throws SQLException {
        UUID attachment = UUID.randomUUID();
        try (Connection connection = connect()) {
            execute(connection,
                    "INSERT INTO chat.attachment(id, conversation_id, owner_account_id, "
                            + "owner_device_id, client_attachment_id, object_key, file_name, "
                            + "media_type, byte_size, content_sha256, state, created_at, ready_at) "
                            + "VALUES (?, ?, ?, ?, ?, ?, 'cleanup.bin', "
                            + "'application/octet-stream', 1, ?, ?, ?, ?)",
                    attachment, conversation, account, device, clientId,
                    "attachments/" + attachment, new byte[32],
                    ready ? "READY" : "UPLOAD_PENDING",
                    OffsetDateTime.ofInstant(createdAt, ZoneOffset.UTC),
                    ready
                            ? OffsetDateTime.ofInstant(createdAt.plusSeconds(1), ZoneOffset.UTC)
                            : null);
        }
        return attachment;
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
                                + "chat.message_import_run, chat.contact_request_import_run "
                                + "CASCADE")) {
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

    private static void createContactRequestSource(Path source) throws SQLException {
        try (Connection connection = DriverManager.getConnection(
                "jdbc:sqlite:" + source.toAbsolutePath());
                java.sql.Statement statement = connection.createStatement()) {
            statement.execute("PRAGMA journal_mode = WAL");
            statement.execute("CREATE TABLE users(id INTEGER PRIMARY KEY, username TEXT UNIQUE, "
                    + "display_name TEXT, password_hash TEXT, salt TEXT, created_at TEXT)");
            statement.execute("CREATE TABLE friendships(user_id1 INTEGER, user_id2 INTEGER)");
            statement.execute("CREATE TABLE friend_requests(id INTEGER PRIMARY KEY, "
                    + "from_user_id INTEGER, to_user_id INTEGER, status TEXT, created_at TEXT)");
            statement.execute("INSERT INTO users VALUES "
                    + "(1, 'contact-a', 'Contact A', '" + "a".repeat(64)
                    + "', 'salt-a', '2026-01-02 03:04:01'), "
                    + "(2, 'contact-b', 'Contact B', '" + "b".repeat(64)
                    + "', 'salt-b', '2026-01-02 03:04:02'), "
                    + "(3, 'contact-c', 'Contact C', '" + "c".repeat(64)
                    + "', 'salt-c', '2026-01-02 03:04:03')");
            statement.execute("INSERT INTO friend_requests VALUES "
                    + "(10, 1, 2, 'pending', '2026-01-02 03:04:05'), "
                    + "(11, 2, 3, 'rejected', '2026-01-02 03:04:06')");
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

    private static void seedContactRequestImportAccounts() throws SQLException {
        try (Connection connection = connect()) {
            execute(connection,
                    "INSERT INTO chat.account(id, username_key, display_name, password_hash) "
                            + "VALUES (?, 'contact-a', 'Contact A', "
                            + "'$argon2id$v=19$m=65536,t=2,p=1$test$fixture'), "
                            + "(?, 'contact-b', 'Contact B', "
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
