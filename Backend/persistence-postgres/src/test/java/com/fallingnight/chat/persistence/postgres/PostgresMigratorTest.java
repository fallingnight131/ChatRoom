package com.fallingnight.chat.persistence.postgres;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
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
import com.fallingnight.chat.application.identity.AuthenticatedDeviceActor;
import com.fallingnight.chat.application.identity.DeviceDirectoryResult;
import com.fallingnight.chat.application.identity.DeviceManagementService;
import com.fallingnight.chat.application.identity.DeviceRevocationResult;
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
import com.fallingnight.chat.application.compatibility.v1.LegacyV1RoomLeaveIntent;
import com.fallingnight.chat.application.compatibility.v1.LegacyV1RoomLeaveResult;
import com.fallingnight.chat.application.compatibility.v1.LegacyV1RoomAdminCommand;
import com.fallingnight.chat.application.compatibility.v1.LegacyV1RoomAdminResult;
import com.fallingnight.chat.application.compatibility.v1.LegacyV1RoomKickCommand;
import com.fallingnight.chat.application.compatibility.v1.LegacyV1RoomKickResult;
import com.fallingnight.chat.application.compatibility.v1.LegacyV1RoomMessageDeletionCommand;
import com.fallingnight.chat.application.compatibility.v1.LegacyV1RoomMessageDeletionResult;
import com.fallingnight.chat.application.compatibility.v1.LegacyV1RoomMessageDeletionService;
import com.fallingnight.chat.application.compatibility.v1.LegacyV1RoomRenameCommand;
import com.fallingnight.chat.application.compatibility.v1.LegacyV1RoomRenameResult;
import com.fallingnight.chat.application.compatibility.v1.LegacyV1RoomRenameService;
import com.fallingnight.chat.application.compatibility.v1.LegacyV1NicknameChangeCommand;
import com.fallingnight.chat.application.compatibility.v1.LegacyV1NicknameChangeResult;
import com.fallingnight.chat.application.compatibility.v1.LegacyV1NicknameChangeService;
import com.fallingnight.chat.application.compatibility.v1.LegacyV1UsernameChangeCommand;
import com.fallingnight.chat.application.compatibility.v1.LegacyV1UsernameChangeResult;
import com.fallingnight.chat.application.compatibility.v1.LegacyV1UsernameChangeService;
import com.fallingnight.chat.application.compatibility.v1.LegacyV1RoomPasswordIntent;
import com.fallingnight.chat.application.compatibility.v1.LegacyV1RoomPasswordStatusResult;
import com.fallingnight.chat.application.compatibility.v1.LegacyV1RoomPasswordUpdateResult;
import com.fallingnight.chat.application.compatibility.v1.LegacyV1RoomDissolutionIntent;
import com.fallingnight.chat.application.compatibility.v1.LegacyV1RoomDissolutionResult;
import com.fallingnight.chat.application.compatibility.v1.LegacyV1PasswordChangeAccess;
import com.fallingnight.chat.application.compatibility.v1.LegacyV1PasswordChangeIntent;
import com.fallingnight.chat.application.compatibility.v1.LegacyV1PasswordChangePersistenceResult;
import com.fallingnight.chat.application.compatibility.v1.LegacyV1RegistrationIntent;
import com.fallingnight.chat.application.compatibility.v1.LegacyV1RegistrationPersistenceResult;
import com.fallingnight.chat.application.compatibility.v1.LegacyV1RoomMemberListPort;
import com.fallingnight.chat.application.compatibility.v1.LegacyV1RoomSettings;
import com.fallingnight.chat.application.compatibility.v1.LegacyV1RoomSettingsPort;
import com.fallingnight.chat.application.compatibility.v1.LegacyV1RoomFilesPort;
import com.fallingnight.chat.application.conversation.ConversationDirectoryPage;
import com.fallingnight.chat.application.conversation.ConversationDirectoryQuery;
import com.fallingnight.chat.application.conversation.ConversationKind;
import com.fallingnight.chat.application.conversation.ConversationParticipantPage;
import com.fallingnight.chat.application.conversation.ConversationParticipantQuery;
import com.fallingnight.chat.application.conversation.ConversationParticipantResult;
import com.fallingnight.chat.application.contact.AccountBlockIntent;
import com.fallingnight.chat.application.contact.AccountBlockDirectoryPage;
import com.fallingnight.chat.application.contact.AccountBlockDirectoryRequest;
import com.fallingnight.chat.application.contact.AccountBlockDirectoryResult;
import com.fallingnight.chat.application.contact.AccountBlockDirectoryService;
import com.fallingnight.chat.application.contact.AccountBlockResult;
import com.fallingnight.chat.application.contact.AccountBlockService;
import com.fallingnight.chat.application.messaging.MessageHistoryQuery;
import com.fallingnight.chat.application.messaging.MessageHistoryResult;
import com.fallingnight.chat.application.messaging.MessageMention;
import com.fallingnight.chat.application.messaging.MessageForwardCommand;
import com.fallingnight.chat.application.messaging.MessageForwardResult;
import com.fallingnight.chat.application.messaging.ConversationEntryHistoryResult;
import com.fallingnight.chat.application.messaging.ConversationEventOutboxClaim;
import com.fallingnight.chat.application.messaging.ConversationEventOutboxStatus;
import com.fallingnight.chat.application.messaging.ConversationHistoryEntry;
import com.fallingnight.chat.application.messaging.MessageSubmission;
import com.fallingnight.chat.application.messaging.MessageSubmissionResult;
import com.fallingnight.chat.application.messaging.MessageReactionCommand;
import com.fallingnight.chat.application.messaging.MessageReactionKind;
import com.fallingnight.chat.application.messaging.MessageReactionResult;
import com.fallingnight.chat.application.messaging.MessageSearchPage;
import com.fallingnight.chat.application.messaging.MessageSearchQuery;
import com.fallingnight.chat.application.messaging.MessageSearchResult;
import com.fallingnight.chat.application.messaging.MessagePinCommand;
import com.fallingnight.chat.application.messaging.MessagePinResult;
import com.fallingnight.chat.application.messaging.MessageEditCommand;
import com.fallingnight.chat.application.messaging.MessageEditResult;
import com.fallingnight.chat.application.notification.ProtectedWebPushSubscription;
import com.fallingnight.chat.application.notification.WebPushCredentialProtectionPort;
import com.fallingnight.chat.application.notification.WebPushCredentialUnprotectionPort;
import com.fallingnight.chat.application.notification.WebPushHttpCredentialAuthenticationResult;
import com.fallingnight.chat.application.notification.WebPushDeliveryPolicy;
import com.fallingnight.chat.application.notification.WebPushNotificationIntent;
import com.fallingnight.chat.application.notification.WebPushOutboxClaim;
import com.fallingnight.chat.application.notification.WebPushRecipientResolution;
import com.fallingnight.chat.application.notification.WebPushSubscriptionRegistration;
import com.fallingnight.chat.application.notification.WebPushSubscriptionReplaceResult;
import com.fallingnight.chat.application.notification.WebPushTerminalOutcome;
import com.fallingnight.chat.application.security.SecretBytes;
import com.fallingnight.chat.application.profile.ProfileImageMetadataCommand;
import com.fallingnight.chat.application.profile.ProfileImageMetadataResult;
import com.fallingnight.chat.application.profile.ProfileImageObjectEvidence;
import com.fallingnight.chat.application.profile.ProfileImageTarget;
import com.fallingnight.chat.application.profile.ProfileImageReadResult;
import com.fallingnight.chat.application.profile.ProfileImageReadTarget;
import com.fallingnight.chat.application.profile.ProfileImageMutationAuthorization;
import com.fallingnight.chat.application.profile.ProfileImageCleanupClaim;
import com.fallingnight.chat.persistence.postgres.migration.PostgresV1IdentityImporter;
import com.fallingnight.chat.persistence.postgres.migration.PostgresV1ConversationImporter;
import com.fallingnight.chat.persistence.postgres.migration.PostgresV1ContactRequestImporter;
import com.fallingnight.chat.persistence.postgres.migration.PostgresV1MessageImporter;
import com.fallingnight.chat.persistence.postgres.migration.PostgresV1ProfileImageImportPlanner;
import com.fallingnight.chat.persistence.postgres.migration.PostgresV1ProfileImageImporter;
import com.fallingnight.chat.persistence.postgres.migration.ProviderVerifiedV1ProfileImageImportInput;
import com.fallingnight.chat.persistence.postgres.migration.PlannedV1ConversationCursor;
import com.fallingnight.chat.persistence.postgres.migration.PlannedV1HistoricalMessage;
import com.fallingnight.chat.persistence.postgres.migration.PlannedV1LegacyDevice;
import com.fallingnight.chat.persistence.postgres.migration.PlannedV1MemberReadCursor;
import com.fallingnight.chat.persistence.postgres.migration.V1MessageImportReport;
import com.fallingnight.chat.persistence.postgres.migration.V1MessageImportException;
import com.fallingnight.chat.persistence.postgres.migration.V1ProfileImageImportEntry;
import com.fallingnight.chat.persistence.postgres.migration.V1ProfileImageImportPlan;
import com.fallingnight.chat.persistence.postgres.migration.V1ProfileImageImportException;
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
import com.fallingnight.chat.persistence.postgres.migration.V1UnifiedAttachmentImportFixture;
import com.fallingnight.chat.persistence.postgres.migration.VerifiedV1UnifiedMessageImportBundle;
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
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
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
        assertEquals(54, first.migrate());
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
                            "attachment_import_run",
                            "attachment", "contact_request",
                            "legacy_v1_contact_request_map",
                            "contact_request_import_run", "group_join_credential",
                            "legacy_v1_room_creation", "group_admission_policy",
                            "group_lifecycle", "group_resource_policy",
                            "legacy_v1_attachment_map", "legacy_v1_room_kick_event",
                            "legacy_v1_room_dissolution", "account_password_change_audit",
                            "legacy_v1_registration_audit",
                            "account_display_name_change_audit",
                            "account_username_change_audit", "profile_image_object",
                            "account_profile_image", "group_profile_image",
                            "profile_image_change_audit", "profile_image_import_run",
                            "profile_image_import_entry", "device_revocation_audit",
                            "message_reply_reference", "message_reaction_operation",
                            "message_reaction", "message_reaction_event",
                            "message_pin_operation", "message_pin", "message_pin_event",
                            "message_edit_operation", "message_edit_event",
                            "message_mention", "message_edit_event_mention",
                            "message_forward_request", "conversation_event_outbox",
                            "account_block", "account_block_operation",
                            "web_push_subscription", "web_push_notification_outbox",
                            "web_push_http_credential"),
                    applicationTables(connection));
            assertEquals(2, count("SELECT count(*) FROM pg_indexes "
                    + "WHERE schemaname = 'chat' AND indexname IN ("
                    + "'web_push_notification_outbox_available_idx', "
                    + "'web_push_notification_outbox_retention_idx')"));
            assertEquals(2, count("SELECT count(*) FROM pg_constraint "
                    + "WHERE connamespace = 'chat'::regnamespace AND conname IN ("
                    + "'web_push_subscription_endpoint_unique', "
                    + "'web_push_notification_lifetime')"));
            assertEquals(1, count("SELECT count(*) FROM pg_indexes "
                    + "WHERE schemaname = 'chat' "
                    + "AND indexname = 'web_push_http_credential_expiry_idx'"));
            assertEquals(3, count("SELECT count(*) FROM pg_constraint "
                    + "WHERE connamespace = 'chat'::regnamespace AND conname IN ("
                    + "'web_push_http_credential_bearer_hash_length', "
                    + "'web_push_http_credential_csrf_hash_length', "
                    + "'web_push_http_credential_lifetime')"));
            assertEquals(11, count("SELECT count(*) FROM pg_constraint "
                    + "WHERE connamespace = 'chat'::regnamespace "
                    + "AND conrelid = 'chat.conversation_event_outbox'::regclass"));
            assertEquals(1, count("SELECT count(*) FROM pg_indexes "
                    + "WHERE schemaname = 'chat' "
                    + "AND indexname = 'conversation_event_outbox_available_idx'"));
            assertEquals(1, count("SELECT count(*) FROM information_schema.columns "
                    + "WHERE table_schema = 'chat' "
                    + "AND table_name = 'conversation_event_outbox' "
                    + "AND column_name = 'claim_id' AND is_nullable = 'YES'"));
            assertEquals(1, count("SELECT count(*) FROM information_schema.columns "
                    + "WHERE table_schema = 'chat' AND table_name = 'message' "
                    + "AND column_name = 'forwarded' AND is_nullable = 'NO'"));
            assertEquals(1, count("SELECT count(*) FROM pg_constraint "
                    + "WHERE connamespace = 'chat'::regnamespace "
                    + "AND conname = 'message_forward_request_hash_length'"));
            assertEquals(2, count("SELECT count(*) FROM information_schema.columns "
                    + "WHERE table_schema = 'chat' AND table_name = 'message' "
                    + "AND column_name IN ('content_revision', 'edited_at')"));
            assertEquals(1, count("SELECT count(*) FROM pg_constraint "
                    + "WHERE connamespace = 'chat'::regnamespace "
                    + "AND conname = 'message_edit_event_revision_unique'"));
            assertEquals(5, count("SELECT count(*) FROM pg_constraint "
                    + "WHERE connamespace = 'chat'::regnamespace AND conname IN ("
                    + "'message_mention_ordinal_bounded', "
                    + "'message_mention_span_bounded', "
                    + "'message_edit_operation_mentions_hash_length', "
                    + "'message_edit_event_mention_ordinal_bounded', "
                    + "'message_edit_event_mention_span_bounded')"));
            assertEquals(2, count("SELECT count(*) FROM pg_trigger "
                    + "WHERE NOT tgisinternal AND tgname IN ("
                    + "'message_recall_erase_edit_bodies', "
                    + "'messages_deleted_erase_edit_bodies')"));
            assertEquals(9, count("SELECT count(*) FROM pg_constraint "
                    + "WHERE connamespace = 'chat'::regnamespace AND conname IN ("
                    + "'profile_image_import_manifest_hex', "
                    + "'profile_image_import_backup_hex', "
                    + "'profile_image_import_identity_hex', "
                    + "'profile_image_import_counts_nonnegative', "
                    + "'profile_image_import_counts_reconcile', "
                    + "'profile_image_import_entry_legacy_id', "
                    + "'profile_image_import_entry_target', "
                    + "'profile_image_import_entry_state', "
                    + "'profile_image_import_entry_conversation')"));
            assertEquals(2, count("SELECT count(*) FROM pg_indexes "
                    + "WHERE schemaname = 'chat' AND indexname IN ("
                    + "'profile_image_import_entry_account_target_idx', "
                    + "'profile_image_import_entry_room_target_idx')"));
            assertEquals(6, count("SELECT count(*) FROM pg_constraint "
                    + "WHERE connamespace = 'chat'::regnamespace AND conname IN ("
                    + "'device_revocation_target_owner', "
                    + "'device_revocation_actor_owner', "
                    + "'device_revocation_not_self', "
                    + "'device_revocation_session_count', "
                    + "'device_revocation_reason_supported', "
                    + "'device_revocation_audit_target_device_id_key')"));
            assertEquals(1, count("SELECT count(*) FROM pg_indexes "
                    + "WHERE schemaname = 'chat' "
                    + "AND indexname = 'device_revocation_audit_account_idx'"));
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
            assertEquals(1, count("SELECT count(*) FROM pg_sequences "
                    + "WHERE schemaname = 'chat' "
                    + "AND sequencename = 'legacy_v1_deletion_event_id_seq' "
                    + "AND increment_by = -1 AND min_value = 1 "
                    + "AND max_value = 2147483647"));
            assertEquals(1, count("SELECT count(*) FROM pg_indexes "
                    + "WHERE schemaname = 'chat' "
                    + "AND indexname = 'messages_deleted_event_runtime_operation_unique'"));
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
    @Order(98)
    void persistsAsymmetricBlocksAndConvergesExactOperations() throws Exception {
        requireDatabase();
        truncateApplicationData();
        UUID actor = UUID.randomUUID();
        UUID target = UUID.randomUUID();
        UUID other = UUID.randomUUID();
        try (Connection connection = connect()) {
            execute(connection,
                    "INSERT INTO chat.account(id, username_key, display_name, password_hash) "
                            + "VALUES (?, ?, 'Block Actor', "
                            + "'$argon2id$v=19$m=65536,t=2,p=1$test$fixture'), "
                            + "(?, ?, 'Block Target', "
                            + "'$argon2id$v=19$m=65536,t=2,p=1$test$fixture'), "
                            + "(?, ?, 'Block Other', "
                            + "'$argon2id$v=19$m=65536,t=2,p=1$test$fixture')",
                    actor, "block-actor-" + actor,
                    target, "block-target-" + target,
                    other, "block-other-" + other);
        }
        var service = new AccountBlockService(new PostgresAccountBlockAdapter(dataSource()));
        UUID blockOperation = UUID.randomUUID();
        var first = new AccountBlockResult.Applied(
                actor, target, true, true, blockOperation);
        assertEquals(first, service.apply(actor,
                new AccountBlockIntent(target, true, blockOperation)));
        assertEquals(first, service.apply(actor,
                new AccountBlockIntent(target, true, blockOperation)));
        assertEquals(new AccountBlockResult.OperationConflict(blockOperation),
                service.apply(actor, new AccountBlockIntent(other, true, blockOperation)));
        assertEquals(1, accountBlockCount(actor, target));

        UUID unchangedOperation = UUID.randomUUID();
        assertEquals(new AccountBlockResult.Applied(
                actor, target, true, false, unchangedOperation),
                service.apply(actor,
                        new AccountBlockIntent(target, true, unchangedOperation)));

        try (Connection connection = connect()) {
            execute(connection,
                    "UPDATE chat.account SET disabled_at=transaction_timestamp() WHERE id=?",
                    target);
        }
        assertEquals(first, service.apply(actor,
                new AccountBlockIntent(target, true, blockOperation)));
        UUID unblockOperation = UUID.randomUUID();
        assertEquals(AccountBlockResult.Rejected.TARGET_UNAVAILABLE,
                service.apply(actor,
                        new AccountBlockIntent(target, false, unblockOperation)));
        assertEquals(1, accountBlockCount(actor, target));
        try (Connection connection = connect()) {
            execute(connection, "UPDATE chat.account SET disabled_at=NULL WHERE id=?", target);
        }
        assertEquals(new AccountBlockResult.Applied(
                actor, target, false, true, unblockOperation),
                service.apply(actor,
                        new AccountBlockIntent(target, false, unblockOperation)));

        UUID repeatedUnblock = UUID.randomUUID();
        assertEquals(new AccountBlockResult.Applied(
                actor, target, false, false, repeatedUnblock),
                service.apply(actor,
                        new AccountBlockIntent(target, false, repeatedUnblock)));
        assertEquals(0, accountBlockCount(actor, target));

        UUID concurrentOperation = UUID.randomUUID();
        var concurrentExpected = new AccountBlockResult.Applied(
                actor, target, true, true, concurrentOperation);
        ExecutorService exactExecutor = Executors.newFixedThreadPool(2);
        CountDownLatch exactReady = new CountDownLatch(2);
        CountDownLatch exactStart = new CountDownLatch(1);
        try {
            java.util.concurrent.Callable<AccountBlockResult> exactTask = () -> {
                exactReady.countDown();
                assertTrue(exactStart.await(5, TimeUnit.SECONDS));
                return service.apply(actor,
                        new AccountBlockIntent(target, true, concurrentOperation));
            };
            Future<AccountBlockResult> left = exactExecutor.submit(exactTask);
            Future<AccountBlockResult> right = exactExecutor.submit(exactTask);
            assertTrue(exactReady.await(5, TimeUnit.SECONDS));
            exactStart.countDown();
            assertEquals(concurrentExpected, left.get(10, TimeUnit.SECONDS));
            assertEquals(concurrentExpected, right.get(10, TimeUnit.SECONDS));
        } finally {
            exactExecutor.shutdownNow();
        }
        assertEquals(1, accountBlockCount(actor, target));

        UUID actorUnblock = UUID.randomUUID();
        UUID reverseBlock = UUID.randomUUID();
        ExecutorService reverseExecutor = Executors.newFixedThreadPool(2);
        CountDownLatch reverseReady = new CountDownLatch(2);
        CountDownLatch reverseStart = new CountDownLatch(1);
        try {
            Future<AccountBlockResult> forward = reverseExecutor.submit(() -> {
                reverseReady.countDown();
                assertTrue(reverseStart.await(5, TimeUnit.SECONDS));
                return service.apply(actor,
                        new AccountBlockIntent(target, false, actorUnblock));
            });
            Future<AccountBlockResult> reverse = reverseExecutor.submit(() -> {
                reverseReady.countDown();
                assertTrue(reverseStart.await(5, TimeUnit.SECONDS));
                return service.apply(target,
                        new AccountBlockIntent(actor, true, reverseBlock));
            });
            assertTrue(reverseReady.await(5, TimeUnit.SECONDS));
            reverseStart.countDown();
            assertEquals(new AccountBlockResult.Applied(
                    actor, target, false, true, actorUnblock),
                    forward.get(10, TimeUnit.SECONDS));
            assertEquals(new AccountBlockResult.Applied(
                    target, actor, true, true, reverseBlock),
                    reverse.get(10, TimeUnit.SECONDS));
        } finally {
            reverseExecutor.shutdownNow();
        }
        assertEquals(0, accountBlockCount(actor, target));
        assertEquals(1, accountBlockCount(target, actor));
        assertEquals(6, accountBlockOperationCount(actor));
        assertAccountBlockSelfConstraint(actor);
    }

    @Test
    @Order(98)
    void readsOnlyAuthenticatedActorsOutgoingBlocksWithStableTargetPages()
            throws Exception {
        requireDatabase();
        truncateApplicationData();
        UUID actor = UUID.fromString("f0000000-0000-4000-8000-000000000001");
        UUID firstTarget = UUID.fromString("10000000-0000-4000-8000-000000000001");
        UUID secondTarget = UUID.fromString("20000000-0000-4000-8000-000000000001");
        try (Connection connection = connect()) {
            execute(connection,
                    "INSERT INTO chat.account(id,username_key,display_name,password_hash) "
                            + "VALUES (?,'directory-actor','Directory Actor',"
                            + "'$argon2id$v=19$m=65536,t=2,p=1$test$fixture'),"
                            + "(?,'directory-first','Directory First',"
                            + "'$argon2id$v=19$m=65536,t=2,p=1$test$fixture'),"
                            + "(?,'directory-second','Directory Second',"
                            + "'$argon2id$v=19$m=65536,t=2,p=1$test$fixture')",
                    actor, firstTarget, secondTarget);
        }
        var mutations = new AccountBlockService(
                new PostgresAccountBlockAdapter(dataSource()));
        assertInstanceOf(AccountBlockResult.Applied.class,
                mutations.apply(actor, new AccountBlockIntent(
                        secondTarget, true, UUID.randomUUID())));
        assertInstanceOf(AccountBlockResult.Applied.class,
                mutations.apply(actor, new AccountBlockIntent(
                        firstTarget, true, UUID.randomUUID())));
        assertInstanceOf(AccountBlockResult.Applied.class,
                mutations.apply(firstTarget, new AccountBlockIntent(
                        actor, true, UUID.randomUUID())));

        var directory = new AccountBlockDirectoryService(
                new PostgresAccountBlockDirectoryAdapter(dataSource()));
        AccountBlockDirectoryPage first = assertInstanceOf(
                AccountBlockDirectoryResult.Found.class,
                directory.list(actor, new AccountBlockDirectoryRequest(
                        Optional.empty(), 1))).page();
        assertEquals(actor, first.accountId());
        assertEquals(List.of(firstTarget), first.blocks().stream()
                .map(block -> block.targetAccountId()).toList());
        assertEquals("Directory First", first.blocks().getFirst().targetDisplayName());
        assertTrue(first.hasMore());
        assertEquals(firstTarget, first.nextAfterTargetAccountId().orElseThrow());

        try (Connection connection = connect()) {
            execute(connection, "UPDATE chat.account SET display_name='Renamed Second' WHERE id=?",
                    secondTarget);
        }
        AccountBlockDirectoryPage second = assertInstanceOf(
                AccountBlockDirectoryResult.Found.class,
                directory.list(actor, new AccountBlockDirectoryRequest(
                        Optional.of(firstTarget), 1))).page();
        assertEquals(List.of(secondTarget), second.blocks().stream()
                .map(block -> block.targetAccountId()).toList());
        assertEquals("Renamed Second", second.blocks().getFirst().targetDisplayName());
        assertFalse(second.hasMore());
        assertTrue(second.nextAfterTargetAccountId().isEmpty());
        assertTrue(second.blocks().getFirst().blockedAt().isAfter(Instant.EPOCH));

        AccountBlockDirectoryPage reverse = assertInstanceOf(
                AccountBlockDirectoryResult.Found.class,
                directory.list(firstTarget, new AccountBlockDirectoryRequest(
                        Optional.empty(), 100))).page();
        assertEquals(List.of(actor), reverse.blocks().stream()
                .map(block -> block.targetAccountId()).toList());

        try (Connection connection = connect()) {
            execute(connection,
                    "UPDATE chat.account SET disabled_at=transaction_timestamp() WHERE id=?",
                    actor);
        }
        assertEquals(AccountBlockDirectoryResult.Rejected.NOT_AUTHORIZED,
                directory.list(actor, new AccountBlockDirectoryRequest(
                        Optional.empty(), 100)));
        assertEquals(AccountBlockDirectoryResult.Rejected.NOT_AUTHORIZED,
                directory.list(UUID.randomUUID(), new AccountBlockDirectoryRequest(
                        Optional.empty(), 100)));
    }

    @Test
    @Order(98)
    void enforcesBilateralBlocksAtDirectWriteTransactionsWithoutAffectingGroups()
            throws Exception {
        requireDatabase();
        truncateApplicationData();
        UUID actor = UUID.randomUUID();
        UUID peer = UUID.randomUUID();
        UUID contact = UUID.randomUUID();
        UUID actorDevice = UUID.randomUUID();
        UUID peerDevice = UUID.randomUUID();
        UUID direct = UUID.randomUUID();
        UUID group = UUID.randomUUID();
        UUID first = actor.toString().compareTo(peer.toString()) <= 0 ? actor : peer;
        UUID second = first.equals(actor) ? peer : actor;
        try (Connection connection = connect()) {
            execute(connection,
                    "INSERT INTO chat.account(id,username_key,display_name,password_hash) "
                            + "VALUES (?,'policy-actor','Actor',"
                            + "'$argon2id$v=19$m=65536,t=2,p=1$test$fixture'),"
                            + "(?,'policy-peer','Peer',"
                            + "'$argon2id$v=19$m=65536,t=2,p=1$test$fixture'),"
                            + "(?,'policy-contact','Contact',"
                            + "'$argon2id$v=19$m=65536,t=2,p=1$test$fixture')",
                    actor, peer, contact);
            execute(connection,
                    "INSERT INTO chat.legacy_v1_account_map(legacy_user_id,account_id) "
                            + "VALUES (1,?),(2,?),(3,?)", actor, peer, contact);
            execute(connection,
                    "INSERT INTO chat.device(id,account_id,client_device_id,platform) "
                            + "VALUES (?,?,'policy-actor-device','WEB'),"
                            + "(?,?,'policy-peer-device','WEB')",
                    actorDevice, actor, peerDevice, peer);
            execute(connection,
                    "INSERT INTO chat.conversation(id,kind) VALUES (?,'DIRECT'),(?,'GROUP')",
                    direct, group);
            execute(connection,
                    "INSERT INTO chat.direct_conversation("
                            + "conversation_id,first_account_id,second_account_id) "
                            + "VALUES (?,?,?)", direct, first, second);
            execute(connection,
                    "INSERT INTO chat.conversation_member(conversation_id,account_id) "
                            + "VALUES (?,?),(?,?),(?,?),(?,?)",
                    direct, actor, direct, peer, group, actor, group, peer);
            execute(connection,
                    "INSERT INTO chat.legacy_v1_conversation_map("
                            + "legacy_kind,legacy_conversation_id,conversation_id) "
                            + "VALUES ('FRIENDSHIP',81,?)", direct);
        }

        PostgresMessageAdapter messages = new PostgresMessageAdapter(dataSource());
        MessageSubmission v2Original = new MessageSubmission(
                direct, actor, actorDevice, "policy-v2-original", 1,
                "before block".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        MessageSubmissionResult.Accepted v2Accepted =
                (MessageSubmissionResult.Accepted) messages.submit(v2Original);
        PostgresLegacyV1DirectMessageAdapter v1Messages =
                new PostgresLegacyV1DirectMessageAdapter(dataSource());
        LegacyV1DirectMessageCommand v1Original = new LegacyV1DirectMessageCommand(
                actor, actorDevice, "policy-peer", "policy-v1-original", "before", "text");
        assertInstanceOf(LegacyV1DirectMessageResult.Accepted.class,
                v1Messages.submit(v1Original));

        PostgresLegacyV1FriendRequestCreationAdapter requests =
                new PostgresLegacyV1FriendRequestCreationAdapter(dataSource());
        assertInstanceOf(LegacyV1FriendRequestCreationResult.Accepted.class,
                requests.create(actor, "policy-contact"));
        long pendingLegacyId;
        try (Connection connection = connect();
                PreparedStatement statement = connection.prepareStatement("""
                        SELECT mapping.legacy_request_id
                        FROM chat.legacy_v1_contact_request_map mapping
                        JOIN chat.contact_request request
                          ON request.id = mapping.contact_request_id
                        WHERE request.requester_account_id = ?
                          AND request.recipient_account_id = ?
                          AND request.state = 'PENDING'
                        """)) {
            statement.setObject(1, actor);
            statement.setObject(2, contact);
            try (ResultSet row = statement.executeQuery()) {
                assertTrue(row.next());
                pendingLegacyId = row.getLong(1);
            }
        }

        AccountBlockService blocks =
                new AccountBlockService(new PostgresAccountBlockAdapter(dataSource()));
        assertInstanceOf(AccountBlockResult.Applied.class,
                blocks.apply(peer,
                        new AccountBlockIntent(actor, true, UUID.randomUUID())));
        assertTrue(((MessageSubmissionResult.Accepted) messages.submit(v2Original)).duplicate());
        assertTrue(((LegacyV1DirectMessageResult.Accepted)
                v1Messages.submit(v1Original)).duplicate());
        assertEquals(MessageSubmissionResult.Rejected.NOT_AUTHORIZED,
                messages.submit(new MessageSubmission(
                        direct, actor, actorDevice, "policy-v2-blocked", 1,
                        new byte[] {1}, Optional.of(v2Accepted.messageId()))));
        assertEquals(MessageSubmissionResult.Rejected.NOT_AUTHORIZED,
                messages.submit(new MessageSubmission(
                        direct, peer, peerDevice, "policy-v2-reverse-blocked", 1,
                        new byte[] {2})));
        assertEquals(LegacyV1DirectMessageResult.Rejected.FRIENDSHIP_ACCESS_DENIED,
                v1Messages.submit(new LegacyV1DirectMessageCommand(
                        actor, actorDevice, "policy-peer", "policy-v1-blocked",
                        "blocked", "text")));

        MessageSubmissionResult.Accepted groupMessage =
                (MessageSubmissionResult.Accepted) messages.submit(new MessageSubmission(
                        group, actor, actorDevice, "policy-group-allowed", 1,
                        "group survives".getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        PostgresMessageForwardAdapter forwards =
                new PostgresMessageForwardAdapter(dataSource());
        assertEquals(MessageForwardResult.Rejected.NOT_AUTHORIZED,
                forwards.forward(new MessageForwardCommand(
                        group, groupMessage.messageId(), 0, direct,
                        actor, actorDevice, "policy-forward-blocked")));

        assertInstanceOf(AccountBlockResult.Applied.class,
                blocks.apply(contact,
                        new AccountBlockIntent(actor, true, UUID.randomUUID())));
        assertEquals(new LegacyV1FriendRequestCreationResult.Accepted(true, contact),
                requests.create(actor, "policy-contact"));
        PostgresLegacyV1FriendRequestAcceptanceAdapter acceptance =
                new PostgresLegacyV1FriendRequestAcceptanceAdapter(dataSource());
        assertEquals(LegacyV1FriendRequestAcceptanceResult.Rejected.INSTANCE,
                acceptance.accept(pendingLegacyId, contact));
        try (Connection connection = connect()) {
            execute(connection,
                    "UPDATE chat.contact_request SET state='REJECTED',"
                            + "resolved_at=transaction_timestamp() "
                            + "WHERE requester_account_id=? AND recipient_account_id=?",
                    actor, contact);
        }
        assertEquals(LegacyV1FriendRequestCreationResult.Rejected.INVALID_TARGET,
                requests.create(actor, "policy-contact"));

        blocks.apply(peer, new AccountBlockIntent(actor, false, UUID.randomUUID()));
        blocks.apply(contact, new AccountBlockIntent(actor, false, UUID.randomUUID()));
        assertInstanceOf(MessageSubmissionResult.Accepted.class,
                messages.submit(new MessageSubmission(
                        direct, actor, actorDevice, "policy-v2-unblocked", 1,
                        new byte[] {3})));
        assertEquals(new LegacyV1FriendRequestCreationResult.Accepted(false, contact),
                requests.create(actor, "policy-contact"));

        ExecutorService serializedWrite = Executors.newSingleThreadExecutor();
        try (Connection blocker = connect()) {
            blocker.setAutoCommit(false);
            try (PreparedStatement statement = blocker.prepareStatement(
                    "SELECT id FROM chat.account WHERE id IN (?,?) ORDER BY id FOR UPDATE")) {
                statement.setObject(1, actor);
                statement.setObject(2, peer);
                try (ResultSet rows = statement.executeQuery()) {
                    assertTrue(rows.next());
                    assertTrue(rows.next());
                }
            }
            execute(blocker,
                    "INSERT INTO chat.account_block(blocker_account_id,blocked_account_id) "
                            + "VALUES (?,?)", peer, actor);
            Future<MessageSubmissionResult> waitingWrite = serializedWrite.submit(
                    () -> messages.submit(new MessageSubmission(
                            direct, actor, actorDevice, "policy-race-blocked", 1,
                            new byte[] {4})));
            assertThrows(java.util.concurrent.TimeoutException.class,
                    () -> waitingWrite.get(200, TimeUnit.MILLISECONDS));
            blocker.commit();
            assertEquals(MessageSubmissionResult.Rejected.NOT_AUTHORIZED,
                    waitingWrite.get(5, TimeUnit.SECONDS));
        } finally {
            serializedWrite.shutdownNow();
        }
        assertEquals(1, count("SELECT count(*) FROM chat.message WHERE conversation_id='"
                + group + "' AND client_message_id='policy-group-allowed'"));
    }

    @Test
    @Order(99)
    void atomicallyImportsAttachmentMessageMappingsCursorAndProofsIdempotently()
            throws Exception {
        requireDatabase();
        truncateApplicationData();
        VerifiedV1UnifiedMessageImportBundle bundle =
                V1UnifiedAttachmentImportFixture.create(temporary);
        UUID account = V1IdentityImportPlanner.deterministicUserId(1);
        UUID conversation = V1ConversationImportPlanner.deterministicRoomId(9);
        seedV1AttachmentTarget(account, conversation);
        PostgresV1MessageImporter importer = new PostgresV1MessageImporter(dataSource());

        V1MessageImportReport first = importer.apply(bundle);
        V1MessageImportReport second = importer.apply(bundle);

        assertTrue(first.applied() && first.reconciled());
        assertEquals(1, first.insertableMessages());
        assertEquals(0, second.insertableMessages());
        assertEquals(1, second.alreadyImportedMessages());
        assertEquals(1, count("SELECT count(*) FROM chat.attachment"));
        assertEquals(1, count("SELECT count(*) FROM chat.message WHERE message_type = 2 "
                + "AND octet_length(payload) = 0 AND attachment_id IS NOT NULL"));
        assertEquals(1, count("SELECT count(*) FROM chat.legacy_v1_attachment_map"));
        assertEquals(1, count("SELECT count(*) FROM chat.legacy_v1_message_map"));
        assertEquals(2, count("SELECT count(*) FROM chat.message_import_run"));
        assertEquals(2, count("SELECT count(*) FROM chat.attachment_import_run"));
        assertEquals(1, count("SELECT count(*) FROM chat.conversation "
                + "WHERE next_sequence = 2"));

        try (Connection connection = connect()) {
            execute(connection, "UPDATE chat.attachment SET file_name = 'drift.pdf'");
        }
        int proofsBefore = count("SELECT count(*) FROM chat.attachment_import_run");
        assertThrows(V1MessageImportException.class, () -> importer.apply(bundle));
        assertEquals(proofsBefore, count("SELECT count(*) FROM chat.attachment_import_run"));
        assertEquals(1, count("SELECT count(*) FROM chat.message"));
    }

    @Test
    @Order(100)
    void listsOnlyCompleteReadyV1RoomFilesForActiveAdministrators() throws Exception {
        requireDatabase();
        truncateApplicationData();
        VerifiedV1UnifiedMessageImportBundle bundle =
                V1UnifiedAttachmentImportFixture.create(temporary);
        UUID account = V1IdentityImportPlanner.deterministicUserId(1);
        UUID conversation = V1ConversationImportPlanner.deterministicRoomId(9);
        seedV1AttachmentTarget(account, conversation);
        try (Connection connection = connect()) {
            execute(connection,
                    "UPDATE chat.conversation_member SET role = 'ADMIN' "
                            + "WHERE conversation_id = ? AND account_id = ?",
                    conversation, account);
            execute(connection,
                    "INSERT INTO chat.group_lifecycle(conversation_id) VALUES (?) "
                            + "ON CONFLICT DO NOTHING",
                    conversation);
            execute(connection,
                    "INSERT INTO chat.group_resource_policy(conversation_id, max_file_size, "
                            + "total_file_space, max_file_count) VALUES (?, 1024, 4096, 42) "
                            + "ON CONFLICT (conversation_id) DO UPDATE SET "
                            + "max_file_size = EXCLUDED.max_file_size, "
                            + "total_file_space = EXCLUDED.total_file_space, "
                            + "max_file_count = EXCLUDED.max_file_count",
                    conversation);
            execute(connection,
                    "INSERT INTO chat.legacy_v1_account_map(legacy_user_id, account_id) "
                            + "VALUES (1, ?)", account);
        }
        new PostgresV1MessageImporter(dataSource()).apply(bundle);
        PostgresLegacyV1RoomFilesAdapter adapter =
                new PostgresLegacyV1RoomFilesAdapter(dataSource());

        var authorized = (LegacyV1RoomFilesPort.QueryResult.Authorized)
                adapter.read(account, 9);

        assertEquals(1, authorized.files().files().size());
        assertEquals(7, authorized.files().files().getFirst().legacyFileId());
        assertEquals("report.pdf", authorized.files().files().getFirst().fileName());
        assertEquals(123, authorized.files().usedFileSpace());
        assertEquals(4096, authorized.files().maxFileSpace());

        try (Connection connection = connect()) {
            execute(connection,
                    "UPDATE chat.conversation_member SET role = 'MEMBER' "
                            + "WHERE conversation_id = ? AND account_id = ?",
                    conversation, account);
        }
        assertEquals(LegacyV1RoomFilesPort.QueryResult.Rejected.ROOM_ADMIN_REQUIRED,
                adapter.read(account, 9));
        try (Connection connection = connect()) {
            execute(connection,
                    "UPDATE chat.conversation_member SET role = 'ADMIN' "
                            + "WHERE conversation_id = ? AND account_id = ?",
                    conversation, account);
            execute(connection,
                    "UPDATE chat.attachment SET state = 'REVOKED', "
                            + "revoked_at = transaction_timestamp() "
                            + "WHERE conversation_id = ?", conversation);
        }
        var empty = (LegacyV1RoomFilesPort.QueryResult.Authorized) adapter.read(account, 9);
        assertTrue(empty.files().files().isEmpty());
        assertEquals(0, empty.files().usedFileSpace());
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
        assertEquals(1, count("SELECT count(*) FROM chat.conversation_event_outbox"));
        assertEquals(1, count("SELECT count(*) FROM chat.conversation_event_outbox o "
                + "JOIN chat.message m ON m.id = o.event_id "
                + "AND m.conversation_id = o.conversation_id "
                + "AND m.conversation_sequence = o.conversation_sequence"));

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
        assertEquals(2, count("SELECT count(*) FROM chat.conversation_event_outbox"));
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

        MessageSubmissionResult.Accepted reply =
                (MessageSubmissionResult.Accepted) adapter.submit(new MessageSubmission(
                        conversation, account, device, "client-reply", 100,
                        new byte[] {5}, Optional.of(raced.getFirst().messageId())));
        assertEquals(3, reply.conversationSequence());
        assertEquals(raced.getFirst().messageId(),
                reply.reply().orElseThrow().targetMessageId());
        MessageSubmissionResult.Accepted duplicateReply =
                (MessageSubmissionResult.Accepted) adapter.submit(new MessageSubmission(
                        conversation, account, device, "client-reply", 100,
                        new byte[] {5}, Optional.of(raced.getFirst().messageId())));
        assertTrue(duplicateReply.duplicate());
        assertEquals(3, count("SELECT count(*) FROM chat.conversation_event_outbox"));
        assertEquals(reply.reply(), duplicateReply.reply());
        assertEquals(MessageSubmissionResult.Rejected.IDEMPOTENCY_CONFLICT,
                adapter.submit(new MessageSubmission(
                        conversation, account, device, "client-reply", 100,
                        new byte[] {5}, Optional.of(second.messageId()))));
        assertEquals(MessageSubmissionResult.Rejected.NOT_AUTHORIZED,
                adapter.submit(new MessageSubmission(
                        conversation, account, device, "missing-reply", 100,
                        new byte[] {5}, Optional.of(UUID.randomUUID()))));
        MessageHistoryResult.Page replyPage = (MessageHistoryResult.Page) adapter.readAfter(
                new MessageHistoryQuery(conversation, account, 2, 10));
        assertEquals(reply.reply(), replyPage.messages().getFirst().reply());
        try (Connection connection = connect()) {
            assertThrows(SQLException.class, () -> execute(connection,
                    "UPDATE chat.message_reply_reference "
                            + "SET target_conversation_sequence = 2 WHERE message_id = ?",
                    reply.messageId()));
        }
        UUID mentionTarget = UUID.randomUUID();
        seedMentionTarget(mentionTarget, conversation);
        byte[] mentionedBody = "@李 hi".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        List<MessageMention> mentions = List.of(new MessageMention(mentionTarget, 0, 4));
        MessageSubmission mentioned = new MessageSubmission(
                conversation, account, device, "client-mentioned", 1, mentionedBody,
                Optional.empty(), mentions);
        MessageSubmissionResult.Accepted mentionedAccepted =
                (MessageSubmissionResult.Accepted) adapter.submit(mentioned);
        assertEquals(4, mentionedAccepted.conversationSequence());
        assertTrue(((MessageSubmissionResult.Accepted) adapter.submit(mentioned)).duplicate());
        assertEquals(4, count("SELECT count(*) FROM chat.conversation_event_outbox"));
        assertEquals(MessageSubmissionResult.Rejected.IDEMPOTENCY_CONFLICT,
                adapter.submit(new MessageSubmission(
                        conversation, account, device, "client-mentioned", 1,
                        mentionedBody, Optional.empty(), List.of())));
        MessageHistoryResult.Page mentionedPage = (MessageHistoryResult.Page) adapter.readAfter(
                new MessageHistoryQuery(conversation, account, 3, 10));
        assertEquals(mentions, mentionedPage.messages().getFirst().mentions());
        assertEquals(1, count("SELECT count(*) FROM chat.message_mention"));
        try (Connection connection = connect()) {
            execute(connection,
                    "UPDATE chat.conversation SET next_sequence = 6 WHERE id = ?",
                    conversation);
            execute(connection,
                    "INSERT INTO chat.conversation_entry(conversation_id, "
                            + "conversation_sequence, entry_kind, occurred_at) "
                            + "VALUES (?, 5, 'MESSAGE_RECALLED', transaction_timestamp())",
                    conversation);
            execute(connection,
                    "INSERT INTO chat.message_recall_event(conversation_id, "
                            + "conversation_sequence, message_id, actor_account_id, source) "
                            + "VALUES (?, 5, ?, ?, 'V2')",
                    conversation, mentionedAccepted.messageId(), account);
        }
        assertEquals(0, count("SELECT count(*) FROM chat.message_mention"));
        assertEquals(4, count("SELECT count(*) FROM chat.conversation_event_outbox"));

        UUID blockedEventId = UUID.randomUUID();
        try (Connection connection = connect()) {
            execute(connection,
                    "INSERT INTO chat.conversation_event_outbox("
                            + "event_id, conversation_id, conversation_sequence) "
                            + "VALUES (?, ?, 5)",
                    blockedEventId, conversation);
        }
        PostgresMessageAdapter conflictingOutbox =
                new PostgresMessageAdapter(dataSource(), () -> blockedEventId);
        assertThrows(MessagePersistenceException.class,
                () -> conflictingOutbox.submit(new MessageSubmission(
                        conversation, account, device, "outbox-must-rollback", 100,
                        new byte[] {7})));
        assertEquals(4, conversationEntryCount(conversation));
        assertEquals(5, allConversationEntryCount(conversation));
        assertEquals(5, count("SELECT count(*) FROM chat.conversation_event_outbox"));
        assertEquals(0, count("SELECT count(*) FROM chat.message "
                + "WHERE client_message_id = 'outbox-must-rollback'"));

        MessageSubmissionResult.Accepted afterOutboxFailure =
                (MessageSubmissionResult.Accepted) adapter.submit(new MessageSubmission(
                        conversation, account, device, "after-outbox-rollback", 100,
                        new byte[] {8}));
        assertEquals(6, afterOutboxFailure.conversationSequence());
        assertEquals(6, count("SELECT count(*) FROM chat.conversation_event_outbox"));

        UUID outsider = UUID.randomUUID();
        seedAccount(outsider, "mention-outsider");
        assertEquals(MessageSubmissionResult.Rejected.NOT_AUTHORIZED,
                adapter.submit(new MessageSubmission(
                        conversation, account, device, "client-bad-mention", 1,
                        mentionedBody, Optional.empty(),
                        List.of(new MessageMention(outsider, 0, 4)))));
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
    void forwardsCurrentServerTextWithIndependentTargetIdempotency() throws Exception {
        requireDatabase();
        truncateApplicationData();
        UUID account = UUID.randomUUID();
        UUID device = UUID.randomUUID();
        UUID sourceConversation = UUID.randomUUID();
        UUID targetConversation = UUID.randomUUID();
        seedMessageOwner(account, device, sourceConversation);
        try (Connection connection = connect()) {
            execute(connection, "INSERT INTO chat.conversation(id,kind) VALUES (?,'GROUP')",
                    targetConversation);
            execute(connection, "INSERT INTO chat.conversation_member("
                            + "conversation_id,account_id) VALUES (?,?)",
                    targetConversation, account);
        }
        PostgresMessageAdapter messages = new PostgresMessageAdapter(dataSource());
        MessageSubmissionResult.Accepted source = (MessageSubmissionResult.Accepted)
                messages.submit(new MessageSubmission(
                        sourceConversation, account, device, "forward-source", 1,
                        "server truth".getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        PostgresMessageForwardAdapter forwards = new PostgresMessageForwardAdapter(dataSource());
        MessageForwardCommand command = new MessageForwardCommand(
                sourceConversation, source.messageId(), 0, targetConversation,
                account, device, "forward-target-1");

        MessageForwardResult.Accepted accepted =
                (MessageForwardResult.Accepted) forwards.forward(command);
        assertFalse(accepted.duplicate());
        assertTrue(accepted.message().forwarded());
        assertEquals("server truth", new String(accepted.message().payload(),
                java.nio.charset.StandardCharsets.UTF_8));
        assertTrue(accepted.message().reply().isEmpty());
        assertTrue(accepted.message().mentions().isEmpty());
        MessageForwardResult.Accepted duplicate =
                (MessageForwardResult.Accepted) forwards.forward(command);
        assertTrue(duplicate.duplicate());
        assertEquals(accepted.message().messageId(), duplicate.message().messageId());
        assertEquals(MessageForwardResult.Rejected.IDEMPOTENCY_CONFLICT,
                forwards.forward(new MessageForwardCommand(
                        sourceConversation, source.messageId(), 0, sourceConversation,
                        account, device, "forward-target-1")));
        assertEquals(MessageForwardResult.Rejected.SOURCE_REVISION_CONFLICT,
                forwards.forward(new MessageForwardCommand(
                        sourceConversation, source.messageId(), 1, targetConversation,
                        account, device, "forward-target-2")));
        assertEquals(MessageForwardResult.Rejected.NOT_AUTHORIZED,
                forwards.forward(new MessageForwardCommand(
                        sourceConversation, UUID.randomUUID(), 0, targetConversation,
                        account, device, "forward-missing")));

        MessageHistoryResult.Page history = (MessageHistoryResult.Page) messages.readAfter(
                new MessageHistoryQuery(targetConversation, account, 0, 10));
        assertEquals(1, history.messages().size());
        assertTrue(history.messages().getFirst().forwarded());
        assertEquals(1, count("SELECT count(*) FROM chat.message_forward_request"));

        try (Connection connection = connect()) {
            execute(connection,
                    "UPDATE chat.conversation SET next_sequence = 3 WHERE id = ?",
                    sourceConversation);
            execute(connection,
                    "INSERT INTO chat.conversation_entry(conversation_id,"
                            + "conversation_sequence,entry_kind,occurred_at) "
                            + "VALUES (?,2,'MESSAGE_RECALLED',transaction_timestamp())",
                    sourceConversation);
            execute(connection,
                    "INSERT INTO chat.message_recall_event(conversation_id,"
                            + "conversation_sequence,message_id,actor_account_id,source) "
                            + "VALUES (?,2,?,?,'V2')",
                    sourceConversation, source.messageId(), account);
        }
        assertEquals(MessageForwardResult.Rejected.NOT_AUTHORIZED,
                forwards.forward(new MessageForwardCommand(
                        sourceConversation, source.messageId(), 0, targetConversation,
                        account, device, "forward-recalled")));
        MessageSubmissionResult.Accepted secondSource = (MessageSubmissionResult.Accepted)
                messages.submit(new MessageSubmission(
                        sourceConversation, account, device, "forward-source-2", 1,
                        "still readable".getBytes(
                                java.nio.charset.StandardCharsets.UTF_8)));
        leaveConversation(targetConversation, account);
        assertEquals(MessageForwardResult.Rejected.NOT_AUTHORIZED,
                forwards.forward(new MessageForwardCommand(
                        sourceConversation, secondSource.messageId(), 0, targetConversation,
                        account, device, "forward-left-target")));
    }

    @Test
    @Order(13)
    void appliesMessageReactionsIdempotentlyWithChangedOnlyOrdering() throws Exception {
        requireDatabase();
        truncateApplicationData();
        UUID account = UUID.randomUUID();
        UUID device = UUID.randomUUID();
        UUID conversation = UUID.randomUUID();
        seedMessageOwner(account, device, conversation);
        PostgresMessageAdapter messages = new PostgresMessageAdapter(dataSource());
        MessageSubmissionResult.Accepted target =
                (MessageSubmissionResult.Accepted) messages.submit(new MessageSubmission(
                        conversation, account, device, "reaction-target", 100,
                        new byte[] {1}));
        PostgresMessageReactionAdapter reactions =
                new PostgresMessageReactionAdapter(dataSource());
        MessageReactionCommand add = new MessageReactionCommand(
                conversation, target.messageId(), account, device,
                MessageReactionKind.LOVE, true, "reaction-add");

        List<MessageReactionResult.Applied> raced = raceReaction(reactions, add);

        assertEquals(2, raced.size());
        assertEquals(1, raced.stream().filter(result -> !result.duplicate()).count());
        assertEquals(1, raced.stream().filter(MessageReactionResult.Applied::duplicate).count());
        assertTrue(raced.stream().allMatch(MessageReactionResult.Applied::changed));
        assertTrue(raced.stream().allMatch(result -> result.conversationSequence() == 2));
        assertEquals(raced.getFirst().occurredAt(), raced.getLast().occurredAt());
        assertEquals(1, count("SELECT count(*) FROM chat.message_reaction"));
        assertEquals(1, count("SELECT count(*) FROM chat.message_reaction_event"));
        assertEquals(1, count("SELECT count(*) FROM chat.message_reaction_operation"));

        MessageReactionCommand noOp = new MessageReactionCommand(
                conversation, target.messageId(), account, device,
                MessageReactionKind.LOVE, true, "reaction-no-op");
        MessageReactionResult.Applied unchanged =
                (MessageReactionResult.Applied) reactions.set(noOp);
        assertFalse(unchanged.changed());
        assertEquals(0, unchanged.conversationSequence());
        MessageReactionResult.Applied duplicateNoOp =
                (MessageReactionResult.Applied) reactions.set(noOp);
        assertTrue(duplicateNoOp.duplicate());
        assertEquals(unchanged.occurredAt(), duplicateNoOp.occurredAt());
        assertEquals(MessageReactionResult.Rejected.IDEMPOTENCY_CONFLICT,
                reactions.set(new MessageReactionCommand(
                        conversation, target.messageId(), account, device,
                        MessageReactionKind.LOVE, false, "reaction-no-op")));
        assertEquals(1, count("SELECT count(*) FROM chat.message_reaction_event"));

        MessageReactionResult.Applied removed =
                (MessageReactionResult.Applied) reactions.set(new MessageReactionCommand(
                        conversation, target.messageId(), account, device,
                        MessageReactionKind.LOVE, false, "reaction-remove"));
        assertTrue(removed.changed());
        assertEquals(3, removed.conversationSequence());
        assertEquals(0, count("SELECT count(*) FROM chat.message_reaction"));
        assertEquals(2, count("SELECT count(*) FROM chat.message_reaction_event"));
        assertEquals(3, count("SELECT count(*) FROM chat.message_reaction_operation"));

        ConversationEntryHistoryResult.Page history =
                (ConversationEntryHistoryResult.Page) messages.readEntriesAfter(
                        new MessageHistoryQuery(conversation, account, 1, 10));
        assertEquals(List.of(2L, 3L), history.entries().stream()
                .map(ConversationHistoryEntry::conversationSequence).toList());
        ConversationHistoryEntry.Reaction added =
                (ConversationHistoryEntry.Reaction) history.entries().getFirst();
        ConversationHistoryEntry.Reaction removedEntry =
                (ConversationHistoryEntry.Reaction) history.entries().getLast();
        assertEquals(MessageReactionKind.LOVE, added.reaction());
        assertTrue(added.active());
        assertFalse(removedEntry.active());
        assertEquals(target.messageId(), removedEntry.messageId());
        assertEquals(3, history.nextSequence());
        assertEquals(3, history.latestSequence());

        assertEquals(MessageReactionResult.Rejected.NOT_AUTHORIZED,
                reactions.set(new MessageReactionCommand(
                        conversation, UUID.randomUUID(), account, device,
                        MessageReactionKind.LIKE, true, "missing-target")));
        assertEquals(MessageReactionResult.Rejected.NOT_AUTHORIZED,
                reactions.set(new MessageReactionCommand(
                        conversation, target.messageId(), UUID.randomUUID(), UUID.randomUUID(),
                        MessageReactionKind.LIKE, true, "outsider")));

        try (Connection connection = connect()) {
            execute(connection, "DELETE FROM chat.message WHERE id = ?", target.messageId());
        }
        assertEquals(0, count("SELECT count(*) FROM chat.message_reaction_event"));
        assertEquals(1, allConversationEntryCount(conversation));
        assertEquals(3, count("SELECT count(*) FROM chat.message_reaction_operation"));
    }

    @Test
    @Order(14)
    void appliesSharedMessagePinsIdempotentlyWithChangedOnlyOrdering() throws Exception {
        requireDatabase();
        truncateApplicationData();
        UUID account = UUID.randomUUID();
        UUID device = UUID.randomUUID();
        UUID conversation = UUID.randomUUID();
        seedMessageOwner(account, device, conversation);
        PostgresMessageAdapter messages = new PostgresMessageAdapter(dataSource());
        MessageSubmissionResult.Accepted target =
                (MessageSubmissionResult.Accepted) messages.submit(new MessageSubmission(
                        conversation, account, device, "pin-target", 100, new byte[] {1}));
        PostgresMessagePinAdapter pins = new PostgresMessagePinAdapter(dataSource());
        MessagePinCommand add = new MessagePinCommand(
                conversation, target.messageId(), account, device, true, "pin-add");

        MessagePinResult.Applied added = (MessagePinResult.Applied) pins.set(add);
        assertTrue(added.changed());
        assertEquals(2, added.conversationSequence());
        MessagePinResult.Applied duplicate = (MessagePinResult.Applied) pins.set(add);
        assertTrue(duplicate.duplicate());
        assertEquals(added.occurredAt(), duplicate.occurredAt());
        assertEquals(MessagePinResult.Rejected.IDEMPOTENCY_CONFLICT,
                pins.set(new MessagePinCommand(conversation, target.messageId(), account, device,
                        false, "pin-add")));

        MessagePinResult.Applied noOp = (MessagePinResult.Applied) pins.set(
                new MessagePinCommand(conversation, target.messageId(), account, device,
                        true, "pin-no-op"));
        assertFalse(noOp.changed());
        assertEquals(0, noOp.conversationSequence());
        MessagePinResult.Applied removed = (MessagePinResult.Applied) pins.set(
                new MessagePinCommand(conversation, target.messageId(), account, device,
                        false, "pin-remove"));
        assertTrue(removed.changed());
        assertEquals(3, removed.conversationSequence());

        ConversationEntryHistoryResult.Page history =
                (ConversationEntryHistoryResult.Page) messages.readEntriesAfter(
                        new MessageHistoryQuery(conversation, account, 1, 10));
        assertEquals(List.of(2L, 3L), history.entries().stream()
                .map(ConversationHistoryEntry::conversationSequence).toList());
        ConversationHistoryEntry.Pin first =
                (ConversationHistoryEntry.Pin) history.entries().getFirst();
        ConversationHistoryEntry.Pin last =
                (ConversationHistoryEntry.Pin) history.entries().getLast();
        assertTrue(first.pinned());
        assertFalse(last.pinned());
        assertEquals(target.messageId(), last.messageId());
        assertEquals(0, count("SELECT count(*) FROM chat.message_pin"));
        assertEquals(2, count("SELECT count(*) FROM chat.message_pin_event"));
        assertEquals(3, count("SELECT count(*) FROM chat.message_pin_operation"));

        assertEquals(MessagePinResult.Rejected.NOT_AUTHORIZED,
                pins.set(new MessagePinCommand(conversation, target.messageId(),
                        UUID.randomUUID(), UUID.randomUUID(), true, "pin-outsider")));

        MessagePinResult.Applied readded = (MessagePinResult.Applied) pins.set(
                new MessagePinCommand(conversation, target.messageId(), account, device,
                        true, "pin-readd"));
        assertEquals(4, readded.conversationSequence());
        try (Connection connection = connect()) {
            execute(connection, "UPDATE chat.conversation SET next_sequence = 6 WHERE id = ?",
                    conversation);
            execute(connection,
                    "INSERT INTO chat.conversation_entry(conversation_id, "
                            + "conversation_sequence, entry_kind, occurred_at) "
                            + "VALUES (?, 5, 'MESSAGE_RECALLED', transaction_timestamp())",
                    conversation);
            execute(connection,
                    "INSERT INTO chat.message_recall_event(conversation_id, "
                            + "conversation_sequence, message_id, actor_account_id, source) "
                            + "VALUES (?, 5, ?, ?, 'V2')",
                    conversation, target.messageId(), account);
        }
        assertEquals(0, count("SELECT count(*) FROM chat.message_pin"));
        assertEquals(4, count("SELECT count(*) FROM chat.message_pin_event"));
        ConversationEntryHistoryResult.Page cleanupHistory =
                (ConversationEntryHistoryResult.Page) messages.readEntriesAfter(
                        new MessageHistoryQuery(conversation, account, 5, 10));
        ConversationHistoryEntry.Pin automatic =
                (ConversationHistoryEntry.Pin) cleanupHistory.entries().getFirst();
        assertEquals(6, automatic.conversationSequence());
        assertFalse(automatic.pinned());
        assertTrue(automatic.clientOperationId().startsWith("AUTO_RECALL:"));
    }

    @Test
    @Order(15)
    void editsV2TextWithSerializedRevisionPolicyAndPrivacyCleanup() throws Exception {
        requireDatabase();
        truncateApplicationData();
        UUID account = UUID.randomUUID();
        UUID device = UUID.randomUUID();
        UUID conversation = UUID.randomUUID();
        seedMessageOwner(account, device, conversation);
        UUID mentionTarget = UUID.randomUUID();
        seedMentionTarget(mentionTarget, conversation);
        PostgresMessageAdapter messages = new PostgresMessageAdapter(dataSource());
        MessageSubmissionResult.Accepted target =
                (MessageSubmissionResult.Accepted) messages.submit(new MessageSubmission(
                        conversation, account, device, "edit-target", 1,
                        "original".getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        PostgresMessageEditAdapter edits = new PostgresMessageEditAdapter(dataSource());
        byte[] mentionedEditBody = "@李 hi".getBytes(
                java.nio.charset.StandardCharsets.UTF_8);
        List<MessageMention> editMentions = List.of(
                new MessageMention(mentionTarget, 0, 4));
        MessageEditCommand first = new MessageEditCommand(
                conversation, target.messageId(), account, device, 0, 1,
                mentionedEditBody, "edit-first", editMentions);

        List<MessageEditResult.Applied> raced = raceEdit(edits, first);
        assertEquals(1, raced.stream().filter(result -> !result.duplicate()).count());
        assertEquals(1, raced.stream().filter(MessageEditResult.Applied::duplicate).count());
        assertTrue(raced.stream().allMatch(result -> result.contentRevision() == 1));
        assertTrue(raced.stream().allMatch(result -> result.conversationSequence() == 2));
        assertEquals(1, count("SELECT count(*) FROM chat.message_edit_event"));
        assertEquals(1, count("SELECT count(*) FROM chat.message_edit_event_mention"));
        assertEquals(1, count("SELECT count(*) FROM chat.message_edit_operation"));
        MessageHistoryResult.Page current = (MessageHistoryResult.Page) messages.readAfter(
                new MessageHistoryQuery(conversation, account, 0, 10));
        assertEquals(1, current.messages().size());
        assertEquals(1, current.messages().getFirst().contentRevision());
        assertTrue(current.messages().getFirst().editedAt().isPresent());
        assertEquals("@李 hi", new String(current.messages().getFirst().payload(),
                java.nio.charset.StandardCharsets.UTF_8));
        assertEquals(editMentions, current.messages().getFirst().mentions());
        ConversationEntryHistoryResult.Page initialEdits =
                (ConversationEntryHistoryResult.Page) messages.readEntriesAfter(
                        new MessageHistoryQuery(conversation, account, 1, 10));
        ConversationHistoryEntry.Edit initialEdit =
                (ConversationHistoryEntry.Edit) initialEdits.entries().getFirst();
        assertEquals(2, initialEdit.conversationSequence());
        assertEquals(1, initialEdit.contentRevision());
        assertFalse(initialEdit.contentErased());
        assertEquals("@李 hi", new String(initialEdit.content(),
                java.nio.charset.StandardCharsets.UTF_8));
        assertEquals(editMentions, initialEdit.mentions());

        MessageEditResult.Applied noOp = (MessageEditResult.Applied) edits.edit(
                new MessageEditCommand(conversation, target.messageId(), account, device, 1, 1,
                        mentionedEditBody, "edit-no-op", editMentions));
        assertFalse(noOp.changed());
        assertEquals(1, noOp.contentRevision());
        assertEquals(0, noOp.conversationSequence());
        assertEquals(MessageEditResult.Rejected.STALE_REVISION, edits.edit(
                new MessageEditCommand(conversation, target.messageId(), account, device, 0, 1,
                        "stale".getBytes(java.nio.charset.StandardCharsets.UTF_8), "edit-stale")));
        assertEquals(MessageEditResult.Rejected.STALE_REVISION, edits.edit(
                new MessageEditCommand(conversation, target.messageId(), account, device, 0, 1,
                        "stale".getBytes(java.nio.charset.StandardCharsets.UTF_8), "edit-stale")));
        assertEquals(MessageEditResult.Rejected.IDEMPOTENCY_CONFLICT, edits.edit(
                new MessageEditCommand(conversation, target.messageId(), account, device, 0, 1,
                        "changed-key".getBytes(java.nio.charset.StandardCharsets.UTF_8),
                        "edit-stale")));
        assertEquals(MessageEditResult.Rejected.IDEMPOTENCY_CONFLICT, edits.edit(
                new MessageEditCommand(conversation, target.messageId(), account, device, 0, 1,
                        mentionedEditBody, "edit-stale",
                        editMentions)));
        UUID outsiderMention = UUID.randomUUID();
        seedAccount(outsiderMention, "edit-mention-outsider");
        assertEquals(MessageEditResult.Rejected.NOT_AUTHORIZED, edits.edit(
                new MessageEditCommand(conversation, target.messageId(), account, device, 1, 1,
                        mentionedEditBody, "edit-bad-mention",
                        List.of(new MessageMention(outsiderMention, 0, 4)))));
        assertEquals(MessageEditResult.Rejected.NOT_AUTHORIZED, edits.edit(
                new MessageEditCommand(conversation, target.messageId(), UUID.randomUUID(),
                        UUID.randomUUID(), 1, 1, new byte[] {1}, "edit-outsider")));

        MessageSubmissionResult.Accepted expired =
                (MessageSubmissionResult.Accepted) messages.submit(new MessageSubmission(
                        conversation, account, device, "edit-expired", 1, new byte[] {1}));
        MessageSubmissionResult.Accepted limited =
                (MessageSubmissionResult.Accepted) messages.submit(new MessageSubmission(
                        conversation, account, device, "edit-limited", 1, new byte[] {1}));
        MessageSubmissionResult.Accepted legacy =
                (MessageSubmissionResult.Accepted) messages.submit(new MessageSubmission(
                        conversation, account, device, "edit-legacy", 1, new byte[] {1}));
        try (Connection connection = connect()) {
            execute(connection, "UPDATE chat.message SET accepted_at="
                    + "transaction_timestamp()-interval '16 minutes' WHERE id=?",
                    expired.messageId());
            execute(connection, "UPDATE chat.message SET content_revision=100,"
                    + "edited_at=transaction_timestamp() WHERE id=?", limited.messageId());
            execute(connection, "INSERT INTO chat.legacy_v1_conversation_map(legacy_kind,"
                    + "legacy_conversation_id,conversation_id) VALUES ('FRIENDSHIP',919,?)",
                    conversation);
            execute(connection, "INSERT INTO chat.legacy_v1_message_map(legacy_kind,"
                    + "legacy_message_id,legacy_conversation_id,conversation_id,message_id) "
                    + "VALUES ('FRIENDSHIP',1919,919,?,?)", conversation, legacy.messageId());
        }
        assertEquals(MessageEditResult.Rejected.WINDOW_EXPIRED, edits.edit(
                new MessageEditCommand(conversation, expired.messageId(), account, device, 0, 1,
                        new byte[] {2}, "edit-expired")));
        assertEquals(MessageEditResult.Rejected.REVISION_LIMIT, edits.edit(
                new MessageEditCommand(conversation, limited.messageId(), account, device, 100, 1,
                        new byte[] {2}, "edit-limited")));
        assertEquals(MessageEditResult.Rejected.NOT_AUTHORIZED, edits.edit(
                new MessageEditCommand(conversation, legacy.messageId(), account, device, 0, 1,
                        new byte[] {2}, "edit-legacy")));

        MessageSubmissionResult.Accepted deleted =
                (MessageSubmissionResult.Accepted) messages.submit(new MessageSubmission(
                        conversation, account, device, "edit-delete", 1, new byte[] {1}));
        MessageEditResult.Applied deletedEdit = (MessageEditResult.Applied) edits.edit(
                new MessageEditCommand(conversation, deleted.messageId(), account, device, 0, 1,
                        new byte[] {2}, "edit-delete-first"));
        try (Connection connection = connect()) {
            long recallSequence = deletedEdit.conversationSequence() + 1;
            long deletionSequence = recallSequence + 1;
            execute(connection, "UPDATE chat.conversation SET next_sequence=? WHERE id=?",
                    deletionSequence + 1, conversation);
            execute(connection, "INSERT INTO chat.conversation_entry VALUES "
                    + "(?,?,'MESSAGE_RECALLED',transaction_timestamp())",
                    conversation, recallSequence);
            execute(connection, "INSERT INTO chat.message_recall_event(conversation_id,"
                    + "conversation_sequence,message_id,actor_account_id,source) "
                    + "VALUES (?,?,?,?,'V2')", conversation, recallSequence,
                    target.messageId(), account);
            execute(connection, "INSERT INTO chat.conversation_entry VALUES "
                    + "(?,?,'MESSAGES_DELETED',transaction_timestamp())",
                    conversation, deletionSequence);
            execute(connection, "INSERT INTO chat.messages_deleted_event(conversation_id,"
                    + "conversation_sequence,actor_account_id,source,mode,client_operation_id,"
                    + "command_fingerprint,message_ids,deleted_count) VALUES "
                    + "(?,?,?,'V2','MESSAGE_IDS','edit-delete-cleanup','fixture',"
                    + "?::jsonb,1)", conversation, deletionSequence, account,
                    "[\"" + deleted.messageId() + "\"]");
        }
        assertEquals(0, count("SELECT count(*) FROM chat.message_edit_event "
                + "WHERE content IS NOT NULL AND message_id IN ('" + target.messageId()
                + "','" + deleted.messageId() + "')"));
        assertEquals(2, count("SELECT count(*) FROM chat.message_edit_event "
                + "WHERE content IS NULL AND content_erased_at IS NOT NULL"));
        assertEquals(0, count("SELECT count(*) FROM chat.message_edit_event_mention"));
        ConversationEntryHistoryResult.Page erasedHistory =
                (ConversationEntryHistoryResult.Page) messages.readEntriesAfter(
                        new MessageHistoryQuery(conversation, account, 1, 100));
        assertEquals(erasedHistory.latestSequence(), erasedHistory.nextSequence());
        assertEquals(2, erasedHistory.entries().stream()
                .filter(ConversationHistoryEntry.Edit.class::isInstance)
                .map(ConversationHistoryEntry.Edit.class::cast)
                .filter(ConversationHistoryEntry.Edit::contentErased)
                .count());
    }

    @Test
    @Order(16)
    void searchesCurrentTextWithAuthorizationMutationExclusionAndDescendingCursor()
            throws Exception {
        requireDatabase();
        truncateApplicationData();
        UUID account = UUID.randomUUID();
        UUID device = UUID.randomUUID();
        UUID conversation = UUID.randomUUID();
        seedMessageOwner(account, device, conversation);
        PostgresMessageAdapter messages = new PostgresMessageAdapter(dataSource());
        MessageSubmissionResult.Accepted older = accepted(messages.submit(new MessageSubmission(
                conversation, account, device, "search-older", 1,
                "聊天 older".getBytes(java.nio.charset.StandardCharsets.UTF_8))));
        MessageSubmissionResult.Accepted editable = accepted(messages.submit(
                new MessageSubmission(conversation, account, device, "search-edit", 1,
                        "plain".getBytes(java.nio.charset.StandardCharsets.UTF_8))));
        MessageSubmissionResult.Accepted newest = accepted(messages.submit(
                new MessageSubmission(conversation, account, device, "search-newest", 1,
                        "%_ marker 聊天 newest".getBytes(
                                java.nio.charset.StandardCharsets.UTF_8))));
        accepted(messages.submit(new MessageSubmission(
                conversation, account, device, "search-non-text", 100,
                "聊天 hidden".getBytes(java.nio.charset.StandardCharsets.UTF_8))));

        PostgresMessageSearchAdapter search = new PostgresMessageSearchAdapter(dataSource());
        MessageSearchPage first = found(search.search(new MessageSearchQuery(
                conversation, account, "聊天", 0, 1)));
        assertEquals(List.of(newest.messageId()),
                first.hits().stream().map(value -> value.messageId()).toList());
        assertEquals(newest.conversationSequence(), first.nextBeforeSequence());
        assertTrue(first.hasMore());
        MessageSearchPage second = found(search.search(new MessageSearchQuery(
                conversation, account, "聊天", first.nextBeforeSequence(), 1)));
        assertEquals(List.of(older.messageId()),
                second.hits().stream().map(value -> value.messageId()).toList());
        assertFalse(second.hasMore());
        assertEquals(List.of(newest.messageId()), found(search.search(new MessageSearchQuery(
                conversation, account, "%_", 0, 10))).hits().stream()
                .map(value -> value.messageId()).toList());

        MessageEditResult.Applied edited = (MessageEditResult.Applied)
                new PostgresMessageEditAdapter(dataSource()).edit(new MessageEditCommand(
                        conversation, editable.messageId(), account, device, 0, 1,
                        "聊天 edited".getBytes(java.nio.charset.StandardCharsets.UTF_8),
                        "search-edit-operation"));
        assertTrue(edited.changed());
        assertEquals(List.of(newest.messageId(), editable.messageId(), older.messageId()),
                found(search.search(new MessageSearchQuery(conversation, account, "聊天",
                        0, 10))).hits().stream().map(value -> value.messageId()).toList());

        try (Connection connection = connect()) {
            long recallSequence = edited.conversationSequence() + 1;
            execute(connection, "UPDATE chat.conversation SET next_sequence=? WHERE id=?",
                    recallSequence + 1, conversation);
            execute(connection, "INSERT INTO chat.conversation_entry VALUES "
                    + "(?,?,'MESSAGE_RECALLED',transaction_timestamp())",
                    conversation, recallSequence);
            execute(connection, "INSERT INTO chat.message_recall_event(conversation_id,"
                    + "conversation_sequence,message_id,actor_account_id,source) "
                    + "VALUES (?,?,?,?,'V2')", conversation, recallSequence,
                    newest.messageId(), account);
            execute(connection, "UPDATE chat.message SET deleted_at=transaction_timestamp() "
                    + "WHERE id=?", older.messageId());
        }
        MessageSearchPage current = found(search.search(new MessageSearchQuery(
                conversation, account, "聊天", 0, 10)));
        assertEquals(List.of(editable.messageId()),
                current.hits().stream().map(value -> value.messageId()).toList());
        assertEquals(1, current.hits().getFirst().contentRevision());
        assertArrayEquals(edited.content(), current.hits().getFirst().payload());
        assertEquals(MessageSearchResult.Rejected.NOT_AUTHORIZED,
                search.search(new MessageSearchQuery(
                        conversation, UUID.randomUUID(), "聊天", 0, 10)));
        assertMessageSearchIndexEligible(conversation);
        leaveConversation(conversation, account);
        assertEquals(MessageSearchResult.Rejected.NOT_AUTHORIZED,
                search.search(new MessageSearchQuery(conversation, account, "聊天", 0, 10)));
    }

    @Test
    @Order(16)
    void leasesConversationOutboxWithOrderingRetryExpiryAndFencing() throws Exception {
        requireDatabase();
        truncateApplicationData();
        UUID account = UUID.randomUUID();
        UUID device = UUID.randomUUID();
        UUID firstConversation = UUID.randomUUID();
        UUID secondConversation = UUID.randomUUID();
        seedMessageOwner(account, device, firstConversation);
        try (Connection connection = connect()) {
            execute(connection, "INSERT INTO chat.conversation(id, kind) VALUES (?, 'GROUP')",
                    secondConversation);
            execute(connection, "INSERT INTO chat.conversation_member("
                            + "conversation_id, account_id) VALUES (?, ?)",
                    secondConversation, account);
        }
        PostgresMessageAdapter messages = new PostgresMessageAdapter(dataSource());
        messages.submit(new MessageSubmission(firstConversation, account, device,
                "outbox-first-1", 1, new byte[] {1}));
        messages.submit(new MessageSubmission(firstConversation, account, device,
                "outbox-first-2", 1, new byte[] {2}));
        messages.submit(new MessageSubmission(secondConversation, account, device,
                "outbox-second-1", 1, new byte[] {3}));

        var outbox = new PostgresConversationEventOutboxAdapter(dataSource());
        var status = new PostgresConversationEventOutboxStatusAdapter(dataSource());
        UUID firstOwner = UUID.randomUUID();
        Instant start = Instant.parse("2030-01-01T00:00:00Z");
        ConversationEventOutboxStatus initialStatus = status.readStatus(start);
        assertEquals(3, initialStatus.unpublished());
        assertEquals(2, initialStatus.ready());
        assertEquals(0, initialStatus.leased());
        assertEquals(0, initialStatus.delayed());
        assertEquals(0, initialStatus.retried());
        assertEquals(0, initialStatus.maximumAttemptCount());
        assertTrue(initialStatus.oldestAgeSeconds(start) > 0);
        List<ConversationEventOutboxClaim> initial =
                outbox.claim(firstOwner, start, Duration.ofSeconds(5), 10);
        assertEquals(2, initial.size());
        assertEquals(Set.of(firstConversation, secondConversation),
                initial.stream().map(ConversationEventOutboxClaim::conversationId)
                        .collect(java.util.stream.Collectors.toSet()));
        assertTrue(initial.stream().allMatch(claim -> claim.conversationSequence() == 1));
        assertTrue(initial.stream().allMatch(claim -> claim.attemptCount() == 1));
        ConversationEventOutboxStatus leasedStatus = status.readStatus(start.plusSeconds(1));
        assertEquals(3, leasedStatus.unpublished());
        assertEquals(0, leasedStatus.ready());
        assertEquals(2, leasedStatus.leased());
        assertEquals(1, leasedStatus.maximumAttemptCount());

        ConversationEventOutboxClaim delayed = initial.stream()
                .filter(claim -> claim.conversationId().equals(firstConversation))
                .findFirst().orElseThrow();
        ConversationEventOutboxClaim completed = initial.stream()
                .filter(claim -> claim.conversationId().equals(secondConversation))
                .findFirst().orElseThrow();
        assertTrue(outbox.defer(delayed, start.plusSeconds(1), start.plusSeconds(10),
                "REDIS_UNAVAILABLE"));
        assertTrue(outbox.markPublished(completed, start.plusSeconds(1)));
        assertFalse(outbox.markPublished(completed, start.plusSeconds(1)));
        ConversationEventOutboxStatus delayedStatus = status.readStatus(start.plusSeconds(2));
        assertEquals(2, delayedStatus.unpublished());
        assertEquals(0, delayedStatus.ready());
        assertEquals(0, delayedStatus.leased());
        assertEquals(1, delayedStatus.delayed());
        assertEquals(0, delayedStatus.retried());
        assertTrue(outbox.claim(UUID.randomUUID(), start.plusSeconds(2),
                Duration.ofSeconds(2), 10).isEmpty());

        UUID secondOwner = UUID.randomUUID();
        ConversationEventOutboxClaim retried = outbox.claim(secondOwner,
                start.plusSeconds(10), Duration.ofSeconds(2), 10).getFirst();
        assertEquals(delayed.eventId(), retried.eventId());
        assertEquals(2, retried.attemptCount());
        assertNotEquals(delayed.claimId(), retried.claimId());
        assertEquals(1, status.readStatus(start.plusSeconds(11)).retried());
        assertFalse(outbox.markPublished(delayed, start.plusSeconds(2)));

        ConversationEventOutboxClaim reclaimed = outbox.claim(UUID.randomUUID(),
                start.plusSeconds(12), Duration.ofSeconds(2), 10).getFirst();
        assertEquals(retried.eventId(), reclaimed.eventId());
        assertEquals(3, reclaimed.attemptCount());
        assertNotEquals(retried.claimId(), reclaimed.claimId());
        assertFalse(outbox.markPublished(retried, start.plusSeconds(11)));
        ConversationEventOutboxClaim wrongOwner = new ConversationEventOutboxClaim(
                reclaimed.eventId(), reclaimed.conversationId(),
                reclaimed.conversationSequence(), reclaimed.claimId(), UUID.randomUUID(),
                reclaimed.claimedAt(), reclaimed.claimExpiresAt(), reclaimed.attemptCount());
        assertFalse(outbox.markPublished(wrongOwner, start.plusSeconds(13)));
        assertTrue(outbox.markPublished(reclaimed, start.plusSeconds(13)));

        ConversationEventOutboxClaim next = outbox.claim(UUID.randomUUID(),
                start.plusSeconds(13), Duration.ofSeconds(2), 10).getFirst();
        assertEquals(firstConversation, next.conversationId());
        assertEquals(2, next.conversationSequence());
        assertTrue(outbox.markPublished(next, start.plusSeconds(14)));
        assertTrue(outbox.claim(UUID.randomUUID(), start.plusSeconds(15),
                Duration.ofSeconds(2), 10).isEmpty());
        assertEquals(3, count("SELECT count(*) FROM chat.conversation_event_outbox "
                + "WHERE published_at IS NOT NULL AND claim_owner IS NULL "
                + "AND claim_id IS NULL AND claim_expires_at IS NULL"));
        assertEquals(new ConversationEventOutboxStatus(
                0, 0, 0, 0, 0, 0, Optional.empty()),
                status.readStatus(start.plusSeconds(15)));
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
    @Order(6)
    void listsOnlyActiveParticipantsForAnActiveRequesterWithStableAccountCursor() throws Exception {
        requireDatabase();
        truncateApplicationData();
        UUID conversation = UUID.fromString("41000000-0000-4000-8000-000000000001");
        UUID requester = UUID.fromString("21000000-0000-4000-8000-000000000001");
        UUID active = UUID.fromString("21000000-0000-4000-8000-000000000002");
        UUID departed = UUID.fromString("21000000-0000-4000-8000-000000000003");
        try (Connection connection = connect()) {
            execute(connection,
                    "INSERT INTO chat.account(id, username_key, display_name, password_hash) "
                            + "VALUES (?, 'participant-requester', 'Alice', "
                            + "'$argon2id$v=19$m=65536,t=2,p=1$test$fixture'), "
                            + "(?, 'participant-active', '李', "
                            + "'$argon2id$v=19$m=65536,t=2,p=1$test$fixture'), "
                            + "(?, 'participant-departed', 'Former', "
                            + "'$argon2id$v=19$m=65536,t=2,p=1$test$fixture')",
                    requester, active, departed);
            execute(connection,
                    "INSERT INTO chat.conversation(id, kind, title) VALUES (?, 'GROUP', 'Team')",
                    conversation);
            execute(connection,
                    "INSERT INTO chat.conversation_member(conversation_id, account_id, role) "
                            + "VALUES (?, ?, 'OWNER'), (?, ?, 'MEMBER'), (?, ?, 'MEMBER')",
                    conversation, requester, conversation, active, conversation, departed);
        }
        leaveConversation(conversation, departed);

        PostgresConversationParticipantAdapter adapter =
                new PostgresConversationParticipantAdapter(dataSource());
        ConversationParticipantResult.Found first = assertInstanceOf(
                ConversationParticipantResult.Found.class,
                adapter.list(new ConversationParticipantQuery(
                        conversation, requester, Optional.empty(), 1)));
        ConversationParticipantPage firstPage = first.page();
        assertEquals(List.of(requester), firstPage.participants().stream()
                .map(participant -> participant.accountId()).toList());
        assertTrue(firstPage.hasMore());

        ConversationParticipantResult.Found second = assertInstanceOf(
                ConversationParticipantResult.Found.class,
                adapter.list(new ConversationParticipantQuery(
                        conversation, requester, firstPage.nextAccountId(), 1)));
        assertEquals(active, second.page().participants().getFirst().accountId());
        assertEquals("李", second.page().participants().getFirst().displayName());
        assertFalse(second.page().hasMore());

        leaveConversation(conversation, requester);
        assertEquals(ConversationParticipantResult.Rejected.NOT_AUTHORIZED,
                adapter.list(new ConversationParticipantQuery(
                        conversation, requester, Optional.empty(), 100)));
        assertEquals(ConversationParticipantResult.Rejected.NOT_AUTHORIZED,
                adapter.list(new ConversationParticipantQuery(
                        conversation, UUID.randomUUID(), Optional.empty(), 100)));
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
                + "AND mapping.legacy_conversation_id = 10 AND policy.max_members = 137"));
        assertEquals(1, count("SELECT count(*) FROM chat.group_resource_policy policy "
                + "JOIN chat.legacy_v1_conversation_map mapping "
                + "ON mapping.conversation_id = policy.conversation_id "
                + "WHERE mapping.legacy_kind = 'ROOM' "
                + "AND mapping.legacy_conversation_id = 10 "
                + "AND policy.max_file_size = 2048 "
                + "AND policy.total_file_space = 8192 "
                + "AND policy.max_file_count = 42"));
        assertEquals(1, count("SELECT count(*) FROM chat.conversation_import_run"));

        try (Connection connection = connect()) {
            execute(connection, "UPDATE chat.group_admission_policy SET max_members = 50 "
                    + "WHERE conversation_id = ?",
                    V1ConversationImportPlanner.deterministicRoomId(10));
            execute(connection, "UPDATE chat.group_resource_policy "
                    + "SET max_file_size = 10737418240, total_file_space = 10737418240, "
                    + "max_file_count = 1500 WHERE conversation_id = ?",
                    V1ConversationImportPlanner.deterministicRoomId(10));
        }
        V1ConversationImportReport policyPreview = importer.preview(input.plan());
        assertTrue(policyPreview.readyToApply());
        assertEquals(1, policyPreview.admissionPoliciesToUpdate());
        assertEquals(1, policyPreview.resourcePoliciesToUpdate());
        V1ConversationImportReport policyMigration = importer.apply(input);
        assertEquals(1, policyMigration.admissionPoliciesToUpdate());
        assertEquals(1, policyMigration.resourcePoliciesToUpdate());
        assertEquals(1, count("SELECT count(*) FROM chat.group_admission_policy policy "
                + "WHERE policy.conversation_id = '"
                + V1ConversationImportPlanner.deterministicRoomId(10)
                + "' AND policy.max_members = 137"));

        V1ConversationImportReport rerun = importer.apply(input);
        assertEquals(0, rerun.insertableConversations());
        assertEquals(2, rerun.alreadyImportedConversations());
        assertEquals(0, rerun.insertableMemberships());
        assertEquals(4, rerun.alreadyImportedMemberships());
        assertEquals(0, rerun.admissionPoliciesToUpdate());
        assertEquals(0, rerun.resourcePoliciesToUpdate());
        assertEquals(3, count("SELECT count(*) FROM chat.conversation_import_run"));

        try (Connection connection = connect()) {
            execute(connection, "UPDATE chat.group_admission_policy SET max_members = 138 "
                    + "WHERE conversation_id = ?",
                    V1ConversationImportPlanner.deterministicRoomId(10));
        }
        V1ConversationImportReport policyConflict = importer.preview(input.plan());
        assertTrue(policyConflict.issues().stream().anyMatch(
                issue -> "TARGET_GROUP_POLICY_CONFLICT".equals(issue.code())));
        assertThrows(V1ConversationImportException.class, () -> importer.apply(input));
        try (Connection connection = connect()) {
            execute(connection, "UPDATE chat.group_admission_policy SET max_members = 137 "
                    + "WHERE conversation_id = ?",
                    V1ConversationImportPlanner.deterministicRoomId(10));
            execute(connection, "UPDATE chat.group_resource_policy SET max_file_size = 4096 "
                    + "WHERE conversation_id = ?",
                    V1ConversationImportPlanner.deterministicRoomId(10));
        }
        V1ConversationImportReport resourceConflict = importer.preview(input.plan());
        assertTrue(resourceConflict.issues().stream().anyMatch(
                issue -> "TARGET_GROUP_RESOURCE_POLICY_CONFLICT".equals(issue.code())));
        assertThrows(V1ConversationImportException.class, () -> importer.apply(input));
        try (Connection connection = connect()) {
            execute(connection, "UPDATE chat.group_resource_policy SET max_file_size = 2048 "
                    + "WHERE conversation_id = ?",
                    V1ConversationImportPlanner.deterministicRoomId(10));
        }

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
        assertEquals(3, count("SELECT count(*) FROM chat.conversation_import_run"));

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
        assertEquals(3, count("SELECT count(*) FROM chat.conversation_import_run"));
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
                + created.get(0).conversationId() + "' AND encoded_password LIKE '$argon2id$%' "
                + "AND password_idempotency_tag = '" + password.idempotencyTag() + "'"));
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
            SQLException excessiveLimit = assertThrows(SQLException.class, () -> execute(connection,
                    "UPDATE chat.group_admission_policy SET max_members = 1000001 "
                            + "WHERE conversation_id = ?", created.conversationId()));
            assertEquals("23514", excessiveLimit.getSQLState());
        }
        assertEquals(LegacyV1RoomJoinResult.Rejected.ACCESS_CHANGED,
                adapter.join(new LegacyV1RoomJoinIntent(rejectedActor, created.conversationId(),
                        created.legacyRoomId(), stale.joinCredential())));
    }

    @Test
    @Order(93)
    void leavesV1RoomsAtomicallyTransfersOwnerAndDurablyDissolves() throws Exception {
        requireDatabase(); truncateApplicationData();
        UUID owner = UUID.randomUUID(), admin = UUID.randomUUID();
        UUID member = UUID.randomUUID(), outsider = UUID.randomUUID();
        try (Connection connection = connect()) {
            execute(connection,
                    "INSERT INTO chat.account(id, username_key, display_name, password_hash) "
                            + "VALUES (?, 'leave-owner', 'Owner', "
                            + "'$argon2id$v=19$m=65536,t=2,p=1$test$fixture'), "
                            + "(?, 'leave-admin', 'Next Admin', "
                            + "'$argon2id$v=19$m=65536,t=2,p=1$test$fixture'), "
                            + "(?, 'leave-member', 'Member', "
                            + "'$argon2id$v=19$m=65536,t=2,p=1$test$fixture'), "
                            + "(?, 'leave-outsider', 'Outsider', "
                            + "'$argon2id$v=19$m=65536,t=2,p=1$test$fixture')",
                    owner, admin, member, outsider);
            execute(connection,
                    "INSERT INTO chat.legacy_v1_account_map(legacy_user_id, account_id) "
                            + "VALUES (71, ?), (72, ?), (73, ?), (74, ?)",
                    owner, admin, member, outsider);
        }
        var created = (LegacyV1RoomCreationResult.Created)
                new PostgresLegacyV1RoomCreationAdapter(dataSource()).create(
                        new LegacyV1RoomCreationIntent(owner, "leave-room-create",
                                "Leave Room", Optional.empty()));
        PostgresLegacyV1RoomJoinAdapter joins =
                new PostgresLegacyV1RoomJoinAdapter(dataSource());
        for (UUID actor : List.of(admin, member)) {
            var access = (LegacyV1RoomJoinAccess.Candidate)
                    joins.inspect(actor, created.legacyRoomId());
            assertTrue(joins.join(new LegacyV1RoomJoinIntent(actor,
                    access.conversationId(), access.legacyRoomId(),
                    access.joinCredential())) instanceof LegacyV1RoomJoinResult.Joined);
        }
        try (Connection connection = connect()) {
            execute(connection, "UPDATE chat.conversation_member SET role = 'ADMIN' "
                    + "WHERE conversation_id = ? AND account_id = ?",
                    created.conversationId(), admin);
        }

        PostgresLegacyV1RoomLeaveAdapter leaves =
                new PostgresLegacyV1RoomLeaveAdapter(dataSource());
        assertEquals(LegacyV1RoomLeaveResult.Rejected.NOT_MEMBER,
                leaves.leave(new LegacyV1RoomLeaveIntent(outsider, created.legacyRoomId())));
        try (Connection connection = connect()) {
            execute(connection, "UPDATE chat.account SET disabled_at = transaction_timestamp() "
                    + "WHERE id = ?", outsider);
        }
        assertEquals(LegacyV1RoomLeaveResult.Rejected.LEAVE_DENIED,
                leaves.leave(new LegacyV1RoomLeaveIntent(outsider, created.legacyRoomId())));

        var ownerLeft = (LegacyV1RoomLeaveResult.Left) leaves.leave(
                new LegacyV1RoomLeaveIntent(owner, created.legacyRoomId()));
        assertTrue(ownerLeft.newLeave()); assertFalse(ownerLeft.dissolved());
        assertEquals(admin, ownerLeft.ownershipTransfer().orElseThrow().successorAccountId());
        assertEquals("Next Admin",
                ownerLeft.ownershipTransfer().orElseThrow().successorDisplayName());
        assertEquals(1, count("SELECT count(*) FROM chat.conversation_member WHERE conversation_id = '"
                + created.conversationId() + "' AND account_id = '" + admin
                + "' AND role = 'OWNER' AND left_at IS NULL"));
        var ownerRetry = (LegacyV1RoomLeaveResult.Left) leaves.leave(
                new LegacyV1RoomLeaveIntent(owner, created.legacyRoomId()));
        assertFalse(ownerRetry.newLeave()); assertFalse(ownerRetry.dissolved());
        assertTrue(ownerRetry.ownershipTransfer().isEmpty());
        assertTrue(new PostgresConversationDirectoryAdapter(dataSource()).list(
                new ConversationDirectoryQuery(owner, Optional.empty(), 100))
                .conversations().isEmpty());

        assertTrue(((LegacyV1RoomLeaveResult.Left) leaves.leave(
                new LegacyV1RoomLeaveIntent(member, created.legacyRoomId()))).newLeave());
        var dissolved = (LegacyV1RoomLeaveResult.Left) leaves.leave(
                new LegacyV1RoomLeaveIntent(admin, created.legacyRoomId()));
        assertTrue(dissolved.newLeave()); assertTrue(dissolved.dissolved());
        assertTrue(dissolved.ownershipTransfer().isEmpty());
        assertEquals(1, count("SELECT count(*) FROM chat.group_lifecycle WHERE conversation_id = '"
                + created.conversationId() + "' AND closed_at IS NOT NULL"));
        assertEquals(1, count("SELECT count(*) FROM chat.conversation WHERE id = '"
                + created.conversationId() + "'"));
        assertEquals(1, count("SELECT count(*) FROM chat.legacy_v1_conversation_map "
                + "WHERE conversation_id = '" + created.conversationId() + "'"));
        assertTrue(new PostgresLegacyV1RoomSearchAdapter(dataSource())
                .search(owner, "Leave Room", 20).isEmpty());
        assertEquals(LegacyV1RoomJoinAccess.Rejected.NOT_FOUND,
                joins.inspect(member, created.legacyRoomId()));
        try (Connection connection = connect()) {
            execute(connection, "UPDATE chat.account SET disabled_at = NULL WHERE id = ?",
                    outsider);
        }
        assertEquals(LegacyV1RoomLeaveResult.Rejected.NOT_FOUND,
                leaves.leave(new LegacyV1RoomLeaveIntent(
                        outsider, created.legacyRoomId())));

        try (Connection connection = connect()) {
            execute(connection, "UPDATE chat.conversation_member SET left_at = NULL "
                    + "WHERE conversation_id = ? AND account_id = ?",
                    created.conversationId(), admin);
        }
        assertTrue(new PostgresConversationDirectoryAdapter(dataSource()).list(
                new ConversationDirectoryQuery(admin, Optional.empty(), 100))
                .conversations().isEmpty());
        assertTrue(new PostgresLegacyV1RoomAudienceAdapter(dataSource())
                .activeMappedMembers(created.conversationId(), Set.of(admin)).isEmpty());
        assertEquals(LegacyV1RoomHistoryResult.Rejected.ROOM_ACCESS_DENIED,
                new PostgresLegacyV1RoomHistoryAdapter(dataSource()).read(
                        new LegacyV1RoomHistoryQuery(admin, created.legacyRoomId(),
                                20, 0, 0L)));
        assertEquals(LegacyV1RoomReadResult.Rejected.ROOM_ACCESS_DENIED,
                new PostgresLegacyV1RoomReadAdapter(dataSource()).markRead(
                        new LegacyV1RoomReadCommand(admin, created.legacyRoomId())));
    }

    @Test
    @Order(93)
    void kicksV1RoomMembersAtomicallyWithGenerationBoundRetryAudit() throws Exception {
        requireDatabase(); truncateApplicationData();
        UUID owner = UUID.randomUUID(), admin = UUID.randomUUID();
        UUID member = UUID.randomUUID(), outsider = UUID.randomUUID();
        try (Connection connection = connect()) {
            execute(connection, "INSERT INTO chat.account(id, username_key, display_name, "
                    + "password_hash) VALUES (?, 'kick-owner', 'Owner', "
                    + "'$argon2id$v=19$m=65536,t=2,p=1$test$fixture'), "
                    + "(?, 'kick-admin', 'Admin', "
                    + "'$argon2id$v=19$m=65536,t=2,p=1$test$fixture'), "
                    + "(?, 'kick-member', 'Member', "
                    + "'$argon2id$v=19$m=65536,t=2,p=1$test$fixture'), "
                    + "(?, 'kick-outsider', 'Outsider', "
                    + "'$argon2id$v=19$m=65536,t=2,p=1$test$fixture')",
                    owner, admin, member, outsider);
            execute(connection, "INSERT INTO chat.legacy_v1_account_map(legacy_user_id, "
                    + "account_id) VALUES (85, ?), (86, ?), (87, ?), (88, ?)",
                    owner, admin, member, outsider);
        }
        var created = (LegacyV1RoomCreationResult.Created)
                new PostgresLegacyV1RoomCreationAdapter(dataSource()).create(
                        new LegacyV1RoomCreationIntent(owner, "kick-room-create",
                                "Kick Room", Optional.empty()));
        PostgresLegacyV1RoomJoinAdapter joins = new PostgresLegacyV1RoomJoinAdapter(dataSource());
        for (UUID account : List.of(admin, member)) {
            var access = (LegacyV1RoomJoinAccess.Candidate)
                    joins.inspect(account, created.legacyRoomId());
            joins.join(new LegacyV1RoomJoinIntent(account, access.conversationId(),
                    access.legacyRoomId(), access.joinCredential()));
        }
        try (Connection connection = connect()) {
            execute(connection, "UPDATE chat.conversation_member SET role = 'ADMIN' "
                    + "WHERE conversation_id = ? AND account_id = ?",
                    created.conversationId(), admin);
        }

        PostgresLegacyV1RoomKickAdapter kicks = new PostgresLegacyV1RoomKickAdapter(dataSource());
        assertEquals(LegacyV1RoomKickResult.Rejected.ROOM_ADMIN_REQUIRED,
                kicks.kick(new LegacyV1RoomKickCommand(
                        member, created.legacyRoomId(), "kick-admin")));
        assertEquals(LegacyV1RoomKickResult.Rejected.TARGET_ROLE_PROTECTED,
                kicks.kick(new LegacyV1RoomKickCommand(
                        owner, created.legacyRoomId(), "kick-admin")));
        assertEquals(LegacyV1RoomKickResult.Rejected.TARGET_NOT_ACTIVE_MEMBER,
                kicks.kick(new LegacyV1RoomKickCommand(
                        owner, created.legacyRoomId(), "kick-outsider")));

        var first = (LegacyV1RoomKickResult.Kicked) kicks.kick(
                new LegacyV1RoomKickCommand(owner, created.legacyRoomId(), "kick-member"));
        assertTrue(first.changed()); assertEquals("Kick Room", first.roomName());
        var retry = (LegacyV1RoomKickResult.Kicked) kicks.kick(
                new LegacyV1RoomKickCommand(owner, created.legacyRoomId(), "kick-member"));
        assertFalse(retry.changed()); assertEquals(first.kickedAt(), retry.kickedAt());
        assertEquals(LegacyV1RoomKickResult.Rejected.TARGET_NOT_ACTIVE_MEMBER,
                kicks.kick(new LegacyV1RoomKickCommand(
                        admin, created.legacyRoomId(), "kick-member")));
        assertEquals(1, count("SELECT count(*) FROM chat.legacy_v1_room_kick_event "
                + "WHERE conversation_id = '" + created.conversationId() + "'"));

        var candidate = (LegacyV1RoomJoinAccess.Candidate)
                joins.inspect(member, created.legacyRoomId());
        assertTrue(((LegacyV1RoomJoinResult.Joined) joins.join(
                new LegacyV1RoomJoinIntent(member, candidate.conversationId(),
                        candidate.legacyRoomId(), candidate.joinCredential()))).newJoin());
        var second = (LegacyV1RoomKickResult.Kicked) kicks.kick(
                new LegacyV1RoomKickCommand(owner, created.legacyRoomId(), "kick-member"));
        assertTrue(second.changed()); assertNotEquals(first.kickedAt(), second.kickedAt());
        assertEquals(2, count("SELECT count(*) FROM chat.legacy_v1_room_kick_event "
                + "WHERE conversation_id = '" + created.conversationId() + "'"));
    }

    @Test
    @Order(93)
    void changesV1RoomAdministratorRolesAtomicallyAndConvergently() throws Exception {
        requireDatabase(); truncateApplicationData();
        UUID owner = UUID.randomUUID(), admin = UUID.randomUUID();
        UUID member = UUID.randomUUID(), outsider = UUID.randomUUID();
        try (Connection connection = connect()) {
            execute(connection, "INSERT INTO chat.account(id, username_key, display_name, "
                    + "password_hash) VALUES (?, 'admin-owner', 'Owner', "
                    + "'$argon2id$v=19$m=65536,t=2,p=1$test$fixture'), "
                    + "(?, 'admin-admin', 'Admin', "
                    + "'$argon2id$v=19$m=65536,t=2,p=1$test$fixture'), "
                    + "(?, 'admin-member', 'Member', "
                    + "'$argon2id$v=19$m=65536,t=2,p=1$test$fixture'), "
                    + "(?, 'admin-outsider', 'Outsider', "
                    + "'$argon2id$v=19$m=65536,t=2,p=1$test$fixture')",
                    owner, admin, member, outsider);
            execute(connection, "INSERT INTO chat.legacy_v1_account_map(legacy_user_id, "
                    + "account_id) VALUES (75, ?), (76, ?), (77, ?), (78, ?)",
                    owner, admin, member, outsider);
        }
        var created = (LegacyV1RoomCreationResult.Created)
                new PostgresLegacyV1RoomCreationAdapter(dataSource()).create(
                        new LegacyV1RoomCreationIntent(owner, "admin-room-create",
                                "Admin Room", Optional.empty()));
        PostgresLegacyV1RoomJoinAdapter joins =
                new PostgresLegacyV1RoomJoinAdapter(dataSource());
        for (UUID actor : List.of(admin, member)) {
            var access = (LegacyV1RoomJoinAccess.Candidate)
                    joins.inspect(actor, created.legacyRoomId());
            joins.join(new LegacyV1RoomJoinIntent(actor, access.conversationId(),
                    access.legacyRoomId(), access.joinCredential()));
        }
        PostgresLegacyV1RoomAdminAdapter roles =
                new PostgresLegacyV1RoomAdminAdapter(dataSource());
        assertEquals(LegacyV1RoomAdminResult.Rejected.ROOM_ADMIN_REQUIRED,
                roles.change(new LegacyV1RoomAdminCommand(
                        member, created.legacyRoomId(), "admin-admin", true)));
        assertEquals(LegacyV1RoomAdminResult.Rejected.TARGET_NOT_ACTIVE_MEMBER,
                roles.change(new LegacyV1RoomAdminCommand(
                        owner, created.legacyRoomId(), "admin-outsider", true)));
        assertEquals(LegacyV1RoomAdminResult.Rejected.OWNER_PROTECTED,
                roles.change(new LegacyV1RoomAdminCommand(
                        owner, created.legacyRoomId(), "admin-owner", false)));

        var promoted = (LegacyV1RoomAdminResult.Changed) roles.change(
                new LegacyV1RoomAdminCommand(
                        owner, created.legacyRoomId(), "admin-admin", true));
        assertTrue(promoted.changed()); assertTrue(promoted.admin());
        assertEquals(admin, promoted.targetAccountId());
        var retry = (LegacyV1RoomAdminResult.Changed) roles.change(
                new LegacyV1RoomAdminCommand(
                        owner, created.legacyRoomId(), "admin-admin", true));
        assertFalse(retry.changed()); assertTrue(retry.admin());
        assertEquals(LegacyV1RoomAdminResult.Rejected.SELF_DEMOTION_REQUIRED,
                roles.change(new LegacyV1RoomAdminCommand(
                        owner, created.legacyRoomId(), "admin-admin", false)));

        var demoted = (LegacyV1RoomAdminResult.Changed) roles.change(
                new LegacyV1RoomAdminCommand(
                        admin, created.legacyRoomId(), "admin-admin", false));
        assertTrue(demoted.changed()); assertFalse(demoted.admin());
        assertEquals(1, count("SELECT count(*) FROM chat.conversation_member WHERE "
                + "conversation_id = '" + created.conversationId() + "' "
                + "AND account_id = '" + admin + "' AND role = 'MEMBER'"));
        assertEquals(LegacyV1RoomAdminResult.Rejected.ROOM_ADMIN_REQUIRED,
                roles.change(new LegacyV1RoomAdminCommand(
                        admin, created.legacyRoomId(), "admin-admin", false)));
    }

    @Test
    @Order(93)
    void listsOnlyCompleteAuthorizedActiveV1RoomMembers() throws Exception {
        requireDatabase(); truncateApplicationData();
        UUID owner = UUID.randomUUID(), member = UUID.randomUUID(), outsider = UUID.randomUUID();
        try (Connection connection = connect()) {
            execute(connection, "INSERT INTO chat.account(id, username_key, display_name, "
                    + "password_hash) VALUES (?, 'list-owner', 'Owner', "
                    + "'$argon2id$v=19$m=65536,t=2,p=1$test$fixture'), "
                    + "(?, 'list-member', 'Member', "
                    + "'$argon2id$v=19$m=65536,t=2,p=1$test$fixture'), "
                    + "(?, 'list-outsider', 'Outsider', "
                    + "'$argon2id$v=19$m=65536,t=2,p=1$test$fixture')",
                    owner, member, outsider);
            execute(connection, "INSERT INTO chat.legacy_v1_account_map(legacy_user_id, "
                    + "account_id) VALUES (81, ?), (82, ?), (83, ?)", owner, member, outsider);
        }
        var created = (LegacyV1RoomCreationResult.Created)
                new PostgresLegacyV1RoomCreationAdapter(dataSource()).create(
                        new LegacyV1RoomCreationIntent(owner, "member-list-create",
                                "Member List", Optional.empty()));
        var candidate = (LegacyV1RoomJoinAccess.Candidate)
                new PostgresLegacyV1RoomJoinAdapter(dataSource())
                        .inspect(member, created.legacyRoomId());
        new PostgresLegacyV1RoomJoinAdapter(dataSource()).join(
                new LegacyV1RoomJoinIntent(member, candidate.conversationId(),
                        candidate.legacyRoomId(), candidate.joinCredential()));
        PostgresLegacyV1RoomMemberListAdapter adapter =
                new PostgresLegacyV1RoomMemberListAdapter(dataSource());
        var listed = (LegacyV1RoomMemberListPort.QueryResult.Authorized)
                adapter.list(owner, created.legacyRoomId(), 1001);
        assertEquals(List.of("list-member", "list-owner"), listed.members().stream()
                .map(entry -> entry.username()).toList());
        assertEquals(List.of("MEMBER", "OWNER"), listed.members().stream()
                .map(entry -> entry.role().name()).toList());
        assertEquals(LegacyV1RoomMemberListPort.QueryResult.Rejected.ROOM_ACCESS_DENIED,
                adapter.list(outsider, created.legacyRoomId(), 1001));
        try (Connection connection = connect()) {
            execute(connection, "UPDATE chat.group_lifecycle SET closed_at = "
                    + "transaction_timestamp() WHERE conversation_id = ?",
                    created.conversationId());
        }
        assertEquals(LegacyV1RoomMemberListPort.QueryResult.Rejected.ROOM_ACCESS_DENIED,
                adapter.list(owner, created.legacyRoomId(), 1001));
    }

    @Test
    @Order(93)
    void readsOnlyCompleteAuthorizedActiveV1RoomSettings() throws Exception {
        requireDatabase(); truncateApplicationData();
        UUID owner = UUID.randomUUID(), outsider = UUID.randomUUID();
        try (Connection connection = connect()) {
            execute(connection, "INSERT INTO chat.account(id, username_key, display_name, "
                    + "password_hash) VALUES (?, 'settings-owner', 'Owner', "
                    + "'$argon2id$v=19$m=65536,t=2,p=1$test$fixture'), "
                    + "(?, 'settings-outsider', 'Outsider', "
                    + "'$argon2id$v=19$m=65536,t=2,p=1$test$fixture')", owner, outsider);
            execute(connection, "INSERT INTO chat.legacy_v1_account_map(legacy_user_id, "
                    + "account_id) VALUES (91, ?), (92, ?)", owner, outsider);
        }
        var created = (LegacyV1RoomCreationResult.Created)
                new PostgresLegacyV1RoomCreationAdapter(dataSource()).create(
                        new LegacyV1RoomCreationIntent(owner, "settings-create",
                                "Settings", java.util.Optional.empty()));
        try (Connection connection = connect()) {
            execute(connection, "UPDATE chat.group_resource_policy SET max_file_size = 2048, "
                    + "total_file_space = 8192, max_file_count = 42 "
                    + "WHERE conversation_id = ?", created.conversationId());
            execute(connection, "UPDATE chat.group_admission_policy SET max_members = 73 "
                    + "WHERE conversation_id = ?", created.conversationId());
        }
        PostgresLegacyV1RoomSettingsAdapter adapter =
                new PostgresLegacyV1RoomSettingsAdapter(dataSource());
        var authorized = (LegacyV1RoomSettingsPort.QueryResult.Authorized)
                adapter.read(owner, created.legacyRoomId());
        assertEquals(new LegacyV1RoomSettings(2048, 8192, 42, 73),
                authorized.settings());
        assertEquals(LegacyV1RoomSettingsPort.QueryResult.Rejected.ROOM_ACCESS_DENIED,
                adapter.read(outsider, created.legacyRoomId()));
        try (Connection connection = connect()) {
            execute(connection, "UPDATE chat.group_lifecycle SET closed_at = "
                    + "transaction_timestamp() WHERE conversation_id = ?",
                    created.conversationId());
        }
        assertEquals(LegacyV1RoomSettingsPort.QueryResult.Rejected.ROOM_ACCESS_DENIED,
                adapter.read(owner, created.legacyRoomId()));
    }

    @Test
    @Order(93)
    void atomicallyDeletesV1RoomMessagesInAllModesAndReplaysExactRetries()
            throws Exception {
        requireDatabase(); truncateApplicationData();
        UUID owner = UUID.randomUUID(), outsider = UUID.randomUUID();
        UUID ownerDevice = UUID.randomUUID(), outsiderDevice = UUID.randomUUID();
        try (Connection connection = connect()) {
            execute(connection, "INSERT INTO chat.account(id, username_key, display_name, "
                    + "password_hash) VALUES (?, 'delete-owner', 'Delete Owner', "
                    + "'$argon2id$v=19$m=65536,t=2,p=1$test$fixture'), "
                    + "(?, 'delete-outsider', 'Delete Outsider', "
                    + "'$argon2id$v=19$m=65536,t=2,p=1$test$fixture')", owner, outsider);
            execute(connection, "INSERT INTO chat.legacy_v1_account_map(legacy_user_id, "
                    + "account_id) VALUES (201, ?), (202, ?)", owner, outsider);
            execute(connection, "INSERT INTO chat.device(id, account_id, client_device_id, "
                    + "platform) VALUES (?, ?, 'delete-owner-device', 'LEGACY'), "
                    + "(?, ?, 'delete-outsider-device', 'LEGACY')",
                    ownerDevice, owner, outsiderDevice, outsider);
        }
        LegacyV1RoomCreationResult.Created room =
                (LegacyV1RoomCreationResult.Created)
                new PostgresLegacyV1RoomCreationAdapter(dataSource()).create(
                        new LegacyV1RoomCreationIntent(owner, "delete-room-create",
                                "Delete Room", Optional.empty()));
        PostgresLegacyV1RoomMessageAdapter messages =
                new PostgresLegacyV1RoomMessageAdapter(dataSource());
        List<LegacyV1RoomMessageResult.Accepted> accepted = new ArrayList<>();
        for (int index = 1; index <= 4; index++) {
            accepted.add((LegacyV1RoomMessageResult.Accepted) messages.submit(
                    new LegacyV1RoomMessageCommand(owner, ownerDevice, room.legacyRoomId(),
                            "delete-message-" + index, "message " + index, "text")));
        }
        LegacyV1RoomRecallResult recalled = new PostgresLegacyV1RoomRecallAdapter(dataSource())
                .recall(new LegacyV1RoomRecallCommand(
                        owner, room.legacyRoomId(), accepted.get(1).legacyMessageId()));
        assertInstanceOf(LegacyV1RoomRecallResult.Recalled.class, recalled);
        List<Instant> times = List.of(Instant.parse("2020-01-01T00:00:00Z"),
                Instant.parse("2021-01-01T00:00:00Z"),
                Instant.parse("2022-01-01T00:00:00Z"),
                Instant.parse("2023-01-01T00:00:00Z"));
        try (Connection connection = connect()) {
            for (int index = 0; index < accepted.size(); index++) {
                execute(connection, "UPDATE chat.message SET accepted_at = ? WHERE id = "
                        + "(SELECT message_id FROM chat.legacy_v1_message_map "
                        + "WHERE legacy_kind = 'ROOM' AND legacy_message_id = ?)",
                        OffsetDateTime.ofInstant(times.get(index), ZoneOffset.UTC),
                        accepted.get(index).legacyMessageId());
            }
        }
        var deletion = new LegacyV1RoomMessageDeletionService(
                new PostgresLegacyV1RoomMessageDeletionAdapter(dataSource()));
        LegacyV1RoomMessageDeletionResult.Deleted selected = deleted(deletion.delete(
                new LegacyV1RoomMessageDeletionCommand(owner, room.legacyRoomId(),
                        "delete-selected", "selected",
                        List.of(accepted.get(1).legacyMessageId()), 0)));
        assertFalse(selected.duplicate()); assertEquals(1, selected.deletedCount());
        assertEquals(List.of(accepted.get(1).legacyMessageId()),
                selected.legacyMessageIds());

        long beforeCutoff = Instant.parse("2021-06-01T00:00:00.999Z").toEpochMilli();
        LegacyV1RoomMessageDeletionResult.Deleted before = deleted(deletion.delete(
                new LegacyV1RoomMessageDeletionCommand(owner, room.legacyRoomId(),
                        "delete-before", "before", List.of(), beforeCutoff)));
        assertEquals(Instant.parse("2021-06-01T00:00:00Z").toEpochMilli(),
                before.cutoffEpochMillis());
        assertEquals(1, before.deletedCount()); assertTrue(before.legacyMessageIds().isEmpty());

        long afterCutoff = Instant.parse("2022-06-01T00:00:00Z").toEpochMilli();
        LegacyV1RoomMessageDeletionResult.Deleted after = deleted(deletion.delete(
                new LegacyV1RoomMessageDeletionCommand(owner, room.legacyRoomId(),
                        "delete-after", "after", List.of(), afterCutoff)));
        assertEquals(1, after.deletedCount());

        LegacyV1RoomMessageDeletionCommand allCommand =
                new LegacyV1RoomMessageDeletionCommand(owner, room.legacyRoomId(),
                        "delete-all", "all", List.of(), 0);
        LegacyV1RoomMessageDeletionResult.Deleted all = deleted(deletion.delete(allCommand));
        assertEquals(1, all.deletedCount()); assertFalse(all.duplicate());
        LegacyV1RoomMessageDeletionResult.Deleted retry = deleted(deletion.delete(allCommand));
        assertTrue(retry.duplicate()); assertEquals(all.sequence(), retry.sequence());
        assertEquals(all.occurredAt(), retry.occurredAt());
        assertEquals(LegacyV1RoomMessageDeletionResult.Rejected.CLIENT_OPERATION_ID_CONFLICT,
                deletion.delete(new LegacyV1RoomMessageDeletionCommand(owner,
                        room.legacyRoomId(), "delete-all", "before", List.of(), beforeCutoff)));
        assertEquals(LegacyV1RoomMessageDeletionResult.Rejected.ROOM_ADMIN_REQUIRED,
                deletion.delete(new LegacyV1RoomMessageDeletionCommand(outsider,
                        room.legacyRoomId(), "outsider-delete", "all", List.of(), 0)));

        assertEquals(0, count("SELECT count(*) FROM chat.message WHERE conversation_id = '"
                + room.conversationId() + "'"));
        assertEquals(0, count("SELECT count(*) FROM chat.message_recall_event "
                + "WHERE conversation_id = '" + room.conversationId() + "'"));
        assertEquals(4, count("SELECT count(*) FROM chat.messages_deleted_event "
                + "WHERE conversation_id = '" + room.conversationId() + "' "
                + "AND source = 'V2'"));
        assertEquals(4, count("SELECT count(*) FROM chat.legacy_v1_deletion_event_map "
                + "WHERE conversation_id = '" + room.conversationId() + "'"));
        LegacyV1RoomHistoryResult.Page history = (LegacyV1RoomHistoryResult.Page)
                new PostgresLegacyV1RoomHistoryAdapter(dataSource()).read(
                        new LegacyV1RoomHistoryQuery(owner, room.legacyRoomId(),
                                10, 0, 0L));
        assertEquals(List.of("selected", "before", "after", "all"),
                history.events().stream().map(event -> event.mode()).toList());
        assertTrue(history.messages().isEmpty());
    }

    @Test
    @Order(93)
    void renamesV1RoomConvergentlyWithDurableDirectoryProjection() throws Exception {
        requireDatabase(); truncateApplicationData();
        UUID owner = UUID.randomUUID(), member = UUID.randomUUID();
        try (Connection connection = connect()) {
            execute(connection, "INSERT INTO chat.account(id, username_key, display_name, "
                    + "password_hash) VALUES (?, 'rename-owner', 'Rename Owner', "
                    + "'$argon2id$v=19$m=65536,t=2,p=1$test$fixture'), "
                    + "(?, 'rename-member', 'Rename Member', "
                    + "'$argon2id$v=19$m=65536,t=2,p=1$test$fixture')", owner, member);
            execute(connection, "INSERT INTO chat.legacy_v1_account_map(legacy_user_id, "
                    + "account_id) VALUES (211, ?), (212, ?)", owner, member);
        }
        LegacyV1RoomCreationResult.Created room = (LegacyV1RoomCreationResult.Created)
                new PostgresLegacyV1RoomCreationAdapter(dataSource()).create(
                        new LegacyV1RoomCreationIntent(owner, "rename-room-create",
                                "Original Room", Optional.empty()));
        try (Connection connection = connect()) {
            execute(connection, "INSERT INTO chat.conversation_member(conversation_id, "
                    + "account_id, role) VALUES (?, ?, 'MEMBER')",
                    room.conversationId(), member);
        }
        var rename = new LegacyV1RoomRenameService(
                new PostgresLegacyV1RoomRenameAdapter(dataSource()));
        assertEquals(LegacyV1RoomRenameResult.Rejected.ROOM_ADMIN_REQUIRED,
                rename.rename(new LegacyV1RoomRenameCommand(
                        member, room.legacyRoomId(), "Forbidden Room")));
        LegacyV1RoomRenameResult.Renamed first = assertInstanceOf(
                LegacyV1RoomRenameResult.Renamed.class,
                rename.rename(new LegacyV1RoomRenameCommand(
                        owner, room.legacyRoomId(), "  Renamed Room  ")));
        assertTrue(first.changed()); assertEquals("Original Room", first.oldName());
        assertEquals("Renamed Room", first.newName());
        LegacyV1RoomRenameResult.Renamed retry = assertInstanceOf(
                LegacyV1RoomRenameResult.Renamed.class,
                rename.rename(new LegacyV1RoomRenameCommand(
                        owner, room.legacyRoomId(), "Renamed Room")));
        assertFalse(retry.changed()); assertEquals(first.updatedAt(), retry.updatedAt());
        var search = new PostgresLegacyV1RoomSearchAdapter(dataSource())
                .search(owner, "Renamed Room", 10);
        assertEquals(1, search.size()); assertEquals(room.legacyRoomId(),
                search.getFirst().legacyRoomId());
        assertEquals("Renamed Room", search.getFirst().roomName());
    }

    @Test
    @Order(93)
    void changesV1NicknameAtomicallyWithCompleteRoomEffects() throws Exception {
        requireDatabase(); truncateApplicationData();
        UUID owner = UUID.randomUUID(), member = UUID.randomUUID();
        try (Connection connection = connect()) {
            execute(connection, "INSERT INTO chat.account(id, username_key, display_name, "
                    + "password_hash) VALUES (?, 'nickname-owner', 'Old Name', "
                    + "'$argon2id$v=19$m=65536,t=2,p=1$test$fixture'), "
                    + "(?, 'nickname-member', 'Member', "
                    + "'$argon2id$v=19$m=65536,t=2,p=1$test$fixture')", owner, member);
            execute(connection, "INSERT INTO chat.legacy_v1_account_map(legacy_user_id, "
                    + "account_id) VALUES (213, ?), (214, ?)", owner, member);
        }
        LegacyV1RoomCreationResult.Created active = (LegacyV1RoomCreationResult.Created)
                new PostgresLegacyV1RoomCreationAdapter(dataSource()).create(
                        new LegacyV1RoomCreationIntent(owner, "nickname-active-room",
                                "Active Room", java.util.Optional.empty()));
        LegacyV1RoomCreationResult.Created closed = (LegacyV1RoomCreationResult.Created)
                new PostgresLegacyV1RoomCreationAdapter(dataSource()).create(
                        new LegacyV1RoomCreationIntent(owner, "nickname-closed-room",
                                "Closed Room", java.util.Optional.empty()));
        try (Connection connection = connect()) {
            execute(connection, "INSERT INTO chat.conversation_member(conversation_id, "
                    + "account_id, role) VALUES (?, ?, 'MEMBER')",
                    active.conversationId(), member);
            execute(connection, "UPDATE chat.group_lifecycle SET closed_at = "
                    + "transaction_timestamp() WHERE conversation_id = ?",
                    closed.conversationId());
        }
        var service = new LegacyV1NicknameChangeService(
                new PostgresLegacyV1NicknameChangeAdapter(dataSource()));
        LegacyV1NicknameChangeResult.Changed first = assertInstanceOf(
                LegacyV1NicknameChangeResult.Changed.class,
                service.change(new LegacyV1NicknameChangeCommand(owner, "  New Name  ")));
        assertTrue(first.changed()); assertEquals("Old Name", first.oldDisplayName());
        assertEquals("New Name", first.newDisplayName());
        assertEquals(1, first.roomAudiences().size());
        assertEquals(active.legacyRoomId(), first.roomAudiences().getFirst().legacyRoomId());
        assertEquals(Set.of(owner, member), first.roomAudiences().getFirst().accountIds());
        assertEquals(1, count("SELECT count(*) FROM chat.account_display_name_change_audit "
                + "WHERE account_id = '" + owner + "' AND old_display_name = 'Old Name' "
                + "AND new_display_name = 'New Name'"));

        LegacyV1NicknameChangeResult.Changed retry = assertInstanceOf(
                LegacyV1NicknameChangeResult.Changed.class,
                service.change(new LegacyV1NicknameChangeCommand(owner, "New Name")));
        assertFalse(retry.changed()); assertTrue(retry.roomAudiences().isEmpty());
        assertEquals(first.changedAt(), retry.changedAt());
        assertEquals(1, count("SELECT count(*) FROM chat.account_display_name_change_audit "
                + "WHERE account_id = '" + owner + "'"));

        UUID unmapped = UUID.randomUUID();
        try (Connection connection = connect()) {
            execute(connection, "INSERT INTO chat.account(id, username_key, display_name, "
                    + "password_hash) VALUES (?, 'nickname-unmapped', 'Unmapped', "
                    + "'$argon2id$v=19$m=65536,t=2,p=1$test$fixture')", unmapped);
            execute(connection, "INSERT INTO chat.conversation_member(conversation_id, "
                    + "account_id, role) VALUES (?, ?, 'MEMBER')",
                    active.conversationId(), unmapped);
        }
        assertThrows(ConversationPersistenceException.class, () -> service.change(
                new LegacyV1NicknameChangeCommand(owner, "Must Roll Back")));
        assertEquals(1, count("SELECT count(*) FROM chat.account WHERE id = '" + owner
                + "' AND display_name = 'New Name'"));
        assertEquals(1, count("SELECT count(*) FROM chat.account_display_name_change_audit "
                + "WHERE account_id = '" + owner + "'"));
    }

    @Test
    @Order(93)
    void changesV1UsernameWithCooldownRetryAndPeerOnlyEffects() throws Exception {
        requireDatabase(); truncateApplicationData();
        UUID owner = UUID.randomUUID(), member = UUID.randomUUID();
        try (Connection connection = connect()) {
            execute(connection, "INSERT INTO chat.account(id, username_key, display_name, "
                    + "password_hash) VALUES (?, 'olduser', 'Owner', "
                    + "'$argon2id$v=19$m=65536,t=2,p=1$test$fixture'), "
                    + "(?, 'peerusr', 'Member', "
                    + "'$argon2id$v=19$m=65536,t=2,p=1$test$fixture')", owner, member);
            execute(connection, "INSERT INTO chat.legacy_v1_account_map(legacy_user_id, "
                    + "account_id) VALUES (215, ?), (216, ?)", owner, member);
        }
        LegacyV1RoomCreationResult.Created room = (LegacyV1RoomCreationResult.Created)
                new PostgresLegacyV1RoomCreationAdapter(dataSource()).create(
                        new LegacyV1RoomCreationIntent(owner, "username-room",
                                "Username Room", java.util.Optional.empty()));
        try (Connection connection = connect()) {
            execute(connection, "INSERT INTO chat.conversation_member(conversation_id, "
                    + "account_id, role) VALUES (?, ?, 'MEMBER')",
                    room.conversationId(), member);
        }
        var service = new LegacyV1UsernameChangeService(
                new PostgresLegacyV1UsernameChangeAdapter(dataSource()));
        LegacyV1UsernameChangeResult.Changed first = assertInstanceOf(
                LegacyV1UsernameChangeResult.Changed.class,
                service.change(new LegacyV1UsernameChangeCommand(owner, "  newuser  ")));
        assertTrue(first.changed()); assertEquals("olduser", first.oldUsername());
        assertEquals("newuser", first.newUsername());
        assertEquals(1, first.roomAudiences().size());
        assertEquals(Set.of(member), first.roomAudiences().getFirst().peerAccountIds());
        assertFalse(first.roomAudiences().getFirst().peerAccountIds().contains(owner));
        assertTrue(new PostgresLegacyV1AccountProjection(dataSource())
                .findByPresentedUsername("olduser").isEmpty());
        assertEquals(owner, new PostgresLegacyV1AccountProjection(dataSource())
                .findByPresentedUsername("newuser").orElseThrow().accountId());

        LegacyV1UsernameChangeResult.Changed retry = assertInstanceOf(
                LegacyV1UsernameChangeResult.Changed.class,
                service.change(new LegacyV1UsernameChangeCommand(owner, "newuser")));
        assertFalse(retry.changed()); assertTrue(retry.roomAudiences().isEmpty());
        assertEquals(first.changedAt(), retry.changedAt());
        assertInstanceOf(LegacyV1UsernameChangeResult.Cooldown.class,
                service.change(new LegacyV1UsernameChangeCommand(owner, "nextusr")));
        assertEquals(LegacyV1UsernameChangeResult.Rejected.SAME_AS_CURRENT,
                service.change(new LegacyV1UsernameChangeCommand(member, "peerusr")));
        assertEquals(LegacyV1UsernameChangeResult.Rejected.USERNAME_TAKEN,
                service.change(new LegacyV1UsernameChangeCommand(member, "newuser")));
        assertEquals(1, count("SELECT count(*) FROM chat.account_username_change_audit "
                + "WHERE account_id = '" + owner + "' AND old_username = 'olduser' "
                + "AND new_username = 'newuser'"));
    }

    @Test
    @Order(93)
    void commitsMetadataOnlyProfileImagesWithAuthorizationAndCleanupIntent()
            throws Exception {
        requireDatabase(); truncateApplicationData();
        UUID owner = UUID.randomUUID(), member = UUID.randomUUID();
        try (Connection connection = connect()) {
            execute(connection, "INSERT INTO chat.account(id, username_key, display_name, "
                    + "password_hash) VALUES (?, 'avatar_owner', 'Avatar Owner', "
                    + "'$argon2id$v=19$m=65536,t=2,p=1$test$fixture'), "
                    + "(?, 'avatar_member', 'Avatar Member', "
                    + "'$argon2id$v=19$m=65536,t=2,p=1$test$fixture')", owner, member);
            execute(connection, "INSERT INTO chat.legacy_v1_account_map(legacy_user_id, "
                    + "account_id) VALUES (217, ?), (218, ?)", owner, member);
        }
        LegacyV1RoomCreationResult.Created room = (LegacyV1RoomCreationResult.Created)
                new PostgresLegacyV1RoomCreationAdapter(dataSource()).create(
                        new LegacyV1RoomCreationIntent(owner, "avatar-room", "Avatar Room",
                                java.util.Optional.empty()));
        try (Connection connection = connect()) {
            execute(connection, "INSERT INTO chat.conversation_member(conversation_id, "
                    + "account_id, role) VALUES (?, ?, 'MEMBER')",
                    room.conversationId(), member);
        }
        var adapter = new PostgresProfileImageMetadataAdapter(dataSource());
        var guard = new PostgresProfileImageMutationGuardAdapter(dataSource());
        assertEquals(ProfileImageMutationAuthorization.AUTHORIZED,
                guard.authorize(new ProfileImageTarget.Account(owner)));
        assertEquals(ProfileImageMutationAuthorization.AUTHORIZED,
                guard.authorize(new ProfileImageTarget.LegacyRoom(owner, room.legacyRoomId())));
        assertEquals(ProfileImageMutationAuthorization.ROOM_ADMIN_REQUIRED,
                guard.authorize(new ProfileImageTarget.LegacyRoom(member, room.legacyRoomId())));
        ProfileImageObjectEvidence firstObject = profileImageEvidence(1, 9);
        ProfileImageMetadataResult.Committed first = assertInstanceOf(
                ProfileImageMetadataResult.Committed.class,
                adapter.commit(new ProfileImageMetadataCommand(
                        new ProfileImageTarget.Account(owner), firstObject, 256, 256)));
        assertTrue(first.changed()); assertEquals(1, first.version());
        assertTrue(first.cleanupObjectKey().isEmpty()); assertTrue(first.roomPeerAccountIds().isEmpty());
        var reads = new PostgresProfileImageReadAdapter(dataSource());
        ProfileImageReadResult.Found accountRead = assertInstanceOf(
                ProfileImageReadResult.Found.class, reads.read(
                        new ProfileImageReadTarget.AccountByUsername(member, "avatar_owner")));
        assertEquals(firstObject.objectKey(), accountRead.object().objectKey());
        assertEquals(1, accountRead.version());
        assertEquals(ProfileImageReadResult.Missing.INSTANCE, reads.read(
                new ProfileImageReadTarget.AccountByUsername(owner, "avatar_member")));

        ProfileImageMetadataResult.Committed retry = assertInstanceOf(
                ProfileImageMetadataResult.Committed.class,
                adapter.commit(new ProfileImageMetadataCommand(
                        new ProfileImageTarget.Account(owner), firstObject, 256, 256)));
        assertFalse(retry.changed()); assertEquals(first.updatedAt(), retry.updatedAt());
        assertEquals(1, count("SELECT count(*) FROM chat.profile_image_change_audit "
                + "WHERE target_account_id = '" + owner + "'"));
        assertEquals(ProfileImageMetadataResult.Rejected.OBJECT_EVIDENCE_CONFLICT,
                adapter.commit(new ProfileImageMetadataCommand(
                        new ProfileImageTarget.Account(owner),
                        new ProfileImageObjectEvidence(firstObject.objectKey(), 10,
                                firstObject.contentSha256(), "image/png"), 256, 256)));

        ProfileImageObjectEvidence shared = profileImageEvidence(2, 10);
        ProfileImageMetadataResult.Committed replacement = assertInstanceOf(
                ProfileImageMetadataResult.Committed.class,
                adapter.commit(new ProfileImageMetadataCommand(
                        new ProfileImageTarget.Account(owner), shared, 128, 128)));
        assertEquals(2, replacement.version());
        assertEquals(java.util.Optional.of(firstObject.objectKey()),
                replacement.cleanupObjectKey());
        assertEquals(1, count("SELECT count(*) FROM chat.profile_image_object WHERE object_key = '"
                + firstObject.objectKey() + "' AND cleanup_requested_at IS NOT NULL "
                + "AND delete_confirmed_at IS NULL"));

        assertEquals(ProfileImageMetadataResult.Rejected.ROOM_ADMIN_REQUIRED,
                adapter.commit(new ProfileImageMetadataCommand(
                        new ProfileImageTarget.LegacyRoom(member, room.legacyRoomId()),
                        shared, 128, 128)));
        ProfileImageMetadataResult.Committed roomFirst = assertInstanceOf(
                ProfileImageMetadataResult.Committed.class,
                adapter.commit(new ProfileImageMetadataCommand(
                        new ProfileImageTarget.LegacyRoom(owner, room.legacyRoomId()),
                        shared, 128, 128)));
        assertTrue(roomFirst.changed()); assertEquals(Set.of(member), roomFirst.roomPeerAccountIds());
        ProfileImageReadResult.Found roomRead = assertInstanceOf(
                ProfileImageReadResult.Found.class, reads.read(
                        new ProfileImageReadTarget.LegacyRoom(member, room.legacyRoomId())));
        assertEquals(shared.objectKey(), roomRead.object().objectKey());
        assertEquals(1, roomRead.version());

        ProfileImageObjectEvidence roomReplacement = profileImageEvidence(3, 11);
        ProfileImageMetadataResult.Committed roomChanged = assertInstanceOf(
                ProfileImageMetadataResult.Committed.class,
                adapter.commit(new ProfileImageMetadataCommand(
                        new ProfileImageTarget.LegacyRoom(owner, room.legacyRoomId()),
                        roomReplacement, 64, 64)));
        assertTrue(roomChanged.cleanupObjectKey().isEmpty());
        assertEquals(2, roomChanged.version());
        assertEquals(0, count("SELECT count(*) FROM chat.profile_image_object WHERE object_key = '"
                + shared.objectKey() + "' AND cleanup_requested_at IS NOT NULL"));

        ProfileImageObjectEvidence orphan = profileImageEvidence(5, 13);
        guard.requestIfUnreferenced(orphan);
        assertEquals(1, count("SELECT count(*) FROM chat.profile_image_object WHERE object_key = '"
                + orphan.objectKey() + "' AND cleanup_requested_at IS NOT NULL "
                + "AND delete_confirmed_at IS NULL"));
        try (Connection connection = connect()) {
            SQLException unpairedClaim = assertThrows(SQLException.class, () -> execute(connection,
                    "UPDATE chat.profile_image_object SET delete_claim_id = ? "
                            + "WHERE object_key = ?", UUID.randomUUID(), orphan.objectKey()));
            assertEquals("23514", unpairedClaim.getSQLState());
        }
        guard.requestIfUnreferenced(shared);
        assertEquals(0, count("SELECT count(*) FROM chat.profile_image_object WHERE object_key = '"
                + shared.objectKey() + "' AND cleanup_requested_at IS NOT NULL"));
        assertThrows(ConversationPersistenceException.class,
                () -> guard.requestIfUnreferenced(new ProfileImageObjectEvidence(
                        orphan.objectKey(), 14, orphan.contentSha256(), "image/png")));

        var cleanup = new PostgresProfileImageCleanupAdapter(dataSource());
        Instant claimTime = Instant.now().plusSeconds(60);
        ProfileImageCleanupClaim firstClaim = cleanup.claim(claimTime, claimTime,
                claimTime, 10).stream().filter(value ->
                        value.objectKey().equals(orphan.objectKey())).findFirst().orElseThrow();
        assertEquals(ProfileImageMetadataResult.Rejected.OBJECT_EVIDENCE_CONFLICT,
                adapter.commit(new ProfileImageMetadataCommand(
                        new ProfileImageTarget.Account(owner), orphan, 32, 32)));
        assertFalse(cleanup.release(new ProfileImageCleanupClaim(UUID.randomUUID(),
                firstClaim.objectKey(), firstClaim.claimedAt())));
        assertTrue(cleanup.release(firstClaim));
        ProfileImageCleanupClaim reclaimed = cleanup.claim(claimTime, claimTime,
                claimTime.plusSeconds(1), 10).stream().filter(value ->
                        value.objectKey().equals(orphan.objectKey())).findFirst().orElseThrow();
        assertNotEquals(firstClaim.claimId(), reclaimed.claimId());
        assertTrue(cleanup.confirmDeleted(reclaimed, claimTime.plusSeconds(2)));
        assertFalse(cleanup.confirmDeleted(reclaimed, claimTime.plusSeconds(3)));
        assertEquals(1, count("SELECT count(*) FROM chat.profile_image_object WHERE object_key = '"
                + orphan.objectKey() + "' AND delete_confirmed_at IS NOT NULL"));
        ProfileImageMetadataResult.Committed revived = assertInstanceOf(
                ProfileImageMetadataResult.Committed.class,
                adapter.commit(new ProfileImageMetadataCommand(
                        new ProfileImageTarget.Account(owner), orphan, 32, 32)));
        assertTrue(revived.changed());
        assertEquals(1, count("SELECT count(*) FROM chat.profile_image_object WHERE object_key = '"
                + orphan.objectKey() + "' AND cleanup_requested_at IS NULL "
                + "AND delete_claim_id IS NULL AND delete_confirmed_at IS NULL"));
        assertTrue(cleanup.claim(claimTime.plusSeconds(10), claimTime.plusSeconds(10),
                claimTime.plusSeconds(10), 10).stream().noneMatch(value ->
                        value.objectKey().equals(orphan.objectKey())));

        UUID unmapped = UUID.randomUUID();
        try (Connection connection = connect()) {
            execute(connection, "INSERT INTO chat.account(id, username_key, display_name, "
                    + "password_hash) VALUES (?, 'avatar_unmapped', 'Unmapped', "
                    + "'$argon2id$v=19$m=65536,t=2,p=1$test$fixture')", unmapped);
            execute(connection, "INSERT INTO chat.conversation_member(conversation_id, "
                    + "account_id, role) VALUES (?, ?, 'MEMBER')",
                    room.conversationId(), unmapped);
        }
        assertEquals(ProfileImageMutationAuthorization.ACCOUNT_UNAVAILABLE,
                guard.authorize(new ProfileImageTarget.Account(unmapped)));
        ProfileImageObjectEvidence blocked = profileImageEvidence(4, 12);
        assertThrows(ConversationPersistenceException.class, () -> adapter.commit(
                new ProfileImageMetadataCommand(
                        new ProfileImageTarget.LegacyRoom(owner, room.legacyRoomId()),
                        blocked, 32, 32)));
        assertEquals(0, count("SELECT count(*) FROM chat.profile_image_object WHERE object_key = '"
                + blocked.objectKey() + "'"));
        assertEquals(1, count("SELECT count(*) FROM chat.group_profile_image WHERE "
                + "conversation_id = '" + room.conversationId() + "' AND version = 2"));
    }

    private static ProfileImageObjectEvidence profileImageEvidence(int marker, long size) {
        byte[] digest = new byte[32]; digest[31] = (byte) marker;
        return new ProfileImageObjectEvidence(ProfileImageObjectEvidence.objectKey(digest),
                size, digest, "image/png");
    }

    @Test
    @Order(93)
    void previewsHistoricalProfileImagesBeforeAnyProviderWrite() throws Exception {
        requireDatabase(); truncateApplicationData();
        UUID owner = UUID.randomUUID(), noAvatar = UUID.randomUUID();
        try (Connection connection = connect()) {
            execute(connection, "INSERT INTO chat.account(id, username_key, display_name, "
                    + "password_hash) VALUES (?, 'avatar_import_owner', 'Import Owner', "
                    + "'$argon2id$v=19$m=65536,t=2,p=1$test$fixture'), "
                    + "(?, 'avatar_import_absent', 'Import Absent', "
                    + "'$argon2id$v=19$m=65536,t=2,p=1$test$fixture')", owner, noAvatar);
            execute(connection, "INSERT INTO chat.legacy_v1_account_map(legacy_user_id, "
                    + "account_id) VALUES (301, ?), (302, ?)", owner, noAvatar);
        }
        LegacyV1RoomCreationResult.Created room = (LegacyV1RoomCreationResult.Created)
                new PostgresLegacyV1RoomCreationAdapter(dataSource()).create(
                        new LegacyV1RoomCreationIntent(owner, "avatar-import-room",
                                "Avatar Import Room", java.util.Optional.empty()));
        ProfileImageObjectEvidence shared = profileImageEvidence(31, 19);
        V1ProfileImageImportPlan plan = new V1ProfileImageImportPlan(
                "a".repeat(64), "b".repeat(64), "c".repeat(64), List.of(
                        new V1ProfileImageImportEntry(
                                V1ProfileImageImportEntry.Kind.ACCOUNT, 301,
                                shared, 16, 16, Instant.parse("2026-08-13T01:00:00Z")),
                        new V1ProfileImageImportEntry(
                                V1ProfileImageImportEntry.Kind.ACCOUNT, 302,
                                null, 0, 0, null),
                        new V1ProfileImageImportEntry(
                                V1ProfileImageImportEntry.Kind.ROOM, room.legacyRoomId(),
                                shared, 16, 16, Instant.parse("2026-08-13T02:00:00Z"))), 1);
        var planner = new PostgresV1ProfileImageImportPlanner(dataSource());
        var missingObject = planner.preview(plan);
        assertTrue(missingObject.readyForProviderWrites());
        assertEquals(0, missingObject.objectsAlreadyRegistered());
        assertEquals(1, missingObject.providerObjectsToVerify());

        try (Connection connection = connect()) {
            execute(connection, "INSERT INTO chat.profile_image_object(object_key, byte_size, "
                    + "content_sha256, media_type) VALUES (?, ?, ?, 'image/png')",
                    shared.objectKey(), shared.byteSize(), shared.contentSha256());
        }
        var registered = planner.preview(plan);
        assertTrue(registered.readyForProviderWrites());
        assertEquals(1, registered.objectsAlreadyRegistered());
        assertEquals(1, registered.providerObjectsToVerify());

        var importer = new PostgresV1ProfileImageImporter(dataSource());
        var applied = importer.apply(ProviderVerifiedV1ProfileImageImportInput.confirm(
                plan, List.of(shared)));
        assertFalse(applied.alreadyApplied()); assertEquals(2, applied.insertedPointers());
        assertEquals(1, count("SELECT count(*) FROM chat.profile_image_import_run"));
        assertEquals(3, count("SELECT count(*) FROM chat.profile_image_import_entry"));
        assertEquals(1, count("SELECT count(*) FROM chat.profile_image_import_entry "
                + "WHERE target_account_id = '" + noAvatar + "' AND object_key IS NULL "
                + "AND width = 0 AND height = 0 AND source_updated_at IS NULL"));
        assertEquals(0, count("SELECT count(*) FROM chat.profile_image_change_audit"));
        assertEquals(1, count("SELECT count(*) FROM chat.account_profile_image "
                + "WHERE account_id = '" + owner + "' AND version = 1"));
        assertEquals(1, count("SELECT count(*) FROM chat.group_profile_image "
                + "WHERE conversation_id = '" + room.conversationId() + "' AND version = 1"));

        new PostgresMigrator(URL, USER, PASSWORD).validate();
        var retry = new PostgresV1ProfileImageImporter(dataSource()).apply(
                ProviderVerifiedV1ProfileImageImportInput.confirm(plan, List.of(shared)));
        assertTrue(retry.alreadyApplied()); assertEquals(0, retry.insertedPointers());
        assertEquals(applied.importRunId(), retry.importRunId());
        assertEquals(1, count("SELECT count(*) FROM chat.profile_image_import_run"));
        assertEquals(3, count("SELECT count(*) FROM chat.profile_image_import_entry"));

        var conflict = planner.preview(plan);
        assertFalse(conflict.readyForProviderWrites());
        assertTrue(conflict.issues().stream().anyMatch(issue ->
                issue.code().equals("TARGET_POINTER_EXISTS")));

        ProfileImageObjectEvidence unowned = profileImageEvidence(32, 20);
        V1ProfileImageImportPlan missingMapping = new V1ProfileImageImportPlan(
                "d".repeat(64), "b".repeat(64), "c".repeat(64), List.of(
                        new V1ProfileImageImportEntry(
                                V1ProfileImageImportEntry.Kind.ACCOUNT, 999,
                                unowned, 8, 8,
                                Instant.parse("2026-08-13T03:00:00Z"))), 1);
        assertTrue(planner.preview(missingMapping).issues().stream().anyMatch(issue ->
                issue.code().equals("TARGET_MAPPING_MISSING")));
        assertThrows(V1ProfileImageImportException.class, () -> importer.apply(
                ProviderVerifiedV1ProfileImageImportInput.confirm(
                        missingMapping, List.of(unowned))));
        assertEquals(0, count("SELECT count(*) FROM chat.profile_image_object WHERE object_key = '"
                + unowned.objectKey() + "'"));
        assertEquals(1, count("SELECT count(*) FROM chat.profile_image_import_run"));
    }

    @Test
    @Order(93)
    void updatesV1RoomPasswordsConvergentlyAndKeepsJoinCredentialAuthoritative()
            throws Exception {
        requireDatabase(); truncateApplicationData();
        UUID owner = UUID.randomUUID(), member = UUID.randomUUID();
        try (Connection connection = connect()) {
            execute(connection, "INSERT INTO chat.account(id, username_key, display_name, "
                    + "password_hash) VALUES (?, 'password-owner', 'Password Owner', "
                    + "'$argon2id$v=19$m=65536,t=2,p=1$test$fixture'), "
                    + "(?, 'password-member', 'Password Member', "
                    + "'$argon2id$v=19$m=65536,t=2,p=1$test$fixture')", owner, member);
            execute(connection, "INSERT INTO chat.legacy_v1_account_map(legacy_user_id, "
                    + "account_id) VALUES (221, ?), (222, ?)", owner, member);
        }
        LegacyV1RoomCreationResult.Created room = (LegacyV1RoomCreationResult.Created)
                new PostgresLegacyV1RoomCreationAdapter(dataSource()).create(
                        new LegacyV1RoomCreationIntent(owner, "password-room-create",
                                "Password Room", Optional.empty()));
        try (Connection connection = connect()) {
            execute(connection, "INSERT INTO chat.conversation_member(conversation_id, "
                    + "account_id, role) VALUES (?, ?, 'MEMBER')",
                    room.conversationId(), member);
        }
        var adapter = new PostgresLegacyV1RoomPasswordAdapter(dataSource());
        assertEquals(LegacyV1RoomPasswordStatusResult.Rejected.ROOM_ADMIN_REQUIRED,
                adapter.status(member, room.legacyRoomId()));
        assertEquals(LegacyV1RoomPasswordUpdateResult.Rejected.ROOM_ADMIN_REQUIRED,
                adapter.update(new LegacyV1RoomPasswordIntent(member,
                        room.legacyRoomId(), Optional.empty())));
        try (Connection connection = connect()) {
            execute(connection, "UPDATE chat.conversation_member SET left_at = "
                    + "transaction_timestamp() WHERE conversation_id = ? AND account_id = ?",
                    room.conversationId(), member);
        }
        var open = assertInstanceOf(LegacyV1RoomPasswordStatusResult.Authorized.class,
                adapter.status(owner, room.legacyRoomId()));
        assertFalse(open.hasPassword());

        var firstEncoding = new LegacyV1RoomPasswordEncoding(
                "$argon2id$v=19$m=65536,t=2,p=1$c2FsdDE$Zmlyc3Q",
                "hmac-sha256:v1:" + "D".repeat(43));
        var first = assertInstanceOf(LegacyV1RoomPasswordUpdateResult.Updated.class,
                adapter.update(new LegacyV1RoomPasswordIntent(owner,
                        room.legacyRoomId(), Optional.of(firstEncoding))));
        assertTrue(first.changed()); assertTrue(first.hasPassword());
        var retry = assertInstanceOf(LegacyV1RoomPasswordUpdateResult.Updated.class,
                adapter.update(new LegacyV1RoomPasswordIntent(owner,
                        room.legacyRoomId(), Optional.of(firstEncoding))));
        assertFalse(retry.changed()); assertEquals(first.updatedAt(), retry.updatedAt());
        var candidate = assertInstanceOf(LegacyV1RoomJoinAccess.Candidate.class,
                new PostgresLegacyV1RoomJoinAdapter(dataSource())
                        .inspect(member, room.legacyRoomId()));
        assertEquals(Optional.of(new StoredCredential.Argon2id(firstEncoding.encodedHash())),
                candidate.joinCredential());

        var replacement = new LegacyV1RoomPasswordEncoding(
                "$argon2id$v=19$m=65536,t=2,p=1$c2FsdDI$cmVwbGFjZWQ",
                "hmac-sha256:v1:" + "E".repeat(43));
        var replaced = assertInstanceOf(LegacyV1RoomPasswordUpdateResult.Updated.class,
                adapter.update(new LegacyV1RoomPasswordIntent(owner,
                        room.legacyRoomId(), Optional.of(replacement))));
        assertTrue(replaced.changed()); assertTrue(replaced.hasPassword());
        candidate = assertInstanceOf(LegacyV1RoomJoinAccess.Candidate.class,
                new PostgresLegacyV1RoomJoinAdapter(dataSource())
                        .inspect(member, room.legacyRoomId()));
        assertEquals(Optional.of(new StoredCredential.Argon2id(replacement.encodedHash())),
                candidate.joinCredential());

        var cleared = assertInstanceOf(LegacyV1RoomPasswordUpdateResult.Updated.class,
                adapter.update(new LegacyV1RoomPasswordIntent(
                        owner, room.legacyRoomId(), Optional.empty())));
        assertTrue(cleared.changed()); assertFalse(cleared.hasPassword());
        var clearRetry = assertInstanceOf(LegacyV1RoomPasswordUpdateResult.Updated.class,
                adapter.update(new LegacyV1RoomPasswordIntent(
                        owner, room.legacyRoomId(), Optional.empty())));
        assertFalse(clearRetry.changed()); assertFalse(clearRetry.hasPassword());
        candidate = assertInstanceOf(LegacyV1RoomJoinAccess.Candidate.class,
                new PostgresLegacyV1RoomJoinAdapter(dataSource())
                        .inspect(member, room.legacyRoomId()));
        assertEquals(Optional.empty(), candidate.joinCredential());

        try (Connection connection = connect()) {
            execute(connection, "INSERT INTO chat.group_join_credential("
                    + "conversation_id, encoded_password) VALUES (?, ?)",
                    room.conversationId(), firstEncoding.encodedHash());
            SQLException invalidTag = assertThrows(SQLException.class, () -> execute(connection,
                    "UPDATE chat.group_join_credential SET password_idempotency_tag = "
                            + "'plain-sha256' WHERE conversation_id = ?",
                    room.conversationId()));
            assertEquals("23514", invalidTag.getSQLState());
        }
        var legacyCredentialUpgrade = assertInstanceOf(
                LegacyV1RoomPasswordUpdateResult.Updated.class,
                adapter.update(new LegacyV1RoomPasswordIntent(owner,
                        room.legacyRoomId(), Optional.of(firstEncoding))));
        assertTrue(legacyCredentialUpgrade.changed());
        assertEquals(1, count("SELECT count(*) FROM chat.group_join_credential "
                + "WHERE conversation_id = '" + room.conversationId() + "' "
                + "AND password_idempotency_tag = '" + firstEncoding.idempotencyTag() + "'"));
    }

    private static LegacyV1RoomMessageDeletionResult.Deleted deleted(
            LegacyV1RoomMessageDeletionResult result) {
        return assertInstanceOf(LegacyV1RoomMessageDeletionResult.Deleted.class, result);
    }

    @Test
    @Order(94)
    void dissolvesV1RoomsAtomicallyAndConvergesOnlyForTheOriginalActor()
            throws Exception {
        requireDatabase(); truncateApplicationData();
        UUID owner = UUID.randomUUID(), admin = UUID.randomUUID(), member = UUID.randomUUID();
        UUID device = UUID.randomUUID(), attachment = UUID.randomUUID();
        try (Connection connection = connect()) {
            execute(connection, "INSERT INTO chat.account(id, username_key, display_name, "
                    + "password_hash) VALUES (?, 'dissolve-owner', 'Dissolve Owner', "
                    + "'$argon2id$v=19$m=65536,t=2,p=1$test$fixture'), "
                    + "(?, 'dissolve-admin', 'Dissolve Admin', "
                    + "'$argon2id$v=19$m=65536,t=2,p=1$test$fixture'), "
                    + "(?, 'dissolve-member', 'Dissolve Member', "
                    + "'$argon2id$v=19$m=65536,t=2,p=1$test$fixture')",
                    owner, admin, member);
            execute(connection, "INSERT INTO chat.legacy_v1_account_map(legacy_user_id, "
                    + "account_id) VALUES (231, ?), (232, ?), (233, ?)", owner, admin, member);
            execute(connection, "INSERT INTO chat.device(id, account_id, client_device_id, platform) "
                    + "VALUES (?, ?, 'dissolution-device', 'WEB')", device, owner);
        }
        LegacyV1RoomCreationResult.Created room = (LegacyV1RoomCreationResult.Created)
                new PostgresLegacyV1RoomCreationAdapter(dataSource()).create(
                        new LegacyV1RoomCreationIntent(owner, "dissolution-room-create",
                                "Dissolution Room", Optional.empty()));
        try (Connection connection = connect()) {
            execute(connection, "INSERT INTO chat.conversation_member(conversation_id, "
                    + "account_id, role) VALUES (?, ?, 'ADMIN'), (?, ?, 'MEMBER')",
                    room.conversationId(), admin, room.conversationId(), member);
            execute(connection, "INSERT INTO chat.group_join_credential(conversation_id, "
                    + "encoded_password, password_idempotency_tag) VALUES (?, "
                    + "'$argon2id$v=19$m=65536,t=2,p=1$c2FsdA$aGFzaA', ?)",
                    room.conversationId(), "hmac-sha256:v1:" + "F".repeat(43));
            execute(connection, "INSERT INTO chat.attachment(id, conversation_id, "
                    + "owner_account_id, owner_device_id, client_attachment_id, object_key, "
                    + "file_name, media_type, byte_size, content_sha256, state, ready_at) "
                    + "VALUES (?, ?, ?, ?, 'dissolution-file', 'rooms/dissolution/file', "
                    + "'evidence.txt', 'text/plain', 8, ?, 'READY', transaction_timestamp())",
                    attachment, room.conversationId(), owner, device, new byte[32]);
        }

        var adapter = new PostgresLegacyV1RoomDissolutionAdapter(dataSource());
        assertEquals(LegacyV1RoomDissolutionResult.Rejected.ROOM_ADMIN_REQUIRED,
                adapter.dissolve(new LegacyV1RoomDissolutionIntent(member, room.legacyRoomId())));
        var first = assertInstanceOf(LegacyV1RoomDissolutionResult.Dissolved.class,
                adapter.dissolve(new LegacyV1RoomDissolutionIntent(owner, room.legacyRoomId())));
        assertTrue(first.changed()); assertEquals("Dissolution Room", first.roomName());
        assertEquals(Set.of(owner, admin, member), first.affectedAccountIds());
        assertEquals(0, count("SELECT count(*) FROM chat.conversation_member WHERE "
                + "conversation_id = '" + room.conversationId() + "' AND left_at IS NULL"));
        assertEquals(1, count("SELECT count(*) FROM chat.group_lifecycle WHERE "
                + "conversation_id = '" + room.conversationId() + "' AND closed_at IS NOT NULL"));
        assertEquals(0, count("SELECT count(*) FROM chat.group_join_credential WHERE "
                + "conversation_id = '" + room.conversationId() + "'"));
        assertEquals(1, count("SELECT count(*) FROM chat.attachment WHERE id = '"
                + attachment + "' AND state = 'REVOKED' AND revoked_at IS NOT NULL "
                + "AND object_deleted_at IS NULL"));
        assertEquals(1, count("SELECT count(*) FROM chat.conversation WHERE id = '"
                + room.conversationId() + "'"));

        var retry = assertInstanceOf(LegacyV1RoomDissolutionResult.Dissolved.class,
                adapter.dissolve(new LegacyV1RoomDissolutionIntent(owner, room.legacyRoomId())));
        assertFalse(retry.changed()); assertTrue(retry.affectedAccountIds().isEmpty());
        assertEquals(first.dissolvedAt(), retry.dissolvedAt());
        assertEquals(LegacyV1RoomDissolutionResult.Rejected.NOT_FOUND,
                adapter.dissolve(new LegacyV1RoomDissolutionIntent(admin, room.legacyRoomId())));
        assertEquals(1, count("SELECT count(*) FROM chat.legacy_v1_room_dissolution WHERE "
                + "conversation_id = '" + room.conversationId() + "' "
                + "AND actor_account_id = '" + owner + "'"));

        UUID unmapped = UUID.randomUUID();
        LegacyV1RoomCreationResult.Created rollbackRoom =
                (LegacyV1RoomCreationResult.Created)
                        new PostgresLegacyV1RoomCreationAdapter(dataSource()).create(
                                new LegacyV1RoomCreationIntent(owner,
                                        "dissolution-rollback-create",
                                        "Rollback Room", Optional.empty()));
        try (Connection connection = connect()) {
            execute(connection, "INSERT INTO chat.account(id, username_key, display_name, "
                    + "password_hash) VALUES (?, 'dissolve-unmapped', 'Unmapped', "
                    + "'$argon2id$v=19$m=65536,t=2,p=1$test$fixture')", unmapped);
            execute(connection, "INSERT INTO chat.conversation_member(conversation_id, "
                    + "account_id, role) VALUES (?, ?, 'MEMBER')",
                    rollbackRoom.conversationId(), unmapped);
        }
        assertThrows(ConversationPersistenceException.class, () -> adapter.dissolve(
                new LegacyV1RoomDissolutionIntent(owner, rollbackRoom.legacyRoomId())));
        assertEquals(1, count("SELECT count(*) FROM chat.group_lifecycle WHERE "
                + "conversation_id = '" + rollbackRoom.conversationId()
                + "' AND closed_at IS NULL"));
        assertEquals(2, count("SELECT count(*) FROM chat.conversation_member WHERE "
                + "conversation_id = '" + rollbackRoom.conversationId()
                + "' AND left_at IS NULL"));
        assertEquals(0, count("SELECT count(*) FROM chat.legacy_v1_room_dissolution WHERE "
                + "conversation_id = '" + rollbackRoom.conversationId() + "'"));
    }

    @Test
    @Order(95)
    void replacesV1PasswordsAndRevokesOtherSessionsAtomically() throws Exception {
        requireDatabase(); truncateApplicationData();
        UUID account = UUID.randomUUID(), retainedDevice = UUID.randomUUID();
        UUID otherDevice = UUID.randomUUID(), retainedSession = UUID.randomUUID();
        UUID otherSession = UUID.randomUUID();
        String originalHash = "$argon2id$v=19$m=65536,t=2,p=1$b2xk$Y3JlZGVudGlhbA";
        String replacementHash = "$argon2id$v=19$m=65536,t=2,p=1$bmV3$Y3JlZGVudGlhbA";
        try (Connection connection = connect()) {
            execute(connection, "INSERT INTO chat.account(id, username_key, display_name, "
                    + "password_hash) VALUES (?, 'password-change-owner', 'Password Change', ?)",
                    account, originalHash);
            execute(connection, "INSERT INTO chat.legacy_v1_account_map(legacy_user_id, "
                    + "account_id) VALUES (241, ?)", account);
            execute(connection, "INSERT INTO chat.device(id, account_id, client_device_id, "
                    + "platform) VALUES (?, ?, 'password-retained', 'WEB'), "
                    + "(?, ?, 'password-other', 'WINDOWS')",
                    retainedDevice, account, otherDevice, account);
            execute(connection, "INSERT INTO chat.device_session(id, account_id, device_id, "
                    + "token_sha256, expires_at) VALUES (?, ?, ?, ?, "
                    + "transaction_timestamp() + interval '1 day'), (?, ?, ?, ?, "
                    + "transaction_timestamp() + interval '1 day')",
                    retainedSession, account, retainedDevice, new byte[32],
                    otherSession, account, otherDevice, filledBytes(32, (byte) 1));
        }

        var adapter = new PostgresLegacyV1PasswordChangeAdapter(dataSource());
        assertEquals(LegacyV1PasswordChangeAccess.Rejected.SESSION_INVALID,
                adapter.inspect(account, UUID.randomUUID()));
        var access = assertInstanceOf(LegacyV1PasswordChangeAccess.Candidate.class,
                adapter.inspect(account, retainedSession));
        assertEquals(new StoredCredential.Argon2id(originalHash), access.credential());

        var updated = assertInstanceOf(LegacyV1PasswordChangePersistenceResult.Updated.class,
                adapter.replace(new LegacyV1PasswordChangeIntent(account, retainedSession,
                        access.credential(), new StoredCredential.Argon2id(replacementHash))));
        assertEquals(1, updated.otherSessionsRevoked());
        assertEquals(new StoredCredential.Argon2id(replacementHash),
                assertInstanceOf(LegacyV1PasswordChangeAccess.Candidate.class,
                        adapter.inspect(account, retainedSession)).credential());
        assertEquals(LegacyV1PasswordChangeAccess.Rejected.SESSION_INVALID,
                adapter.inspect(account, otherSession));
        assertEquals(1, count("SELECT count(*) FROM chat.device_session WHERE id = '"
                + retainedSession + "' AND revoked_at IS NULL"));
        assertEquals(1, count("SELECT count(*) FROM chat.device_session WHERE id = '"
                + otherSession + "' AND revoked_at IS NOT NULL"));
        assertEquals(1, count("SELECT count(*) FROM chat.account_password_change_audit WHERE "
                + "account_id = '" + account + "' AND initiating_session_id = '"
                + retainedSession + "' AND other_sessions_revoked = 1"));
        assertEquals(updated.changedAt(), assertInstanceOf(
                LegacyV1PasswordChangeAccess.Candidate.class,
                adapter.inspect(account, retainedSession)).passwordChangedAt());

        assertEquals(LegacyV1PasswordChangePersistenceResult.Rejected.CONCURRENT_CHANGE,
                adapter.replace(new LegacyV1PasswordChangeIntent(account, retainedSession,
                        access.credential(), new StoredCredential.Argon2id(replacementHash))));
        assertEquals(1, count("SELECT count(*) FROM chat.account_password_change_audit WHERE "
                + "account_id = '" + account + "'"));
    }

    @Test
    @Order(96)
    void registersV1AccountsConcurrentlyWithOneStableNumericMapping() throws Exception {
        requireDatabase(); truncateApplicationData();
        var adapter = new PostgresLegacyV1RegistrationAdapter(dataSource());
        var intent = new LegacyV1RegistrationIntent("runtime_01", "Runtime User",
                new StoredCredential.Argon2id(
                        "$argon2id$v=19$m=65536,t=2,p=1$cnVudGltZQ$cmVnaXN0ZXJlZA"));
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2), start = new CountDownLatch(1);
        try {
            var task = (java.util.concurrent.Callable<LegacyV1RegistrationPersistenceResult>) () -> {
                ready.countDown(); assertTrue(start.await(5, TimeUnit.SECONDS));
                return adapter.register(intent);
            };
            Future<LegacyV1RegistrationPersistenceResult> first = executor.submit(task);
            Future<LegacyV1RegistrationPersistenceResult> second = executor.submit(task);
            assertTrue(ready.await(5, TimeUnit.SECONDS)); start.countDown();
            var results = List.of(first.get(10, TimeUnit.SECONDS),
                    second.get(10, TimeUnit.SECONDS));
            var created = results.stream().filter(
                    LegacyV1RegistrationPersistenceResult.Created.class::isInstance)
                    .map(LegacyV1RegistrationPersistenceResult.Created.class::cast).toList();
            var existing = results.stream().filter(
                    LegacyV1RegistrationPersistenceResult.Existing.class::isInstance)
                    .map(LegacyV1RegistrationPersistenceResult.Existing.class::cast).toList();
            assertEquals(1, created.size()); assertEquals(1, existing.size());
            assertEquals(created.getFirst().accountId(), existing.getFirst().accountId());
            assertEquals(OptionalLong.of(created.getFirst().legacyUserId()),
                    existing.getFirst().legacyUserId());
            assertTrue(created.getFirst().legacyUserId() > 0);
        } finally { executor.shutdownNow(); }
        assertEquals(1, count("SELECT count(*) FROM chat.account WHERE "
                + "username_key = 'runtime_01' AND display_name = 'Runtime User' "
                + "AND password_scheme = 'ARGON2ID' AND legacy_password_salt IS NULL"));
        assertEquals(1, count("SELECT count(*) FROM chat.legacy_v1_registration_audit audit "
                + "JOIN chat.legacy_v1_account_map mapping "
                + "ON mapping.account_id = audit.account_id "
                + "AND mapping.legacy_user_id = audit.legacy_user_id"));
    }

    @Test
    @Order(97)
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
                "0".repeat(64),
                "0".repeat(64),
                List.of(new PlannedV1LegacyDevice(account, device, "v1-history-import")),
                List.of(),
                List.of(new PlannedV1HistoricalMessage(
                        LegacyV1ConversationKind.ROOM, 77, 501, message, conversation, 1,
                        null, account, device, "v1-import-room-501", 1, "text", "hello",
                        null, false, true, Instant.parse("2026-01-02T03:04:05Z"))),
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
                    "UPDATE chat.legacy_v1_message_map SET legacy_content_type = 'archive' "
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
        assertTrue(raced.getFirst().attachment().objectKey().orElseThrow()
                .startsWith("attachments/"));
        assertFalse(raced.getFirst().attachment().objectKey().orElseThrow()
                .contains("报告.pdf"));
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
        SQLException unavailableWithFabricatedObject = assertThrows(SQLException.class,
                () -> execute(connection,
                        "INSERT INTO chat.attachment(id, conversation_id, owner_account_id, "
                                + "owner_device_id, client_attachment_id, object_key, "
                                + "file_name, media_type, byte_size, content_sha256, state, "
                                + "unavailable_at, unavailable_reason) VALUES "
                                + "(?, ?, ?, ?, 'legacy-bad', ?, 'expired.pdf', "
                                + "'application/pdf', 10, ?, 'UNAVAILABLE', "
                                + "transaction_timestamp(), 'expired')",
                        UUID.randomUUID(), conversation, account, device,
                        "attachments/" + UUID.randomUUID(), new byte[32]));
        assertEquals("23514", unavailableWithFabricatedObject.getSQLState());
        UUID unavailable = UUID.randomUUID();
        execute(connection,
                "INSERT INTO chat.attachment(id, conversation_id, owner_account_id, "
                        + "owner_device_id, client_attachment_id, file_name, byte_size, state, "
                        + "unavailable_at, unavailable_reason) VALUES "
                        + "(?, ?, ?, ?, 'legacy-unavailable', 'expired.pdf', 10, "
                        + "'UNAVAILABLE', transaction_timestamp(), 'legacy file expired')",
                unavailable, conversation, account, device);
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT count(*) FROM chat.attachment WHERE id = ? "
                        + "AND (object_key IS NOT NULL OR media_type IS NOT NULL "
                        + "OR content_sha256 IS NOT NULL)")) {
            statement.setObject(1, unavailable);
            try (ResultSet result = statement.executeQuery()) {
                assertTrue(result.next());
                assertEquals(0, result.getInt(1));
            }
        }
        execute(connection,
                "UPDATE chat.attachment SET state = 'READY', "
                        + "ready_at = transaction_timestamp() WHERE id = ?",
                attachment);
        execute(connection,
                "INSERT INTO chat.legacy_v1_conversation_map(legacy_kind, "
                        + "legacy_conversation_id, conversation_id) "
                        + "VALUES ('ROOM', 900, ?)", conversation);
        execute(connection,
                "INSERT INTO chat.conversation_entry(conversation_id, "
                        + "conversation_sequence, entry_kind) VALUES (?, 1, 'MESSAGE')",
                conversation);
        UUID attachmentMessage = UUID.randomUUID();
        execute(connection,
                "INSERT INTO chat.message(id, conversation_id, conversation_sequence, "
                        + "sender_account_id, sender_device_id, client_message_id, "
                        + "message_type, payload, payload_sha256, attachment_id) "
                        + "VALUES (?, ?, 1, ?, ?, 'attachment-message-1', 2, ?, ?, ?)",
                attachmentMessage, conversation, account, device,
                new byte[0], new byte[32], attachment);
        execute(connection,
                "INSERT INTO chat.legacy_v1_attachment_map(legacy_kind, legacy_file_id, "
                        + "legacy_conversation_id, conversation_id, attachment_id) "
                        + "VALUES ('ROOM', 700, 900, ?, ?)", conversation, attachment);
        SQLException missingAttachment = assertThrows(SQLException.class, () -> execute(
                connection,
                "INSERT INTO chat.message(id, conversation_id, conversation_sequence, "
                        + "sender_account_id, sender_device_id, client_message_id, "
                        + "message_type, payload, payload_sha256) "
                        + "VALUES (?, ?, 2, ?, ?, 'attachment-message-2', 2, ?, ?)",
                UUID.randomUUID(), conversation, account, device,
                new byte[0], new byte[32]));
        assertEquals("23514", missingAttachment.getSQLState());
        SQLException attachmentOnText = assertThrows(SQLException.class, () -> execute(
                connection,
                "INSERT INTO chat.message(id, conversation_id, conversation_sequence, "
                        + "sender_account_id, sender_device_id, client_message_id, "
                        + "message_type, payload, payload_sha256, attachment_id) "
                        + "VALUES (?, ?, 2, ?, ?, 'attachment-message-3', 1, ?, ?, ?)",
                UUID.randomUUID(), conversation, account, device,
                new byte[] {1}, new byte[32], attachment));
        assertEquals("23514", attachmentOnText.getSQLState());
        SQLException duplicateLegacyFile = assertThrows(SQLException.class, () -> execute(
                connection,
                "INSERT INTO chat.legacy_v1_attachment_map(legacy_kind, legacy_file_id, "
                        + "legacy_conversation_id, conversation_id, attachment_id) "
                        + "VALUES ('ROOM', 700, 900, ?, ?)", conversation, attachment));
        assertEquals("23505", duplicateLegacyFile.getSQLState());
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

    private static List<MessageReactionResult.Applied> raceReaction(
            PostgresMessageReactionAdapter adapter,
            MessageReactionCommand command) throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<MessageReactionResult>> futures =
                    java.util.stream.IntStream.range(0, 2)
                            .mapToObj(index -> executor.submit(() -> {
                                ready.countDown();
                                assertTrue(start.await(2, TimeUnit.SECONDS));
                                return adapter.set(command);
                            }))
                            .toList();
            assertTrue(ready.await(2, TimeUnit.SECONDS));
            start.countDown();
            return List.of(
                    (MessageReactionResult.Applied) futures.get(0).get(),
                    (MessageReactionResult.Applied) futures.get(1).get());
        } finally {
            start.countDown();
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(2, TimeUnit.SECONDS));
        }
    }

    private static List<MessageEditResult.Applied> raceEdit(
            PostgresMessageEditAdapter adapter, MessageEditCommand command) throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<MessageEditResult>> futures = java.util.stream.IntStream.range(0, 2)
                    .mapToObj(index -> executor.submit(() -> {
                        ready.countDown();
                        assertTrue(start.await(2, TimeUnit.SECONDS));
                        return adapter.edit(command);
                    }))
                    .toList();
            assertTrue(ready.await(2, TimeUnit.SECONDS));
            start.countDown();
            return List.of((MessageEditResult.Applied) futures.get(0).get(),
                    (MessageEditResult.Applied) futures.get(1).get());
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
                    "INSERT INTO chat.direct_conversation("
                            + "conversation_id, first_account_id, second_account_id) "
                            + "VALUES (?, ?, ?)")) {
                statement.setObject(1, conversation);
                statement.setObject(2, account);
                statement.setObject(3, account);
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

    private static void seedMentionTarget(UUID account, UUID conversation) throws SQLException {
        seedAccount(account, "mention-target");
        try (Connection connection = connect()) {
            execute(connection,
                    "INSERT INTO chat.conversation_member(conversation_id, account_id) "
                            + "VALUES (?, ?)",
                    conversation, account);
        }
    }

    private static void seedAccount(UUID account, String username) throws SQLException {
        try (Connection connection = connect()) {
            execute(connection,
                    "INSERT INTO chat.account(id, username_key, display_name, password_hash) "
                            + "VALUES (?, ?, 'Mention Target', "
                            + "'$argon2id$v=19$m=65536,t=2,p=1$test$fixture')",
                    account, username);
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

    private static void assertMessageSearchIndexEligible(UUID conversation) throws SQLException {
        try (Connection connection = connect()) {
            connection.setAutoCommit(false);
            execute(connection, "SET LOCAL enable_seqscan = off");
            StringBuilder plan = new StringBuilder();
            try (PreparedStatement statement = connection.prepareStatement(
                    "EXPLAIN (FORMAT TEXT) SELECT id, conversation_sequence "
                            + "FROM chat.message WHERE conversation_id=? "
                            + "AND conversation_sequence < ? AND message_type=1 "
                            + "AND deleted_at IS NULL "
                            + "AND position(lower(?) in lower(convert_from(payload,'UTF8'))) > 0 "
                            + "ORDER BY conversation_sequence DESC LIMIT ?")) {
                statement.setObject(1, conversation);
                statement.setLong(2, Long.MAX_VALUE);
                statement.setString(3, "聊天");
                statement.setInt(4, 51);
                try (ResultSet result = statement.executeQuery()) {
                    while (result.next()) plan.append(result.getString(1)).append('\n');
                }
            } finally {
                connection.rollback();
            }
            assertTrue(plan.toString().contains("message_conversation_history_idx"),
                    plan.toString());
        }
    }

    private static MessageSubmissionResult.Accepted accepted(MessageSubmissionResult result) {
        return (MessageSubmissionResult.Accepted) result;
    }

    private static MessageSearchPage found(MessageSearchResult result) {
        return ((MessageSearchResult.Found) result).page();
    }

    @Test
    @Order(99)
    void listsAndRevokesOtherDevicesWithDurableSessionBoundAudit() throws Exception {
        requireDatabase(); truncateApplicationData();
        UUID account = UUID.randomUUID(), foreignAccount = UUID.randomUUID();
        UUID current = UUID.randomUUID(), target = UUID.randomUUID();
        UUID legacy = UUID.randomUUID(), foreign = UUID.randomUUID();
        UUID currentSession = UUID.randomUUID(), targetSession = UUID.randomUUID();
        UUID targetSessionTwo = UUID.randomUUID(), foreignSession = UUID.randomUUID();
        byte[] targetResume = new byte[32]; Arrays.fill(targetResume, (byte) 41);
        try (Connection connection = connect()) {
            execute(connection, "INSERT INTO chat.account(id, username_key, display_name, "
                    + "password_hash) VALUES (?, 'device_owner', 'Device Owner', "
                    + "'$argon2id$v=19$m=65536,t=2,p=1$test$fixture'), "
                    + "(?, 'device_foreign', 'Device Foreign', "
                    + "'$argon2id$v=19$m=65536,t=2,p=1$test$fixture')",
                    account, foreignAccount);
            execute(connection, "INSERT INTO chat.device(id, account_id, client_device_id, "
                    + "platform, created_at, last_seen_at) VALUES "
                    + "(?, ?, 'current-web', 'WEB', transaction_timestamp() - interval '2 day', "
                    + "transaction_timestamp()), "
                    + "(?, ?, 'lost-windows', 'WINDOWS', transaction_timestamp() - interval '3 day', "
                    + "transaction_timestamp() - interval '1 hour'), "
                    + "(?, ?, 'legacy-history', 'LEGACY', transaction_timestamp() - interval '4 day', "
                    + "transaction_timestamp() - interval '4 day'), "
                    + "(?, ?, 'foreign-web', 'WEB', transaction_timestamp(), transaction_timestamp())",
                    current, account, target, account, legacy, account, foreign, foreignAccount);
            execute(connection, "INSERT INTO chat.device_session(id, account_id, device_id, "
                    + "token_sha256, created_at, expires_at) VALUES "
                    + "(?, ?, ?, ?, transaction_timestamp(), transaction_timestamp() + interval '1 day'), "
                    + "(?, ?, ?, ?, transaction_timestamp(), transaction_timestamp() + interval '1 day'), "
                    + "(?, ?, ?, ?, transaction_timestamp(), transaction_timestamp() + interval '1 day'), "
                    + "(?, ?, ?, ?, transaction_timestamp(), transaction_timestamp() + interval '1 day')",
                    currentSession, account, current, sha256(new byte[] {1}),
                    targetSession, account, target, sha256(targetResume),
                    targetSessionTwo, account, target, sha256(new byte[] {2}),
                    foreignSession, foreignAccount, foreign, sha256(new byte[] {3}));
        }
        var actor = new AuthenticatedDeviceActor(account, current, currentSession);
        var service = new DeviceManagementService(
                new PostgresDeviceManagementAdapter(dataSource()));
        DeviceDirectoryResult.Available directory = assertInstanceOf(
                DeviceDirectoryResult.Available.class, service.listActive(actor));
        assertEquals(2, directory.devices().size());
        assertEquals(1, directory.devices().stream().filter(value -> value.current()).count());
        assertTrue(directory.devices().stream().anyMatch(value ->
                value.deviceId().equals(target) && value.platform() == ClientPlatform.WINDOWS));
        assertTrue(directory.devices().stream().noneMatch(value ->
                value.deviceId().equals(legacy) || value.deviceId().equals(foreign)));
        assertEquals(DeviceRevocationResult.Rejected.INSTANCE,
                service.revokeOther(actor, current));
        assertEquals(DeviceRevocationResult.Rejected.INSTANCE,
                service.revokeOther(actor, foreign));

        DeviceRevocationResult.Revoked revoked = assertInstanceOf(
                DeviceRevocationResult.Revoked.class, service.revokeOther(actor, target));
        assertTrue(revoked.changed()); assertEquals(2, revoked.revokedSessions());
        assertEquals(1, count("SELECT count(*) FROM chat.device WHERE id = '" + target
                + "' AND revoked_at = '" + revoked.revokedAt() + "'::timestamptz"));
        assertEquals(2, count("SELECT count(*) FROM chat.device_session WHERE device_id = '"
                + target + "' AND revoked_at = '" + revoked.revokedAt() + "'::timestamptz"));
        assertEquals(1, count("SELECT count(*) FROM chat.device_revocation_audit WHERE id = '"
                + revoked.auditId() + "' AND actor_device_id = '" + current
                + "' AND actor_session_id = '" + currentSession + "'"));

        DeviceRevocationResult.Revoked retry = assertInstanceOf(
                DeviceRevocationResult.Revoked.class, service.revokeOther(actor, target));
        assertFalse(retry.changed()); assertEquals(revoked.auditId(), retry.auditId());
        assertEquals(revoked.revokedAt(), retry.revokedAt());
        assertEquals(1, count("SELECT count(*) FROM chat.device_revocation_audit"));

        new PostgresMigrator(URL, USER, PASSWORD).validate();
        try (SecretBytes proof = SecretBytes.copyOf(targetResume)) {
            assertTrue(new PostgresIdentityAdapter(dataSource()).resumeAndRotate(
                    targetSession, proof,
                    new ClientDescriptor("lost-windows", ClientPlatform.WINDOWS, "test"),
                    Instant.now()).isEmpty());
        }
        revokeDevice(current);
        assertEquals(DeviceDirectoryResult.Rejected.INSTANCE, service.listActive(actor));
        assertEquals(DeviceRevocationResult.Rejected.INSTANCE,
                service.revokeOther(actor, target));
    }

    @Test
    @Order(99)
    void convergesConcurrentMutualDeviceRevocationToOneAuthority() throws Exception {
        requireDatabase(); truncateApplicationData();
        UUID account = UUID.randomUUID(), first = UUID.randomUUID(), second = UUID.randomUUID();
        UUID firstSession = UUID.randomUUID(), secondSession = UUID.randomUUID();
        try (Connection connection = connect()) {
            execute(connection, "INSERT INTO chat.account(id, username_key, display_name, "
                    + "password_hash) VALUES (?, 'mutual_device_owner', 'Mutual Owner', "
                    + "'$argon2id$v=19$m=65536,t=2,p=1$test$fixture')", account);
            execute(connection, "INSERT INTO chat.device(id, account_id, client_device_id, "
                    + "platform) VALUES (?, ?, 'mutual-first', 'WEB'), "
                    + "(?, ?, 'mutual-second', 'WINDOWS')", first, account, second, account);
            execute(connection, "INSERT INTO chat.device_session(id, account_id, device_id, "
                    + "token_sha256, expires_at) VALUES "
                    + "(?, ?, ?, ?, transaction_timestamp() + interval '1 day'), "
                    + "(?, ?, ?, ?, transaction_timestamp() + interval '1 day')",
                    firstSession, account, first, sha256(new byte[] {11}),
                    secondSession, account, second, sha256(new byte[] {12}));
        }
        var service = new DeviceManagementService(
                new PostgresDeviceManagementAdapter(dataSource()));
        var firstActor = new AuthenticatedDeviceActor(account, first, firstSession);
        var secondActor = new AuthenticatedDeviceActor(account, second, secondSession);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService workers = Executors.newFixedThreadPool(2);
        try {
            Future<DeviceRevocationResult> one = workers.submit(() -> {
                start.await(); return service.revokeOther(firstActor, second);
            });
            Future<DeviceRevocationResult> two = workers.submit(() -> {
                start.await(); return service.revokeOther(secondActor, first);
            });
            start.countDown();
            List<DeviceRevocationResult> results = List.of(
                    one.get(10, TimeUnit.SECONDS), two.get(10, TimeUnit.SECONDS));
            assertEquals(1, results.stream().filter(value -> value
                    instanceof DeviceRevocationResult.Revoked revoked && revoked.changed()).count());
            assertEquals(1, results.stream().filter(value ->
                    value == DeviceRevocationResult.Rejected.INSTANCE).count());
        } finally {
            workers.shutdownNow(); assertTrue(workers.awaitTermination(5, TimeUnit.SECONDS));
        }
        assertEquals(1, count("SELECT count(*) FROM chat.device WHERE account_id = '" + account
                + "' AND revoked_at IS NULL"));
        assertEquals(1, count("SELECT count(*) FROM chat.device_session WHERE account_id = '"
                + account + "' AND revoked_at IS NULL"));
        assertEquals(1, count("SELECT count(*) FROM chat.device_revocation_audit "
                + "WHERE account_id = '" + account + "'"));
    }

    @Test
    @Order(100)
    void storesOnlyProtectedWebPushCredentialsAndTransfersEndpointOwnership() throws Exception {
        requireDatabase(); truncateApplicationData();
        UUID firstAccount = UUID.randomUUID(), secondAccount = UUID.randomUUID();
        UUID firstInstall = UUID.randomUUID(), secondInstall = UUID.randomUUID();
        seedAccount(firstAccount, "push-first");
        seedAccount(secondAccount, "push-second");
        byte[] endpoint = "https://push.example.test/send/shared-token"
                .getBytes(java.nio.charset.StandardCharsets.US_ASCII);
        byte[] p256dh = new byte[65]; p256dh[0] = 0x04;
        byte[] auth = new byte[16]; Arrays.fill(auth, (byte) 7);
        AtomicReference<ProtectedWebPushSubscription> lastProtected = new AtomicReference<>();
        var adapter = new PostgresWebPushSubscriptionAdapter(dataSource(), registration -> {
            byte[] endpointCiphertext = new byte[48];
            Arrays.fill(endpointCiphertext,
                    (byte) registration.accountId().getLeastSignificantBits());
            byte[] keyCiphertext = new byte[96]; Arrays.fill(keyCiphertext, (byte) 2);
            byte[] authCiphertext = new byte[48]; Arrays.fill(authCiphertext, (byte) 3);
            byte[] lookupTag = registration.withEndpointCopy(
                    PostgresMigratorTest::sha256);
            var value = ProtectedWebPushSubscription.copyOf(
                    registration.accountId(), registration.installationId(),
                    registration.browserExpiresAt(), "fixture-key:v1",
                    endpointCiphertext, keyCiphertext, authCiphertext, lookupTag);
            lastProtected.set(value);
            return value;
        });

        try (var first = WebPushSubscriptionRegistration.copyOf(
                firstAccount, firstInstall, Optional.empty(), endpoint, p256dh, auth)) {
            assertEquals(WebPushSubscriptionReplaceResult.REPLACED, adapter.replace(first));
        }
        assertTrue(lastProtected.get().isClosed());
        byte[] stored = webPushEndpointCiphertext(firstAccount, firstInstall);
        assertEquals(48, stored.length);
        assertFalse(Arrays.equals(endpoint, stored));
        assertEquals(0, count("SELECT count(*) FROM chat.web_push_subscription "
                + "WHERE endpoint_ciphertext = convert_to('https://push.example.test/send/"
                + "shared-token', 'UTF8')"));

        try (var second = WebPushSubscriptionRegistration.copyOf(
                secondAccount, secondInstall, Optional.empty(), endpoint, p256dh, auth)) {
            assertEquals(WebPushSubscriptionReplaceResult.REPLACED, adapter.replace(second));
        }
        assertEquals(0, webPushSubscriptionCount(firstAccount, firstInstall));
        assertEquals(1, webPushSubscriptionCount(secondAccount, secondInstall));
        assertFalse(adapter.delete(firstAccount, secondInstall));
        assertTrue(adapter.delete(secondAccount, secondInstall));
        assertFalse(adapter.delete(secondAccount, secondInstall));

        List<UUID> quotaInstalls = new ArrayList<>();
        for (int index = 0;
                index < PostgresWebPushSubscriptionAdapter.MAX_SUBSCRIPTIONS_PER_ACCOUNT;
                index++) {
            UUID installation = UUID.randomUUID(); quotaInstalls.add(installation);
            byte[] uniqueEndpoint = ("https://push.example.test/send/quota-" + index)
                    .getBytes(java.nio.charset.StandardCharsets.US_ASCII);
            try (var registration = WebPushSubscriptionRegistration.copyOf(
                    firstAccount, installation, Optional.empty(),
                    uniqueEndpoint, p256dh, auth)) {
                assertEquals(WebPushSubscriptionReplaceResult.REPLACED,
                        adapter.replace(registration));
            }
        }
        try (var overLimit = WebPushSubscriptionRegistration.copyOf(
                firstAccount, UUID.randomUUID(), Optional.empty(),
                "https://push.example.test/send/over-limit"
                        .getBytes(java.nio.charset.StandardCharsets.US_ASCII),
                p256dh, auth)) {
            assertEquals(WebPushSubscriptionReplaceResult.LIMIT_REACHED,
                    adapter.replace(overLimit));
        }
        assertEquals(PostgresWebPushSubscriptionAdapter.MAX_SUBSCRIPTIONS_PER_ACCOUNT,
                count("SELECT count(*) FROM chat.web_push_subscription WHERE account_id = '"
                        + firstAccount + "'"));
        try (var updateExisting = WebPushSubscriptionRegistration.copyOf(
                firstAccount, quotaInstalls.getFirst(), Optional.empty(),
                "https://push.example.test/send/quota-updated"
                        .getBytes(java.nio.charset.StandardCharsets.US_ASCII),
                p256dh, auth)) {
            assertEquals(WebPushSubscriptionReplaceResult.REPLACED,
                    adapter.replace(updateExisting));
        }
        disableAccount(firstAccount);
        try (var disabled = WebPushSubscriptionRegistration.copyOf(
                firstAccount, quotaInstalls.getFirst(), Optional.empty(),
                "https://push.example.test/send/disabled"
                        .getBytes(java.nio.charset.StandardCharsets.US_ASCII),
                p256dh, auth)) {
            assertEquals(WebPushSubscriptionReplaceResult.ACCOUNT_UNAVAILABLE,
                    adapter.replace(disabled));
        }
    }

    @Test
    @Order(101)
    void transactionallyRewrapsWebPushEncryptionAndLookupKeysOrRollsBackAll()
            throws Exception {
        requireDatabase(); truncateApplicationData();
        UUID firstAccount = UUID.randomUUID(), secondAccount = UUID.randomUUID();
        UUID firstInstall = UUID.randomUUID(), secondInstall = UUID.randomUUID();
        seedAccount(firstAccount, "push-rotation-first");
        seedAccount(secondAccount, "push-rotation-second");
        var source = new FixtureWebPushProtector("enc-old", (byte) 11);
        var target = new FixtureWebPushProtector("enc-new", (byte) 22);
        var store = new PostgresWebPushSubscriptionAdapter(dataSource(), source);
        byte[] p256dh = new byte[65]; p256dh[0] = 0x04;
        byte[] auth = new byte[16]; Arrays.fill(auth, (byte) 7);
        byte[] firstEndpoint = "https://push.example.test/send/rotation-first"
                .getBytes(java.nio.charset.StandardCharsets.US_ASCII);
        byte[] secondEndpoint = "https://push.example.test/send/rotation-second"
                .getBytes(java.nio.charset.StandardCharsets.US_ASCII);
        try (var first = WebPushSubscriptionRegistration.copyOf(
                    firstAccount, firstInstall, Optional.empty(),
                    firstEndpoint, p256dh, auth);
                var second = WebPushSubscriptionRegistration.copyOf(
                    secondAccount, secondInstall, Optional.empty(),
                    secondEndpoint, p256dh, auth)) {
            assertEquals(WebPushSubscriptionReplaceResult.REPLACED, store.replace(first));
            assertEquals(WebPushSubscriptionReplaceResult.REPLACED, store.replace(second));
        }

        var rotation = new PostgresWebPushSubscriptionKeyRotation(
                dataSource(), source, target, "enc-new");
        assertThrows(IllegalStateException.class, () -> rotation.rotate(1, 1));
        assertEquals(2, count("SELECT count(*) FROM chat.web_push_subscription "
                + "WHERE encryption_key_id='enc-old'"));

        WebPushSubscriptionKeyRotationReport report = rotation.rotate(1, 10);
        assertEquals(2, report.rotatedSubscriptions());
        assertEquals(Set.of("enc-old"), report.sourceEncryptionKeyIds());
        assertEquals("enc-new", report.targetEncryptionKeyId());
        assertEquals(2, count("SELECT count(*) FROM chat.web_push_subscription "
                + "WHERE encryption_key_id='enc-new'"));
        assertEquals(0, count("SELECT count(*) FROM chat.web_push_subscription "
                + "WHERE endpoint_lookup_tag=decode('"
                + source.lookupHex(firstEndpoint) + "','hex')"));
        assertEquals(1, count("SELECT count(*) FROM chat.web_push_subscription "
                + "WHERE endpoint_lookup_tag=decode('"
                + target.lookupHex(firstEndpoint) + "','hex')"));

        var targetStore = new PostgresWebPushSubscriptionAdapter(dataSource(), target);
        try (var batch = targetStore.loadActive(firstAccount, Instant.now());
                var restored = target.unprotect(batch.subscriptions().getFirst())) {
            assertArrayEquals(firstEndpoint,
                    restored.withEndpointCopy(bytes -> bytes.clone()));
        }

        var next = new FixtureWebPushProtector("enc-next", (byte) 33);
        AtomicInteger calls = new AtomicInteger();
        WebPushCredentialProtectionPort failingTarget = registration -> {
            if (calls.incrementAndGet() == 2) {
                throw new IllegalStateException("fixture target failure");
            }
            return next.protect(registration);
        };
        var failingRotation = new PostgresWebPushSubscriptionKeyRotation(
                dataSource(), target, failingTarget, "enc-next");
        assertThrows(IllegalStateException.class, () -> failingRotation.rotate(1, 10));
        assertEquals(2, count("SELECT count(*) FROM chat.web_push_subscription "
                + "WHERE encryption_key_id='enc-new'"));
        assertEquals(0, count("SELECT count(*) FROM chat.web_push_subscription "
                + "WHERE encryption_key_id='enc-next'"));

        try (Connection connection = connect()) {
            execute(connection, "DELETE FROM chat.account WHERE id=?", firstAccount);
        }
        assertEquals(1, count("SELECT count(*) FROM chat.web_push_subscription"));
    }

    @Test
    @Order(102)
    void producesDefaultOffWebPushOutboxOnlyForNewMessagesInTheMessageTransaction()
            throws Exception {
        requireDatabase(); truncateApplicationData();
        UUID account = UUID.randomUUID(), device = UUID.randomUUID();
        UUID conversation = UUID.randomUUID(), mentionedAccount = UUID.randomUUID();
        seedMessageOwner(account, device, conversation);
        seedMentionTarget(mentionedAccount, conversation);

        var disabled = new PostgresMessageAdapter(dataSource());
        accepted(disabled.submit(
                new MessageSubmission(conversation, account, device, "push-disabled", 1,
                        "before".getBytes(java.nio.charset.StandardCharsets.UTF_8))));
        assertEquals(0, count("SELECT count(*) FROM chat.web_push_notification_outbox"));

        var enabled = new PostgresMessageAdapter(
                dataSource(), new WebPushDeliveryPolicy(true));
        byte[] mentionedBody = "@you hi".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        var enabledSubmission = new MessageSubmission(
                conversation, account, device, "push-enabled", 1, mentionedBody,
                Optional.empty(), List.of(new MessageMention(mentionedAccount, 0, 4)));
        MessageSubmissionResult.Accepted enabledAccepted = accepted(
                enabled.submit(enabledSubmission));
        assertFalse(enabledAccepted.duplicate());
        assertTrue(accepted(enabled.submit(enabledSubmission)).duplicate());
        assertEquals(1, count("SELECT count(*) FROM chat.web_push_notification_outbox"));
        assertWebPushOutbox(enabledAccepted, conversation, account, mentionedAccount);

        try (Connection connection = connect()) {
            execute(connection, """
                    CREATE FUNCTION chat.reject_web_push_outbox_fixture()
                    RETURNS trigger LANGUAGE plpgsql AS $$
                    BEGIN
                        RAISE EXCEPTION 'fixture Web Push outbox rejection';
                    END;
                    $$
                    """);
            execute(connection, """
                    CREATE TRIGGER reject_web_push_outbox_fixture
                    BEFORE INSERT ON chat.web_push_notification_outbox
                    FOR EACH ROW EXECUTE FUNCTION chat.reject_web_push_outbox_fixture()
                    """);
        }
        try {
            assertThrows(MessagePersistenceException.class, () -> enabled.submit(
                    new MessageSubmission(conversation, account, device,
                            "push-outbox-rollback", 1, new byte[] {9})));
            assertEquals(2, conversationEntryCount(conversation));
            assertEquals(2, count("SELECT count(*) FROM chat.conversation_event_outbox"));
            assertEquals(1, count("SELECT count(*) FROM chat.web_push_notification_outbox"));
            assertEquals(0, count("SELECT count(*) FROM chat.message "
                    + "WHERE client_message_id = 'push-outbox-rollback'"));
        } finally {
            try (Connection connection = connect()) {
                execute(connection, "DROP TRIGGER reject_web_push_outbox_fixture "
                        + "ON chat.web_push_notification_outbox");
                execute(connection, "DROP FUNCTION chat.reject_web_push_outbox_fixture()");
            }
        }
        MessageSubmissionResult.Accepted afterRollback = accepted(enabled.submit(
                new MessageSubmission(conversation, account, device,
                        "push-outbox-rollback", 1, new byte[] {9})));
        assertEquals(3, afterRollback.conversationSequence());
        assertEquals(2, count("SELECT count(*) FROM chat.web_push_notification_outbox"));
    }

    @Test
    @Order(103)
    void fencesConcurrentWebPushClaimsAndBoundsExpiryAndRetention() throws Exception {
        requireDatabase(); truncateApplicationData();
        UUID account = UUID.randomUUID(), device = UUID.randomUUID();
        UUID conversation = UUID.randomUUID();
        seedMessageOwner(account, device, conversation);
        var messages = new PostgresMessageAdapter(
                dataSource(), new WebPushDeliveryPolicy(true));
        for (int index = 0; index < 3; index++) {
            accepted(messages.submit(new MessageSubmission(
                    conversation, account, device, "push-claim-" + index, 1,
                    new byte[] {(byte) index})));
        }

        Instant claimedAt = Instant.now();
        UUID firstOwner = UUID.randomUUID(), secondOwner = UUID.randomUUID();
        var firstWorker = new PostgresWebPushOutboxAdapter(dataSource());
        var secondWorker = new PostgresWebPushOutboxAdapter(dataSource());
        var initialStatus = firstWorker.readStatus(claimedAt);
        assertEquals(3, initialStatus.pending());
        assertEquals(3, initialStatus.ready());
        assertEquals(0, initialStatus.leased());
        assertEquals(0, initialStatus.maximumAttemptCount());
        assertTrue(initialStatus.oldestCommittedAt().isPresent());
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService workers = Executors.newFixedThreadPool(2);
        List<WebPushOutboxClaim> claims;
        try {
            Future<List<WebPushOutboxClaim>> first = workers.submit(() -> {
                start.await();
                return firstWorker.claim(firstOwner, claimedAt, Duration.ofSeconds(30), 2);
            });
            Future<List<WebPushOutboxClaim>> second = workers.submit(() -> {
                start.await();
                return secondWorker.claim(secondOwner, claimedAt, Duration.ofSeconds(30), 2);
            });
            start.countDown();
            claims = java.util.stream.Stream.concat(
                            first.get(10, TimeUnit.SECONDS).stream(),
                            second.get(10, TimeUnit.SECONDS).stream())
                    .toList();
        } finally {
            start.countDown(); workers.shutdownNow();
            assertTrue(workers.awaitTermination(5, TimeUnit.SECONDS));
        }
        assertEquals(3, claims.size());
        assertEquals(3, claims.stream().map(claim -> claim.intent().messageId())
                .distinct().count());
        assertTrue(claims.stream().allMatch(claim -> claim.attemptCount() == 1));
        var claimedStatus = firstWorker.readStatus(claimedAt.plusMillis(1));
        assertEquals(3, claimedStatus.pending());
        assertEquals(0, claimedStatus.ready());
        assertEquals(3, claimedStatus.leased());
        assertEquals(1, claimedStatus.maximumAttemptCount());

        WebPushOutboxClaim completed = claims.get(0);
        Instant transitionAt = claimedAt.plusMillis(100);
        assertTrue(firstWorker.complete(
                completed, transitionAt, WebPushTerminalOutcome.DELIVERED));
        assertFalse(firstWorker.complete(
                completed, transitionAt, WebPushTerminalOutcome.DELIVERED));
        assertThrows(IllegalArgumentException.class, () -> firstWorker.complete(
                claims.get(1), claims.get(1).claimExpiresAt().plusMillis(1),
                WebPushTerminalOutcome.DELIVERED));
        assertThrows(IllegalArgumentException.class, () -> firstWorker.complete(
                claims.get(1), transitionAt, WebPushTerminalOutcome.EXPIRED));

        WebPushOutboxClaim deferred = claims.get(1);
        Instant retryAt = transitionAt.plusSeconds(2);
        assertTrue(firstWorker.defer(deferred, transitionAt, retryAt, "PROVIDER_TIMEOUT"));
        var deferredStatus = firstWorker.readStatus(transitionAt.plusMillis(1));
        assertEquals(2, deferredStatus.pending());
        assertEquals(1, deferredStatus.leased());
        assertEquals(1, deferredStatus.delayed());
        assertTrue(firstWorker.claim(
                UUID.randomUUID(), retryAt.minusMillis(1), Duration.ofSeconds(30), 10).isEmpty());
        WebPushOutboxClaim retried = firstWorker.claim(
                UUID.randomUUID(), retryAt, Duration.ofSeconds(30), 10).getFirst();
        assertEquals(deferred.intent().messageId(), retried.intent().messageId());
        assertEquals(2, retried.attemptCount());
        var retriedStatus = firstWorker.readStatus(retryAt.plusMillis(1));
        assertEquals(2, retriedStatus.leased());
        assertEquals(1, retriedStatus.retried());
        assertEquals(2, retriedStatus.maximumAttemptCount());
        assertTrue(firstWorker.complete(
                retried, retryAt.plusMillis(100), WebPushTerminalOutcome.DELIVERED));
        assertTrue(firstWorker.complete(
                claims.get(2), transitionAt, WebPushTerminalOutcome.INELIGIBLE));

        MessageSubmissionResult.Accepted expiring = accepted(messages.submit(
                new MessageSubmission(conversation, account, device,
                        "push-expire", 1, new byte[] {9})));
        Instant expiredAt = claimedAt.minusSeconds(1);
        Instant oldCommittedAt = expiredAt.minus(Duration.ofHours(24));
        try (Connection connection = connect()) {
            execute(connection, "UPDATE chat.web_push_notification_outbox "
                            + "SET committed_at=?, available_at=?, expires_at=? WHERE message_id=?",
                    OffsetDateTime.ofInstant(oldCommittedAt, ZoneOffset.UTC),
                    OffsetDateTime.ofInstant(oldCommittedAt, ZoneOffset.UTC),
                    OffsetDateTime.ofInstant(expiredAt, ZoneOffset.UTC), expiring.messageId());
        }
        var expiredStatus = firstWorker.readStatus(claimedAt);
        assertEquals(1, expiredStatus.pending());
        assertEquals(1, expiredStatus.expired());
        assertEquals(1, firstWorker.expire(claimedAt, 10));
        assertEquals(0, firstWorker.expire(claimedAt, 10));
        assertEquals(2, firstWorker.purgeCompletedBefore(retryAt.plusSeconds(1), 2));
        assertEquals(2, firstWorker.purgeCompletedBefore(retryAt.plusSeconds(1), 10));
        assertEquals(0, count("SELECT count(*) FROM chat.web_push_notification_outbox"));
        assertEquals(4, count("SELECT count(*) FROM chat.message"));
    }

    @Test
    @Order(104)
    void reauthorizesCurrentWebPushRecipientsAndLoadsOnlyActiveProtectedSubscriptions()
            throws Exception {
        requireDatabase(); truncateApplicationData();
        UUID sender = UUID.randomUUID(), device = UUID.randomUUID();
        UUID conversation = UUID.randomUUID(), mentioned = UUID.randomUUID();
        UUID other = UUID.randomUUID(), blocked = UUID.randomUUID();
        seedMessageOwner(sender, device, conversation);
        seedAccount(mentioned, "push-mentioned");
        seedAccount(other, "push-other");
        seedAccount(blocked, "push-blocked");
        try (Connection connection = connect()) {
            execute(connection, "INSERT INTO chat.conversation_member("
                            + "conversation_id, account_id) VALUES (?, ?), (?, ?), (?, ?)",
                    conversation, mentioned, conversation, other, conversation, blocked);
        }
        byte[] payload = "@you hi".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        var messages = new PostgresMessageAdapter(
                dataSource(), new WebPushDeliveryPolicy(true));
        MessageSubmissionResult.Accepted accepted = accepted(messages.submit(
                new MessageSubmission(conversation, sender, device, "push-policy", 1,
                        payload, Optional.empty(),
                        List.of(new MessageMention(mentioned, 0, 4)))));
        try (Connection connection = connect()) {
            execute(connection, "INSERT INTO chat.account_block("
                            + "blocker_account_id, blocked_account_id) VALUES (?, ?)",
                    blocked, sender);
        }
        var intent = new WebPushNotificationIntent(
                accepted.messageId(), conversation, sender, accepted.acceptedAt(),
                accepted.acceptedAt().plus(Duration.ofHours(24)), Set.of(mentioned));
        var policy = new PostgresWebPushRecipientPolicyAdapter(dataSource());
        assertEquals(WebPushRecipientResolution.Saturated.INSTANCE,
                policy.resolve(intent, 1));
        var complete = (WebPushRecipientResolution.Complete) policy.resolve(intent, 1000);
        assertEquals(2, complete.recipients().size());
        assertTrue(complete.recipients().stream().anyMatch(recipient ->
                recipient.accountId().equals(mentioned) && recipient.mentioned()));
        assertTrue(complete.recipients().stream().noneMatch(recipient ->
                recipient.accountId().equals(blocked)));
        assertTrue(policy.reauthorize(intent, mentioned).orElseThrow().mentioned());
        assertTrue(policy.reauthorize(intent, blocked).isEmpty());

        disableAccount(other);
        complete = (WebPushRecipientResolution.Complete) policy.resolve(intent, 1000);
        assertEquals(List.of(mentioned), complete.recipients().stream()
                .map(recipient -> recipient.accountId()).toList());

        Instant observedAt = Instant.now();
        var subscriptions = new PostgresWebPushSubscriptionAdapter(
                dataSource(), registration -> protectedFixture(registration));
        UUID activeInstallation = UUID.randomUUID(), expiredInstallation = UUID.randomUUID();
        byte[] p256dh = new byte[65]; p256dh[0] = 0x04;
        byte[] auth = new byte[16];
        try (var active = WebPushSubscriptionRegistration.copyOf(
                    mentioned, activeInstallation, Optional.empty(),
                    "https://push.example.test/send/active"
                            .getBytes(java.nio.charset.StandardCharsets.US_ASCII),
                    p256dh, auth);
                var expired = WebPushSubscriptionRegistration.copyOf(
                    mentioned, expiredInstallation, Optional.of(observedAt.minusSeconds(1)),
                    "https://push.example.test/send/expired"
                            .getBytes(java.nio.charset.StandardCharsets.US_ASCII),
                    p256dh, auth)) {
            assertEquals(WebPushSubscriptionReplaceResult.REPLACED,
                    subscriptions.replace(active));
            assertEquals(WebPushSubscriptionReplaceResult.REPLACED,
                    subscriptions.replace(expired));
        }
        ProtectedWebPushSubscription activeProtected;
        try (var batch = subscriptions.loadActive(mentioned, observedAt)) {
            assertEquals(1, batch.subscriptions().size());
            activeProtected = batch.subscriptions().getFirst();
            assertEquals(activeInstallation, activeProtected.installationId());
        }
        assertTrue(activeProtected.isClosed());

        try (Connection connection = connect()) {
            execute(connection, "UPDATE chat.conversation SET next_sequence = 3 WHERE id = ?",
                    conversation);
            execute(connection, "INSERT INTO chat.conversation_entry("
                            + "conversation_id, conversation_sequence, entry_kind, occurred_at) "
                            + "VALUES (?, 2, 'MESSAGE_RECALLED', transaction_timestamp())",
                    conversation);
            execute(connection, "INSERT INTO chat.message_recall_event("
                            + "conversation_id, conversation_sequence, message_id, "
                            + "actor_account_id, source) VALUES (?, 2, ?, ?, 'V2')",
                    conversation, accepted.messageId(), sender);
        }
        assertTrue(((WebPushRecipientResolution.Complete) policy.resolve(intent, 1000))
                .recipients().isEmpty());
        assertTrue(policy.reauthorize(intent, mentioned).isEmpty());
        disableAccount(mentioned);
        try (var batch = subscriptions.loadActive(mentioned, observedAt)) {
            assertTrue(batch.subscriptions().isEmpty());
        }
    }

    @Test
    @Order(105)
    void issuesOnlyHashedSessionBoundWebPushHttpCredentialsAndRechecksRevocation()
            throws Exception {
        requireDatabase(); truncateApplicationData();
        UUID account = UUID.randomUUID(), device = UUID.randomUUID(), session = UUID.randomUUID();
        Instant observedAt = Instant.now();
        Instant sessionExpiresAt = observedAt.plus(Duration.ofMinutes(5));
        try (Connection connection = connect()) {
            execute(connection, "INSERT INTO chat.account(id, username_key, display_name, "
                            + "password_hash) VALUES (?, 'push-http', 'Push HTTP', "
                            + "'$argon2id$v=19$m=65536,t=2,p=1$test$fixture')",
                    account);
            execute(connection, "INSERT INTO chat.device(id, account_id, client_device_id, "
                            + "platform) VALUES (?, ?, 'push-http-device', 'WEB')",
                    device, account);
            execute(connection, "INSERT INTO chat.device_session(id, account_id, device_id, "
                            + "token_sha256, created_at, expires_at) VALUES (?, ?, ?, ?, ?, ?)",
                    session, account, device, sha256(new byte[] {42}),
                    OffsetDateTime.ofInstant(observedAt.minusSeconds(1), ZoneOffset.UTC),
                    OffsetDateTime.ofInstant(sessionExpiresAt, ZoneOffset.UTC));
        }
        AtomicInteger tokenNumber = new AtomicInteger(1);
        var adapter = new PostgresWebPushHttpCredentialAdapter(
                dataSource(), Duration.ofMinutes(10), () -> {
                    byte[] token = new byte[PostgresWebPushHttpCredentialAdapter.RANDOM_TOKEN_BYTES];
                    Arrays.fill(token, (byte) tokenNumber.getAndIncrement());
                    return token;
                });
        var actor = new AuthenticatedDeviceActor(account, device, session);
        byte[][] firstTokens;
        try (var first = adapter.issue(actor, observedAt).orElseThrow()) {
            assertEquals(sessionExpiresAt, first.expiresAt());
            firstTokens = first.withTokenCopies((bearer, csrf) ->
                    new byte[][] {bearer.clone(), csrf.clone()});
        }
        byte[][] secondTokens = null;
        try {
            assertEquals(1, count("SELECT count(*) FROM chat.web_push_http_credential "
                    + "WHERE session_id = '" + session + "'"));
            byte[] storedBearerHash = webPushHttpBearerHash(session);
            byte[] expectedBearerHash = sha256(firstTokens[0]);
            try {
                assertArrayEquals(expectedBearerHash, storedBearerHash);
                assertFalse(Arrays.equals(firstTokens[0], storedBearerHash));
            } finally {
                Arrays.fill(storedBearerHash, (byte) 0);
                Arrays.fill(expectedBearerHash, (byte) 0);
            }
            assertEquals(actor, assertInstanceOf(
                    WebPushHttpCredentialAuthenticationResult.Authenticated.class,
                    adapter.authenticate(firstTokens[0], firstTokens[1], observedAt.plusSeconds(1)))
                    .actor());
            byte[] wrongCsrf = firstTokens[1].clone();
            wrongCsrf[0] = wrongCsrf[0] == 'A' ? (byte) 'B' : (byte) 'A';
            try {
                assertEquals(WebPushHttpCredentialAuthenticationResult.Rejected.INVALID_CSRF,
                        adapter.authenticate(firstTokens[0], wrongCsrf, observedAt.plusSeconds(1)));
            } finally {
                Arrays.fill(wrongCsrf, (byte) 0);
            }

            try (var second = adapter.issue(actor, observedAt.plusSeconds(2)).orElseThrow()) {
                secondTokens = second.withTokenCopies((bearer, csrf) ->
                        new byte[][] {bearer.clone(), csrf.clone()});
            }
            assertEquals(WebPushHttpCredentialAuthenticationResult.Rejected.INVALID_SESSION,
                    adapter.authenticate(firstTokens[0], firstTokens[1], observedAt.plusSeconds(3)));
            assertInstanceOf(WebPushHttpCredentialAuthenticationResult.Authenticated.class,
                    adapter.authenticate(secondTokens[0], secondTokens[1],
                            observedAt.plusSeconds(3)));
            assertEquals(WebPushHttpCredentialAuthenticationResult.Rejected.INVALID_SESSION,
                    adapter.authenticate(secondTokens[0], secondTokens[1], sessionExpiresAt));

            try (Connection connection = connect()) {
                execute(connection, "UPDATE chat.device SET revoked_at = ? WHERE id = ?",
                        OffsetDateTime.ofInstant(observedAt.plusSeconds(4), ZoneOffset.UTC), device);
            }
            assertEquals(WebPushHttpCredentialAuthenticationResult.Rejected.INVALID_SESSION,
                    adapter.authenticate(secondTokens[0], secondTokens[1],
                            observedAt.plusSeconds(5)));
            assertTrue(adapter.issue(actor, observedAt.plusSeconds(5)).isEmpty());
            try (Connection connection = connect()) {
                execute(connection, "UPDATE chat.device SET revoked_at = NULL WHERE id = ?", device);
                execute(connection, "UPDATE chat.account SET disabled_at = ? WHERE id = ?",
                        OffsetDateTime.ofInstant(observedAt.plusSeconds(6), ZoneOffset.UTC), account);
            }
            assertEquals(WebPushHttpCredentialAuthenticationResult.Rejected.INVALID_SESSION,
                    adapter.authenticate(secondTokens[0], secondTokens[1],
                            observedAt.plusSeconds(7)));
            try (Connection connection = connect()) {
                execute(connection, "UPDATE chat.account SET disabled_at = NULL WHERE id = ?",
                        account);
                execute(connection, "UPDATE chat.device_session SET revoked_at = ? WHERE id = ?",
                        OffsetDateTime.ofInstant(observedAt.plusSeconds(8), ZoneOffset.UTC), session);
            }
            assertEquals(WebPushHttpCredentialAuthenticationResult.Rejected.INVALID_SESSION,
                    adapter.authenticate(secondTokens[0], secondTokens[1],
                            observedAt.plusSeconds(9)));
            assertTrue(adapter.issue(actor, observedAt.plusSeconds(9)).isEmpty());
        } finally {
            Arrays.fill(firstTokens[0], (byte) 0);
            Arrays.fill(firstTokens[1], (byte) 0);
            if (secondTokens != null) {
                Arrays.fill(secondTokens[0], (byte) 0);
                Arrays.fill(secondTokens[1], (byte) 0);
            }
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

    private static ProtectedWebPushSubscription protectedFixture(
            WebPushSubscriptionRegistration registration) {
        byte[] endpointCiphertext = new byte[48];
        Arrays.fill(endpointCiphertext,
                (byte) registration.accountId().getLeastSignificantBits());
        byte[] keyCiphertext = new byte[96]; Arrays.fill(keyCiphertext, (byte) 2);
        byte[] authCiphertext = new byte[48]; Arrays.fill(authCiphertext, (byte) 3);
        byte[] lookupTag = registration.withEndpointCopy(PostgresMigratorTest::sha256);
        return ProtectedWebPushSubscription.copyOf(
                registration.accountId(), registration.installationId(),
                registration.browserExpiresAt(), "fixture-key:v1",
                endpointCiphertext, keyCiphertext, authCiphertext, lookupTag);
    }

    private static final class FixtureWebPushProtector
            implements WebPushCredentialProtectionPort, WebPushCredentialUnprotectionPort {
        private static final int HEADER_BYTES = 17;
        private final String keyId;
        private final byte mask;

        private FixtureWebPushProtector(String keyId, byte mask) {
            this.keyId = keyId;
            this.mask = mask;
        }

        @Override
        public ProtectedWebPushSubscription protect(
                WebPushSubscriptionRegistration registration) {
            return registration.withEndpointCopy(endpoint ->
                    registration.withP256dhCopy(p256dh ->
                            registration.withAuthSecretCopy(auth ->
                                    ProtectedWebPushSubscription.copyOf(
                                            registration.accountId(),
                                            registration.installationId(),
                                            registration.browserExpiresAt(), keyId,
                                            encode(endpoint), encode(p256dh), encode(auth),
                                            lookup(endpoint)))));
        }

        @Override
        public WebPushSubscriptionRegistration unprotect(
                ProtectedWebPushSubscription subscription) {
            if (!keyId.equals(subscription.encryptionKeyId())) {
                throw new IllegalArgumentException("fixture key ID mismatch");
            }
            return subscription.withCopies((endpoint, p256dh, auth, lookupTag) -> {
                byte[] plainEndpoint = decode(endpoint);
                byte[] plainP256dh = decode(p256dh);
                byte[] plainAuth = decode(auth);
                byte[] expectedLookup = lookup(plainEndpoint);
                try {
                    if (!MessageDigest.isEqual(expectedLookup, lookupTag)) {
                        throw new IllegalArgumentException("fixture lookup tag mismatch");
                    }
                    return WebPushSubscriptionRegistration.copyOf(
                            subscription.accountId(), subscription.installationId(),
                            subscription.browserExpiresAt(), plainEndpoint,
                            plainP256dh, plainAuth);
                } finally {
                    Arrays.fill(plainEndpoint, (byte) 0);
                    Arrays.fill(plainP256dh, (byte) 0);
                    Arrays.fill(plainAuth, (byte) 0);
                    Arrays.fill(expectedLookup, (byte) 0);
                }
            });
        }

        private byte[] encode(byte[] plain) {
            byte[] encoded = new byte[HEADER_BYTES + plain.length];
            Arrays.fill(encoded, 0, HEADER_BYTES, mask);
            for (int index = 0; index < plain.length; index++) {
                encoded[HEADER_BYTES + index] = (byte) (plain[index] ^ mask);
            }
            return encoded;
        }

        private byte[] decode(byte[] encoded) {
            if (encoded.length < HEADER_BYTES
                    || encoded[0] != mask) {
                throw new IllegalArgumentException("fixture ciphertext mismatch");
            }
            byte[] plain = new byte[encoded.length - HEADER_BYTES];
            for (int index = 0; index < plain.length; index++) {
                plain[index] = (byte) (encoded[HEADER_BYTES + index] ^ mask);
            }
            return plain;
        }

        private byte[] lookup(byte[] endpoint) {
            byte[] input = new byte[endpoint.length + 1];
            input[0] = mask;
            System.arraycopy(endpoint, 0, input, 1, endpoint.length);
            try {
                return sha256(input);
            } finally {
                Arrays.fill(input, (byte) 0);
            }
        }

        private String lookupHex(byte[] endpoint) {
            byte[] tag = lookup(endpoint);
            try {
                return HexFormat.of().formatHex(tag);
            } finally {
                Arrays.fill(tag, (byte) 0);
            }
        }
    }

    private static PGSimpleDataSource dataSource() {
        PGSimpleDataSource dataSource = new PGSimpleDataSource();
        dataSource.setUrl(URL);
        dataSource.setUser(USER);
        dataSource.setPassword(PASSWORD);
        return dataSource;
    }

    private static int accountBlockCount(UUID actor, UUID target) throws SQLException {
        try (Connection connection = connect();
                PreparedStatement statement = connection.prepareStatement(
                        "SELECT count(*) FROM chat.account_block "
                                + "WHERE blocker_account_id=? AND blocked_account_id=?")) {
            statement.setObject(1, actor);
            statement.setObject(2, target);
            try (ResultSet result = statement.executeQuery()) {
                assertTrue(result.next());
                return result.getInt(1);
            }
        }
    }

    private static int accountBlockOperationCount(UUID actor) throws SQLException {
        try (Connection connection = connect();
                PreparedStatement statement = connection.prepareStatement(
                        "SELECT count(*) FROM chat.account_block_operation "
                                + "WHERE actor_account_id=?")) {
            statement.setObject(1, actor);
            try (ResultSet result = statement.executeQuery()) {
                assertTrue(result.next());
                return result.getInt(1);
            }
        }
    }

    private static void assertAccountBlockSelfConstraint(UUID actor) throws SQLException {
        try (Connection connection = connect();
                PreparedStatement statement = connection.prepareStatement(
                        "INSERT INTO chat.account_block(blocker_account_id, blocked_account_id) "
                                + "VALUES (?, ?)")) {
            statement.setObject(1, actor);
            statement.setObject(2, actor);
            SQLException exception = assertThrows(SQLException.class, statement::executeUpdate);
            assertEquals("23514", exception.getSQLState());
        }
    }

    private static int webPushSubscriptionCount(UUID account, UUID installation)
            throws SQLException {
        try (Connection connection = connect();
                PreparedStatement statement = connection.prepareStatement(
                        "SELECT count(*) FROM chat.web_push_subscription "
                                + "WHERE account_id = ? AND installation_id = ?")) {
            statement.setObject(1, account);
            statement.setObject(2, installation);
            try (ResultSet result = statement.executeQuery()) {
                assertTrue(result.next());
                return result.getInt(1);
            }
        }
    }

    private static byte[] webPushEndpointCiphertext(UUID account, UUID installation)
            throws SQLException {
        try (Connection connection = connect();
                PreparedStatement statement = connection.prepareStatement(
                        "SELECT endpoint_ciphertext FROM chat.web_push_subscription "
                                + "WHERE account_id = ? AND installation_id = ?")) {
            statement.setObject(1, account);
            statement.setObject(2, installation);
            try (ResultSet result = statement.executeQuery()) {
                assertTrue(result.next());
                byte[] value = result.getBytes(1);
                assertFalse(result.next());
                return value;
            }
        }
    }

    private static byte[] webPushHttpBearerHash(UUID session) throws SQLException {
        try (Connection connection = connect();
                PreparedStatement statement = connection.prepareStatement(
                        "SELECT bearer_sha256 FROM chat.web_push_http_credential "
                                + "WHERE session_id = ?")) {
            statement.setObject(1, session);
            try (ResultSet result = statement.executeQuery()) {
                assertTrue(result.next());
                byte[] value = result.getBytes(1);
                assertFalse(result.next());
                return value;
            }
        }
    }

    private static void assertWebPushOutbox(
            MessageSubmissionResult.Accepted accepted,
            UUID conversation,
            UUID sender,
            UUID mentionedAccount) throws SQLException {
        try (Connection connection = connect();
                PreparedStatement statement = connection.prepareStatement("""
                        SELECT conversation_id, sender_account_id, committed_at, expires_at,
                               mentioned_account_ids
                        FROM chat.web_push_notification_outbox WHERE message_id = ?
                        """)) {
            statement.setObject(1, accepted.messageId());
            try (ResultSet result = statement.executeQuery()) {
                assertTrue(result.next());
                assertEquals(conversation, result.getObject(1, UUID.class));
                assertEquals(sender, result.getObject(2, UUID.class));
                Instant committedAt = result.getObject(3, OffsetDateTime.class).toInstant();
                Instant expiresAt = result.getObject(4, OffsetDateTime.class).toInstant();
                assertEquals(accepted.acceptedAt(), committedAt);
                assertEquals(Duration.ofHours(24), Duration.between(committedAt, expiresAt));
                java.sql.Array mentions = result.getArray(5);
                try {
                    assertEquals(List.of(mentionedAccount),
                            Arrays.asList((UUID[]) mentions.getArray()));
                } finally {
                    mentions.free();
                }
                assertFalse(result.next());
            }
        }
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

    private static void seedV1AttachmentTarget(UUID account, UUID conversation)
            throws SQLException {
        try (Connection connection = connect()) {
            execute(connection,
                    "INSERT INTO chat.account(id, username_key, display_name, password_hash) "
                            + "VALUES (?, 'v1-attachment-user', 'Attachment User', "
                            + "'$argon2id$v=19$m=65536,t=2,p=1$test$fixture')",
                    account);
            execute(connection,
                    "INSERT INTO chat.conversation(id, kind, title) "
                            + "VALUES (?, 'GROUP', 'Room')",
                    conversation);
            execute(connection,
                    "INSERT INTO chat.conversation_member(conversation_id, account_id) "
                            + "VALUES (?, ?)", conversation, account);
            execute(connection,
                    "INSERT INTO chat.legacy_v1_conversation_map("
                            + "legacy_kind, legacy_conversation_id, conversation_id) "
                            + "VALUES ('ROOM', 9, ?)", conversation);
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
            statement.execute("CREATE TABLE room_settings(room_id INTEGER PRIMARY KEY, "
                    + "max_file_size INTEGER DEFAULT 10737418240, "
                    + "total_file_space INTEGER DEFAULT 10737418240, "
                    + "max_file_count INTEGER DEFAULT 1500, max_members INTEGER)");
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
            statement.execute("INSERT INTO room_settings(room_id, max_file_size, "
                    + "total_file_space, max_file_count, max_members) "
                    + "VALUES (10, 2048, 8192, 42, 137)");
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
            statement.execute("CREATE TABLE room_settings(room_id INTEGER PRIMARY KEY, "
                    + "max_file_size INTEGER DEFAULT 10737418240, "
                    + "total_file_space INTEGER DEFAULT 10737418240, "
                    + "max_file_count INTEGER DEFAULT 1500, max_members INTEGER)");
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
            statement.execute("INSERT INTO room_settings(room_id, max_members) VALUES (77, 50)");
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

    private static byte[] filledBytes(int length, byte value) {
        byte[] result = new byte[length]; Arrays.fill(result, value); return result;
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
