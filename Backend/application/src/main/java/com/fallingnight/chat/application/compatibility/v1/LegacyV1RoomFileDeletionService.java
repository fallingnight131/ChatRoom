package com.fallingnight.chat.application.compatibility.v1;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;

/** Strict idempotent administrator selected-file deletion policy. */
public final class LegacyV1RoomFileDeletionService
        implements LegacyV1RoomFileDeletionUseCase {
    public static final int MAX_FILES = 100;
    public static final int MAX_OPERATION_ID_BYTES = 128;
    private final LegacyV1RoomFileDeletionPort deletion;

    public LegacyV1RoomFileDeletionService(LegacyV1RoomFileDeletionPort deletion) {
        this.deletion = Objects.requireNonNull(deletion, "deletion");
    }

    @Override public LegacyV1RoomFileDeletionResult delete(
            LegacyV1RoomFileDeletionCommand command) {
        Objects.requireNonNull(command, "command");
        Objects.requireNonNull(command.actorAccountId(), "actorAccountId");
        if (command.legacyRoomId() <= 0 || command.legacyRoomId() > Integer.MAX_VALUE
                || !validOperationId(command.clientOperationId())
                || command.legacyFileIds() == null
                || command.legacyFileIds().isEmpty()
                || command.legacyFileIds().size() > MAX_FILES
                || command.legacyFileIds().stream().anyMatch(id -> id == null || id <= 0
                    || id > Integer.MAX_VALUE)
                || command.legacyFileIds().stream().distinct().count()
                    != command.legacyFileIds().size()) {
            return LegacyV1RoomFileDeletionResult.Rejected.INVALID_INPUT;
        }
        List<Long> ids = command.legacyFileIds().stream().sorted().toList();
        LegacyV1RoomFileDeletionResult result = Objects.requireNonNull(deletion.delete(
                new LegacyV1RoomFileDeletionIntent(command.actorAccountId(),
                        command.legacyRoomId(), command.clientOperationId(),
                        fingerprint(command.legacyRoomId(), ids), ids)), "deletion result");
        if (result instanceof LegacyV1RoomFileDeletionResult.Deleted deleted
                && (deleted.legacyRoomId() != command.legacyRoomId()
                    || !deleted.clientOperationId().equals(command.clientOperationId()))) {
            throw new IllegalStateException("V1 room file deletion identity changed");
        }
        return result;
    }

    private static boolean validOperationId(String value) {
        return value != null && !value.isBlank()
                && value.getBytes(StandardCharsets.UTF_8).length <= MAX_OPERATION_ID_BYTES
                && value.codePoints().noneMatch(Character::isISOControl);
    }

    private static String fingerprint(long roomId, List<Long> ids) {
        String canonical = "room-files-delete:v1:" + roomId + ":"
                + String.join(",", ids.stream().map(String::valueOf).toList());
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 unavailable", exception);
        }
    }
}
