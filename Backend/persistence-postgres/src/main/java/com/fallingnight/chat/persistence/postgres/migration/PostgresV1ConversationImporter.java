package com.fallingnight.chat.persistence.postgres.migration;

import com.fallingnight.chat.application.compatibility.v1.LegacyV1ConversationKind;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import javax.sql.DataSource;

/** Strict one-way V1 conversation metadata importer with atomic reconciliation. */
public final class PostgresV1ConversationImporter {
    private final DataSource dataSource;

    public PostgresV1ConversationImporter(DataSource dataSource) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
    }

    public V1ConversationImportReport preview(V1ConversationImportPlan plan) {
        requireReady(plan);
        try (Connection connection = dataSource.getConnection()) {
            connection.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);
            connection.setReadOnly(true);
            connection.setAutoCommit(false);
            try {
                Comparison comparison = compare(connection, plan);
                connection.commit();
                return comparison.report(plan, false, null);
            } catch (RuntimeException | SQLException exception) {
                rollback(connection, exception);
                throw exception;
            }
        } catch (SQLException exception) {
            throw new V1ConversationImportException(
                    "V1 conversation target preview failed", exception);
        }
    }

    public V1ConversationImportReport apply(VerifiedV1ConversationImportInput input) {
        Objects.requireNonNull(input, "input");
        V1ConversationImportPlan plan = input.plan();
        requireReady(plan);
        try (Connection connection = dataSource.getConnection()) {
            connection.setTransactionIsolation(Connection.TRANSACTION_SERIALIZABLE);
            connection.setAutoCommit(false);
            try {
                lockTarget(connection);
                Comparison before = compare(connection, plan);
                if (!before.ready()) {
                    throw new V1ConversationImportException(
                            "V1 conversation target contains blocking conflicts");
                }
                insertConversations(connection, before.conversationsToInsert());
                updateAdmissionPolicies(connection, before.policiesToUpdate());
                insertDirectPairs(connection, before.conversationsToInsert());
                insertMappings(connection, before.mappingsToInsert());
                insertMemberships(connection, before.membershipsToInsert());
                Comparison after = compare(connection, plan);
                if (!after.fullyReconciled(plan)) {
                    throw new V1ConversationImportException(
                            "V1 conversation post-write reconciliation failed");
                }
                input.reverify();
                UUID runId = UUID.randomUUID();
                persistProof(connection, runId, plan, input.backupProof(), before);
                connection.commit();
                return new V1ConversationImportReport(
                        plan.sourceFingerprintSha256(),
                        plan.conversations().size(),
                        plan.memberships().size(),
                        before.conversationsToInsert().size(),
                        before.alreadyConversations(),
                        before.membershipsToInsert().size(),
                        before.alreadyMemberships(),
                        before.policiesToUpdate().size(),
                        List.of(),
                        true,
                        true,
                        runId);
            } catch (RuntimeException | SQLException exception) {
                rollback(connection, exception);
                throw exception;
            }
        } catch (SQLException exception) {
            throw new V1ConversationImportException(
                    "V1 conversation target apply failed", exception);
        }
    }

    private static Comparison compare(Connection connection, V1ConversationImportPlan plan)
            throws SQLException {
        Map<UUID, PlannedV1Conversation> plannedById = new HashMap<>();
        for (PlannedV1Conversation planned : plan.conversations()) {
            plannedById.put(planned.conversationId(), planned);
        }
        Map<UUID, TargetConversation> targets = readConversations(connection, plannedById.keySet());
        Map<UUID, TargetMapping> mappings = readMappings(connection, plannedById.keySet(), plan);
        Map<UUID, TargetDirect> directs = readDirects(connection, plannedById.keySet(), plan);
        Map<UUID, TargetGroupPolicy> policies = readGroupPolicies(
                connection, plannedById.keySet());
        Map<MemberKey, TargetMember> members = readMembers(connection, plannedById.keySet());
        Set<UUID> existingAccounts = readAccounts(connection, plan.memberships());

        List<PlannedV1Conversation> conversationsToInsert = new ArrayList<>();
        List<PlannedV1Conversation> mappingsToInsert = new ArrayList<>();
        List<PlannedV1ConversationMember> membershipsToInsert = new ArrayList<>();
        List<PlannedV1Conversation> policiesToUpdate = new ArrayList<>();
        List<V1ConversationImportIssue> issues = new ArrayList<>();
        int alreadyConversations = 0;
        int alreadyMemberships = 0;

        for (PlannedV1Conversation planned : plan.conversations()) {
            TargetConversation target = targets.get(planned.conversationId());
            TargetMapping mapping = mappings.get(planned.conversationId());
            TargetDirect direct = directs.get(planned.conversationId());
            TargetGroupPolicy policy = policies.get(planned.conversationId());
            boolean mappingMatches = mapping != null && mapping.matches(planned);
            boolean directMatches = planned.legacyKind() == LegacyV1ConversationKind.ROOM
                    ? direct == null : direct != null && direct.matches(planned);
            boolean policyMatches = planned.legacyKind() == LegacyV1ConversationKind.ROOM
                    ? policy != null && policy.matches(planned) : policy == null;
            boolean policyCanMigrate = planned.legacyKind() == LegacyV1ConversationKind.ROOM
                    && policy != null && policy.maxMembers() == 50;
            if (target == null && mapping == null && direct == null && policy == null) {
                conversationsToInsert.add(planned);
                mappingsToInsert.add(planned);
            } else if (target != null && target.matches(planned)
                    && directMatches && (policyMatches || policyCanMigrate)) {
                if (!policyMatches) policiesToUpdate.add(planned);
                if (mapping == null) {
                    mappingsToInsert.add(planned);
                    alreadyConversations++;
                } else if (mappingMatches) {
                    alreadyConversations++;
                } else {
                    issues.add(issue(planned, "TARGET_CONVERSATION_MAPPING_CONFLICT",
                            "target V1 conversation mapping differs from plan"));
                }
            } else if (target != null && target.matches(planned)
                    && directMatches && !policyMatches) {
                issues.add(issue(planned, "TARGET_GROUP_POLICY_CONFLICT",
                        "target group admission policy differs from plan"));
            } else {
                issues.add(issue(planned, "TARGET_CONVERSATION_CONFLICT",
                        "target conversation differs from planned V1 conversation"));
            }
        }

        Set<MemberKey> plannedMemberKeys = new HashSet<>();
        for (PlannedV1ConversationMember planned : plan.memberships()) {
            MemberKey key = new MemberKey(planned.conversationId(), planned.accountId());
            plannedMemberKeys.add(key);
            PlannedV1Conversation parent = plannedById.get(planned.conversationId());
            if (!existingAccounts.contains(planned.accountId())) {
                issues.add(issue(parent, "TARGET_ACCOUNT_MISSING",
                        "planned conversation member account is absent"));
                continue;
            }
            TargetMember target = members.get(key);
            if (target == null) {
                membershipsToInsert.add(planned);
            } else if (target.matches(planned)) {
                alreadyMemberships++;
            } else {
                issues.add(issue(parent, "TARGET_MEMBERSHIP_CONFLICT",
                        "target membership differs from planned V1 membership"));
            }
        }
        for (MemberKey target : members.keySet()) {
            if (!plannedMemberKeys.contains(target)) {
                PlannedV1Conversation parent = plannedById.get(target.conversationId());
                issues.add(issue(parent, "TARGET_UNEXPECTED_MEMBERSHIP",
                        "target conversation contains an unexpected membership"));
            }
        }
        return new Comparison(
                List.copyOf(conversationsToInsert),
                List.copyOf(mappingsToInsert),
                List.copyOf(membershipsToInsert),
                List.copyOf(policiesToUpdate),
                alreadyConversations,
                alreadyMemberships,
                List.copyOf(issues));
    }

    private static Map<UUID, TargetConversation> readConversations(
            Connection connection, Set<UUID> ids) throws SQLException {
        Map<UUID, TargetConversation> result = new HashMap<>();
        if (ids.isEmpty()) return result;
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT id, kind, title, next_sequence, created_at, updated_at
                FROM chat.conversation WHERE id = ANY (?)
                """)) {
            statement.setArray(1, connection.createArrayOf("uuid", ids.toArray()));
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    UUID id = rows.getObject("id", UUID.class);
                    result.put(id, new TargetConversation(
                            id, rows.getString("kind"), rows.getString("title"),
                            rows.getLong("next_sequence"),
                            rows.getObject("created_at", OffsetDateTime.class).toInstant(),
                            rows.getObject("updated_at", OffsetDateTime.class).toInstant()));
                }
            }
        }
        return result;
    }

    private static Map<UUID, TargetMapping> readMappings(
            Connection connection, Set<UUID> ids, V1ConversationImportPlan plan)
            throws SQLException {
        Map<UUID, TargetMapping> result = new HashMap<>();
        if (ids.isEmpty()) return result;
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT legacy_kind, legacy_conversation_id, conversation_id
                FROM chat.legacy_v1_conversation_map
                WHERE conversation_id = ANY (?)
                   OR (legacy_kind, legacy_conversation_id) IN (
                       SELECT * FROM unnest(?::varchar[], ?::bigint[]))
                """)) {
            statement.setArray(1, connection.createArrayOf("uuid", ids.toArray()));
            statement.setArray(2, connection.createArrayOf("varchar", plan.conversations().stream()
                    .map(value -> value.legacyKind().name()).toArray()));
            statement.setArray(3, connection.createArrayOf("bigint", plan.conversations().stream()
                    .map(PlannedV1Conversation::legacyId).toArray()));
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    UUID conversationId = rows.getObject("conversation_id", UUID.class);
                    TargetMapping target = new TargetMapping(
                            rows.getString("legacy_kind"),
                            rows.getLong("legacy_conversation_id"), conversationId);
                    for (PlannedV1Conversation planned : plan.conversations()) {
                        if (conversationId.equals(planned.conversationId())
                                || (target.kind().equals(planned.legacyKind().name())
                                    && target.legacyId() == planned.legacyId())) {
                            result.put(planned.conversationId(), target);
                        }
                    }
                }
            }
        }
        return result;
    }

    private static Map<UUID, TargetDirect> readDirects(
            Connection connection, Set<UUID> ids, V1ConversationImportPlan plan)
            throws SQLException {
        Map<UUID, TargetDirect> result = new HashMap<>();
        List<PlannedV1Conversation> directPlans = plan.conversations().stream()
                .filter(value -> value.legacyKind() == LegacyV1ConversationKind.FRIENDSHIP)
                .toList();
        if (directPlans.isEmpty()) return result;
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT conversation_id, first_account_id, second_account_id
                FROM chat.direct_conversation
                WHERE conversation_id = ANY (?)
                   OR (first_account_id, second_account_id) IN (
                       SELECT * FROM unnest(?::uuid[], ?::uuid[]))
                """)) {
            statement.setArray(1, connection.createArrayOf("uuid", ids.toArray()));
            statement.setArray(2, connection.createArrayOf("uuid", directPlans.stream()
                    .map(PlannedV1Conversation::firstAccountId).toArray()));
            statement.setArray(3, connection.createArrayOf("uuid", directPlans.stream()
                    .map(PlannedV1Conversation::secondAccountId).toArray()));
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    UUID conversationId = rows.getObject("conversation_id", UUID.class);
                    TargetDirect target = new TargetDirect(
                            conversationId,
                            rows.getObject("first_account_id", UUID.class),
                            rows.getObject("second_account_id", UUID.class));
                    for (PlannedV1Conversation planned : directPlans) {
                        if (conversationId.equals(planned.conversationId())
                                || (target.first().equals(planned.firstAccountId())
                                    && target.second().equals(planned.secondAccountId()))) {
                            result.put(planned.conversationId(), target);
                        }
                    }
                }
            }
        }
        return result;
    }

    private static Map<UUID, TargetGroupPolicy> readGroupPolicies(
            Connection connection, Set<UUID> ids) throws SQLException {
        Map<UUID, TargetGroupPolicy> result = new HashMap<>();
        if (ids.isEmpty()) return result;
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT conversation_id, max_members
                FROM chat.group_admission_policy WHERE conversation_id = ANY (?)
                """)) {
            statement.setArray(1, connection.createArrayOf("uuid", ids.toArray()));
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    UUID conversationId = rows.getObject("conversation_id", UUID.class);
                    result.put(conversationId, new TargetGroupPolicy(
                            conversationId, rows.getInt("max_members")));
                }
            }
        }
        return result;
    }

    private static Map<MemberKey, TargetMember> readMembers(
            Connection connection, Set<UUID> ids) throws SQLException {
        Map<MemberKey, TargetMember> result = new HashMap<>();
        if (ids.isEmpty()) return result;
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT conversation_id, account_id, role, joined_at,
                       left_at, last_read_sequence
                FROM chat.conversation_member WHERE conversation_id = ANY (?)
                """)) {
            statement.setArray(1, connection.createArrayOf("uuid", ids.toArray()));
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    MemberKey key = new MemberKey(
                            rows.getObject("conversation_id", UUID.class),
                            rows.getObject("account_id", UUID.class));
                    OffsetDateTime left = rows.getObject("left_at", OffsetDateTime.class);
                    result.put(key, new TargetMember(
                            key, rows.getString("role"),
                            rows.getObject("joined_at", OffsetDateTime.class).toInstant(),
                            left == null ? null : left.toInstant(),
                            rows.getLong("last_read_sequence")));
                }
            }
        }
        return result;
    }

    private static Set<UUID> readAccounts(
            Connection connection, List<PlannedV1ConversationMember> members)
            throws SQLException {
        Set<UUID> requested = members.stream().map(PlannedV1ConversationMember::accountId)
                .collect(java.util.stream.Collectors.toSet());
        Set<UUID> result = new HashSet<>();
        if (requested.isEmpty()) return result;
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT id FROM chat.account WHERE id = ANY (?) AND disabled_at IS NULL")) {
            statement.setArray(1, connection.createArrayOf("uuid", requested.toArray()));
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) result.add(rows.getObject(1, UUID.class));
            }
        }
        return result;
    }

    private static void insertConversations(
            Connection connection, List<PlannedV1Conversation> planned) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO chat.conversation(id, kind, title, next_sequence, created_at, updated_at)
                VALUES (?, ?, ?, 1, ?, ?)
                """)) {
            for (PlannedV1Conversation value : planned) {
                statement.setObject(1, value.conversationId());
                statement.setString(2, value.legacyKind() == LegacyV1ConversationKind.ROOM
                        ? "GROUP" : "DIRECT");
                statement.setString(3, value.groupTitle());
                OffsetDateTime created = OffsetDateTime.ofInstant(value.createdAt(), ZoneOffset.UTC);
                statement.setObject(4, created);
                statement.setObject(5, created);
                statement.addBatch();
            }
            requireBatch(statement.executeBatch(), "conversation");
        }
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO chat.group_admission_policy(conversation_id, max_members)
                VALUES (?, ?)
                """)) {
            int expected = 0;
            for (PlannedV1Conversation value : planned) {
                if (value.legacyKind() != LegacyV1ConversationKind.ROOM) continue;
                statement.setObject(1, value.conversationId());
                statement.setInt(2, value.maxMembers());
                statement.addBatch();
                expected++;
            }
            if (expected > 0) requireBatch(statement.executeBatch(), "group admission policy");
        }
    }

    private static void insertDirectPairs(
            Connection connection, List<PlannedV1Conversation> planned) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO chat.direct_conversation(
                    conversation_id, first_account_id, second_account_id)
                VALUES (?, ?, ?)
                """)) {
            int expected = 0;
            for (PlannedV1Conversation value : planned) {
                if (value.legacyKind() != LegacyV1ConversationKind.FRIENDSHIP) continue;
                statement.setObject(1, value.conversationId());
                statement.setObject(2, value.firstAccountId());
                statement.setObject(3, value.secondAccountId());
                statement.addBatch();
                expected++;
            }
            requireBatch(statement.executeBatch(), "direct conversation", expected);
        }
    }

    private static void updateAdmissionPolicies(Connection connection,
            List<PlannedV1Conversation> planned) throws SQLException {
        if (planned.isEmpty()) return;
        try (PreparedStatement statement = connection.prepareStatement("""
                UPDATE chat.group_admission_policy
                SET max_members = ?, updated_at = transaction_timestamp()
                WHERE conversation_id = ? AND max_members = 50
                """)) {
            for (PlannedV1Conversation value : planned) {
                statement.setInt(1, value.maxMembers());
                statement.setObject(2, value.conversationId());
                statement.addBatch();
            }
            requireBatch(statement.executeBatch(), "group admission policy update");
        }
    }

    private static void insertMappings(
            Connection connection, List<PlannedV1Conversation> planned) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO chat.legacy_v1_conversation_map(
                    legacy_kind, legacy_conversation_id, conversation_id)
                VALUES (?, ?, ?)
                """)) {
            for (PlannedV1Conversation value : planned) {
                statement.setString(1, value.legacyKind().name());
                statement.setLong(2, value.legacyId());
                statement.setObject(3, value.conversationId());
                statement.addBatch();
            }
            requireBatch(statement.executeBatch(), "conversation mapping");
        }
    }

    private static void insertMemberships(
            Connection connection, List<PlannedV1ConversationMember> planned) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO chat.conversation_member(
                    conversation_id, account_id, role, joined_at, last_read_sequence)
                VALUES (?, ?, ?, ?, 0)
                """)) {
            for (PlannedV1ConversationMember value : planned) {
                statement.setObject(1, value.conversationId());
                statement.setObject(2, value.accountId());
                statement.setString(3, value.role());
                statement.setObject(4, OffsetDateTime.ofInstant(value.joinedAt(), ZoneOffset.UTC));
                statement.addBatch();
            }
            requireBatch(statement.executeBatch(), "conversation membership");
        }
    }

    private static void persistProof(
            Connection connection, UUID runId, V1ConversationImportPlan plan,
            VerifiedV1IdentityBackup proof, Comparison before) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO chat.conversation_import_run(
                    id, source_fingerprint_sha256, backup_file_sha256,
                    source_rooms, source_friendships, source_memberships,
                    inserted_conversations, already_imported_conversations,
                    inserted_memberships, already_imported_memberships,
                    backup_bytes, backup_created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """)) {
            statement.setObject(1, runId);
            statement.setString(2, plan.sourceFingerprintSha256());
            statement.setString(3, proof.backupFileSha256());
            statement.setInt(4, plan.sourceRooms());
            statement.setInt(5, plan.sourceFriendships());
            statement.setInt(6, plan.memberships().size());
            statement.setInt(7, before.conversationsToInsert().size());
            statement.setInt(8, before.alreadyConversations());
            statement.setInt(9, before.membershipsToInsert().size());
            statement.setInt(10, before.alreadyMemberships());
            statement.setLong(11, proof.backupBytes());
            statement.setObject(12, OffsetDateTime.ofInstant(proof.createdAt(), ZoneOffset.UTC));
            if (statement.executeUpdate() != 1) {
                throw new SQLException("conversation import proof was not persisted");
            }
        }
    }

    private static void lockTarget(Connection connection) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "LOCK TABLE chat.account, chat.conversation, chat.direct_conversation, "
                        + "chat.conversation_member, chat.legacy_v1_conversation_map, "
                        + "chat.group_admission_policy, chat.conversation_import_run "
                        + "IN SHARE ROW EXCLUSIVE MODE")) {
            statement.execute();
        }
    }

    private static void requireBatch(int[] counts, String name) throws SQLException {
        requireBatch(counts, name, counts.length);
    }

    private static void requireBatch(int[] counts, String name, int expected) throws SQLException {
        if (counts.length != expected) throw new SQLException(name + " batch size mismatch");
        for (int count : counts) if (count != 1) {
            throw new SQLException(name + " insert did not affect exactly one row");
        }
    }

    private static V1ConversationImportIssue issue(
            PlannedV1Conversation planned, String code, String message) {
        return new V1ConversationImportIssue(
                planned.legacyKind(), planned.legacyId(), code, message);
    }

    private static void requireReady(V1ConversationImportPlan plan) {
        Objects.requireNonNull(plan, "plan");
        if (!plan.readyToCompareWithTarget()) {
            throw new V1ConversationImportException(
                    "V1 conversation plan is not ready for target comparison");
        }
    }

    private static void rollback(Connection connection, Exception original) {
        try { connection.rollback(); } catch (SQLException failure) {
            original.addSuppressed(failure);
        }
    }

    private record Comparison(
            List<PlannedV1Conversation> conversationsToInsert,
            List<PlannedV1Conversation> mappingsToInsert,
            List<PlannedV1ConversationMember> membershipsToInsert,
            List<PlannedV1Conversation> policiesToUpdate,
            int alreadyConversations,
            int alreadyMemberships,
            List<V1ConversationImportIssue> issues) {
        boolean ready() { return issues.isEmpty(); }
        boolean fullyReconciled(V1ConversationImportPlan plan) {
            return ready() && conversationsToInsert.isEmpty()
                    && mappingsToInsert.isEmpty() && membershipsToInsert.isEmpty()
                    && policiesToUpdate.isEmpty()
                    && alreadyConversations == plan.conversations().size()
                    && alreadyMemberships == plan.memberships().size();
        }
        V1ConversationImportReport report(
                V1ConversationImportPlan plan, boolean applied, UUID runId) {
            return new V1ConversationImportReport(
                    plan.sourceFingerprintSha256(), plan.conversations().size(),
                    plan.memberships().size(), conversationsToInsert.size(),
                    alreadyConversations, membershipsToInsert.size(), alreadyMemberships,
                    policiesToUpdate.size(),
                    issues, applied, applied, runId);
        }
    }

    private record TargetConversation(
            UUID id, String kind, String title, long nextSequence,
            java.time.Instant createdAt, java.time.Instant updatedAt) {
        boolean matches(PlannedV1Conversation value) {
            String expectedKind = value.legacyKind() == LegacyV1ConversationKind.ROOM
                    ? "GROUP" : "DIRECT";
            return id.equals(value.conversationId()) && kind.equals(expectedKind)
                    && Objects.equals(title, value.groupTitle()) && nextSequence == 1
                    && createdAt.equals(value.createdAt()) && updatedAt.equals(value.createdAt());
        }
    }
    private record TargetMapping(String kind, long legacyId, UUID conversationId) {
        boolean matches(PlannedV1Conversation value) {
            return kind.equals(value.legacyKind().name()) && legacyId == value.legacyId()
                    && conversationId.equals(value.conversationId());
        }
    }
    private record TargetDirect(UUID conversationId, UUID first, UUID second) {
        boolean matches(PlannedV1Conversation value) {
            return conversationId.equals(value.conversationId())
                    && first.equals(value.firstAccountId()) && second.equals(value.secondAccountId());
        }
    }
    private record TargetGroupPolicy(UUID conversationId, int maxMembers) {
        boolean matches(PlannedV1Conversation value) {
            return conversationId.equals(value.conversationId())
                    && value.maxMembers() != null && maxMembers == value.maxMembers();
        }
    }
    private record MemberKey(UUID conversationId, UUID accountId) {}
    private record TargetMember(
            MemberKey key, String role, java.time.Instant joinedAt,
            java.time.Instant leftAt, long lastReadSequence) {
        boolean matches(PlannedV1ConversationMember value) {
            return key.conversationId().equals(value.conversationId())
                    && key.accountId().equals(value.accountId()) && role.equals(value.role())
                    && joinedAt.equals(value.joinedAt()) && leftAt == null
                    && lastReadSequence == 0;
        }
    }
}
