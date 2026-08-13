package com.fallingnight.chat.application.compatibility.v1;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;

/** Strict policy and canonical fingerprint for V1 administrative message deletion. */
public final class LegacyV1RoomMessageDeletionService
        implements LegacyV1RoomMessageDeletionUseCase {
    public static final int MAX_SELECTED = 100;
    public static final int MAX_FILES = 1_500;
    public static final int MAX_OPERATION_ID_BYTES = 128;
    public static final long MAX_CUTOFF_EPOCH_MILLIS = 253_402_300_799_999L;
    private final LegacyV1RoomMessageDeletionPort deletion;

    public LegacyV1RoomMessageDeletionService(LegacyV1RoomMessageDeletionPort deletion) {
        this.deletion = Objects.requireNonNull(deletion, "deletion");
    }

    @Override public LegacyV1RoomMessageDeletionResult delete(
            LegacyV1RoomMessageDeletionCommand command) {
        Objects.requireNonNull(command, "command");
        Objects.requireNonNull(command.actorAccountId(), "actorAccountId");
        var parsedMode = LegacyV1RoomMessageDeletionMode.parse(command.mode());
        if (command.legacyRoomId() <= 0 || command.legacyRoomId() > Integer.MAX_VALUE
                || !validOperationId(command.clientOperationId()) || parsedMode.isEmpty()
                || command.legacyMessageIds() == null) {
            return LegacyV1RoomMessageDeletionResult.Rejected.INVALID_INPUT;
        }
        LegacyV1RoomMessageDeletionMode mode = parsedMode.orElseThrow();
        List<Long> ids = command.legacyMessageIds();
        if ((mode == LegacyV1RoomMessageDeletionMode.SELECTED
                    && (ids.isEmpty() || ids.size() > MAX_SELECTED))
                || (mode != LegacyV1RoomMessageDeletionMode.SELECTED && !ids.isEmpty())
                || ids.stream().anyMatch(id -> id == null || id <= 0
                    || id > Integer.MAX_VALUE)
                || ids.stream().distinct().count() != ids.size()
                || (mode.usesCutoff() && (command.cutoffEpochMillis() <= 0
                    || command.cutoffEpochMillis() > MAX_CUTOFF_EPOCH_MILLIS))
                || (!mode.usesCutoff() && command.cutoffEpochMillis() != 0)) {
            return LegacyV1RoomMessageDeletionResult.Rejected.INVALID_INPUT;
        }
        List<Long> normalizedIds = ids.stream().sorted().toList();
        long normalizedCutoff = mode.usesCutoff()
                ? command.cutoffEpochMillis() / 1_000 * 1_000 : 0;
        var intent = new LegacyV1RoomMessageDeletionIntent(command.actorAccountId(),
                command.legacyRoomId(), command.clientOperationId(), fingerprint(
                    command.legacyRoomId(), mode, normalizedIds, normalizedCutoff),
                mode, normalizedIds, normalizedCutoff);
        LegacyV1RoomMessageDeletionResult result = Objects.requireNonNull(
                deletion.delete(intent), "deletion result");
        if (result instanceof LegacyV1RoomMessageDeletionResult.Deleted deleted
                && (deleted.legacyRoomId() != command.legacyRoomId()
                    || !deleted.clientOperationId().equals(command.clientOperationId())
                    || deleted.mode() != mode || deleted.cutoffEpochMillis() != normalizedCutoff)) {
            throw new IllegalStateException("V1 room message deletion identity changed");
        }
        return result;
    }

    private static boolean validOperationId(String value) {
        return value != null && !value.isBlank()
                && value.getBytes(StandardCharsets.UTF_8).length <= MAX_OPERATION_ID_BYTES
                && value.codePoints().noneMatch(Character::isISOControl);
    }

    private static String fingerprint(long roomId, LegacyV1RoomMessageDeletionMode mode,
            List<Long> ids, long cutoff) {
        String canonical = "room-messages-delete:v1:" + roomId + ":" + mode.wireValue()
                + ":" + cutoff + ":"
                + String.join(",", ids.stream().map(String::valueOf).toList());
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 unavailable", exception);
        }
    }
}
