package com.fallingnight.chat.application.compatibility.v1;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.Set;
public final class LegacyV1RoomMessageService implements LegacyV1RoomMessageUseCase {
    public static final int MAX_CLIENT_MESSAGE_ID_UTF8_BYTES = 128;
    public static final int MAX_CONTENT_UTF8_BYTES = 65_536;
    private static final Set<String> CONTENT_TYPES = Set.of("text", "emoji");
    private final LegacyV1RoomMessagePort messages;
    public LegacyV1RoomMessageService(LegacyV1RoomMessagePort messages) {
        this.messages = Objects.requireNonNull(messages, "messages");
    }
    @Override public LegacyV1RoomMessageResult submit(LegacyV1RoomMessageCommand command) {
        Objects.requireNonNull(command, "command");
        Objects.requireNonNull(command.senderAccountId(), "senderAccountId");
        Objects.requireNonNull(command.senderDeviceId(), "senderDeviceId");
        if (command.legacyRoomId() <= 0 || command.legacyRoomId() > Integer.MAX_VALUE)
            return LegacyV1RoomMessageResult.Rejected.ROOM_ACCESS_DENIED;
        if (!bounded(command.clientMessageId(), MAX_CLIENT_MESSAGE_ID_UTF8_BYTES))
            return LegacyV1RoomMessageResult.Rejected.INVALID_CLIENT_MESSAGE_ID;
        if (!bounded(command.content(), MAX_CONTENT_UTF8_BYTES)
                || !CONTENT_TYPES.contains(command.contentType()))
            return LegacyV1RoomMessageResult.Rejected.INVALID_MESSAGE;
        LegacyV1RoomMessageResult result = Objects.requireNonNull(
                messages.submit(command), "room message result");
        if (result instanceof LegacyV1RoomMessageResult.Accepted accepted
                && accepted.legacyRoomId() != command.legacyRoomId())
            throw new IllegalStateException("room message target changed");
        return result;
    }
    private static boolean bounded(String value, int maximumBytes) {
        return value != null && !value.isEmpty()
                && value.getBytes(StandardCharsets.UTF_8).length <= maximumBytes;
    }
}
