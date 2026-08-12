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
        return plan(bundle.statePlan(), bundle.payloadPlan(), List.of(),
                "0".repeat(64), "0".repeat(64));
    }

    public V1MessageTargetImportPlan plan(VerifiedV1UnifiedMessageImportBundle bundle) {
        return plan(bundle.statePlan(), bundle.payloadPlan(),
                bundle.attachmentPlan().attachments(),
                bundle.attachmentPlan().sourceFingerprintSha256(),
                bundle.attachmentPlan().evidenceFingerprintSha256());
    }

    private static V1MessageTargetImportPlan plan(
            V1MessageStateImportPlan statePlan,
            V1MessagePayloadImportPlan payloadPlan,
            List<PlannedV1AttachmentImport> attachmentImports,
            String attachmentSourceFingerprint,
            String attachmentEvidenceFingerprint) {
        Map<MessageKey, PlannedV1MessagePayload> payloads = new HashMap<>();
        for (PlannedV1MessagePayload payload : payloadPlan.messages()) {
            payloads.put(new MessageKey(payload.legacyKind(), payload.legacyMessageId()), payload);
        }
        Map<MessageKey, PlannedV1AttachmentImport> attachments = new HashMap<>();
        for (PlannedV1AttachmentImport attachment : attachmentImports) {
            PlannedV1AttachmentSource source = attachment.source();
            attachments.put(new MessageKey(source.legacyKind(), source.legacyMessageId()),
                    attachment);
        }
        Map<UUID, PlannedV1LegacyDevice> devices = new LinkedHashMap<>();
        List<PlannedV1HistoricalMessage> messages = new ArrayList<>();
        for (V1MessageCursorRow state : statePlan.sourceMessageRows()) {
            MessageKey key = new MessageKey(state.legacyKind(), state.legacyMessageId());
            PlannedV1MessagePayload payload = payloads.get(
                    key);
            PlannedV1AttachmentImport attachment = attachments.get(key);
            if ((payload == null) == (attachment == null)) {
                throw new V1MessageImportBundleException(
                        "verified V1 message content disappeared during target planning");
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
                    payload != null ? payload.messageId() : attachment.source().messageId(),
                    conversationId(state.legacyKind(), state.legacyConversationId()),
                    state.creationSequence(),
                    state.mutationSequence(),
                    accountId,
                    device.deviceId(),
                    payload != null ? payload.targetClientMessageId()
                            : "v1-import-" + state.legacyKind().name().toLowerCase(
                                    java.util.Locale.ROOT) + "-" + state.legacyMessageId(),
                    payload != null ? payload.targetContentType() : 2,
                    payload != null ? payload.legacyContentType()
                            : attachment.source().legacyContentType(),
                    payload != null ? payload.targetText() : "",
                    attachment == null ? null : attachment.source().attachmentId(),
                    state.recalled(),
                    payload == null || payload.historicalContentAvailable(),
                    state.createdAt()));
        }
        messages.sort(Comparator
                .comparing((PlannedV1HistoricalMessage value) ->
                        value.conversationId().toString())
                .thenComparingLong(PlannedV1HistoricalMessage::creationSequence));
        List<PlannedV1LegacyDevice> sortedDevices = new ArrayList<>(devices.values());
        sortedDevices.sort(Comparator.comparing(value -> value.accountId().toString()));
        List<PlannedV1DeletionEvent> deletions = statePlan.sourceDeletionEventRows()
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
                statePlan.sourceFingerprintSha256(),
                payloadPlan.sourceFingerprintSha256(),
                attachmentSourceFingerprint,
                attachmentEvidenceFingerprint,
                sortedDevices,
                attachmentImports,
                messages,
                deletions,
                statePlan.conversationCursors(),
                statePlan.memberReadCursors());
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
