package com.fallingnight.chat.persistence.postgres.migration;

import com.fallingnight.chat.application.compatibility.v1.LegacyV1ConversationKind;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Joins a verified V1 message bundle into deterministic PostgreSQL write rows. */
public final class V1MessageTargetImportPlanner {
    private static final UUID LEGACY_DEVICE_NAMESPACE =
            UUID.fromString("eab1378c-1118-58cf-afad-ecc935a4d004");

    public V1MessageTargetImportPlan plan(VerifiedV1MessageImportBundle bundle) {
        Map<MessageKey, PlannedV1MessagePayload> payloads = new HashMap<>();
        for (PlannedV1MessagePayload payload : bundle.payloadPlan().messages()) {
            payloads.put(new MessageKey(payload.legacyKind(), payload.legacyMessageId()), payload);
        }
        Map<UUID, PlannedV1LegacyDevice> devices = new LinkedHashMap<>();
        List<PlannedV1HistoricalMessage> messages = new ArrayList<>();
        for (V1MessageCursorRow state : bundle.statePlan().sourceMessageRows()) {
            PlannedV1MessagePayload payload = payloads.get(
                    new MessageKey(state.legacyKind(), state.legacyMessageId()));
            if (payload == null) {
                throw new V1MessageImportBundleException(
                        "verified V1 message payload disappeared during target planning");
            }
            UUID accountId = V1IdentityImportPlanner.deterministicUserId(
                    state.legacySenderUserId());
            PlannedV1LegacyDevice device = devices.computeIfAbsent(accountId,
                    ignored -> new PlannedV1LegacyDevice(
                            accountId,
                            deterministicLegacyDeviceId(accountId),
                            "v1-history-import"));
            messages.add(new PlannedV1HistoricalMessage(
                    state.legacyKind(),
                    state.legacyConversationId(),
                    state.legacyMessageId(),
                    payload.messageId(),
                    conversationId(state.legacyKind(), state.legacyConversationId()),
                    state.creationSequence(),
                    state.mutationSequence(),
                    accountId,
                    device.deviceId(),
                    payload.targetClientMessageId(),
                    payload.targetContentType(),
                    payload.targetText(),
                    state.recalled(),
                    payload.historicalContentAvailable(),
                    state.createdAt()));
        }
        messages.sort(Comparator
                .comparing((PlannedV1HistoricalMessage value) ->
                        value.conversationId().toString())
                .thenComparingLong(PlannedV1HistoricalMessage::creationSequence));
        List<PlannedV1LegacyDevice> sortedDevices = new ArrayList<>(devices.values());
        sortedDevices.sort(Comparator.comparing(value -> value.accountId().toString()));
        List<PlannedV1DeletionEvent> deletions = bundle.statePlan().sourceDeletionEventRows()
                .stream()
                .map(row -> new PlannedV1DeletionEvent(
                        row.legacyEventId(),
                        row.legacyRoomId(),
                        V1ConversationImportPlanner.deterministicRoomId(row.legacyRoomId()),
                        row.sequence(),
                        V1IdentityImportPlanner.deterministicUserId(
                                row.legacyOperatorUserId()),
                        row.operatorName(),
                        row.clientOperationId(),
                        row.commandFingerprint(),
                        row.mode(),
                        row.messageIdsJson(),
                        row.fileIdsJson(),
                        row.cutoffEpochMs(),
                        row.deletedCount(),
                        row.createdAt()))
                .sorted(Comparator.comparing(
                        (PlannedV1DeletionEvent value) -> value.conversationId().toString())
                        .thenComparingLong(PlannedV1DeletionEvent::conversationSequence))
                .toList();
        return new V1MessageTargetImportPlan(
                bundle.statePlan().sourceFingerprintSha256(),
                bundle.payloadPlan().sourceFingerprintSha256(),
                sortedDevices,
                messages,
                deletions,
                bundle.statePlan().conversationCursors(),
                bundle.statePlan().memberReadCursors());
    }

    public static UUID deterministicLegacyDeviceId(UUID accountId) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-1");
            digest.update(uuidBytes(LEGACY_DEVICE_NAMESPACE));
            byte[] hash = digest.digest(("v1-history-device:" + accountId)
                    .getBytes(StandardCharsets.UTF_8));
            hash[6] = (byte) ((hash[6] & 0x0f) | 0x50);
            hash[8] = (byte) ((hash[8] & 0x3f) | 0x80);
            return new UUID(ByteBuffer.wrap(hash, 0, 8).getLong(),
                    ByteBuffer.wrap(hash, 8, 8).getLong());
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-1 is unavailable", exception);
        }
    }

    private static UUID conversationId(LegacyV1ConversationKind kind, long legacyId) {
        return kind == LegacyV1ConversationKind.ROOM
                ? V1ConversationImportPlanner.deterministicRoomId(legacyId)
                : V1ConversationImportPlanner.deterministicFriendshipId(legacyId);
    }

    private static byte[] uuidBytes(UUID value) {
        return ByteBuffer.allocate(16)
                .putLong(value.getMostSignificantBits())
                .putLong(value.getLeastSignificantBits())
                .array();
    }

    private record MessageKey(LegacyV1ConversationKind kind, long id) {}
}
