package com.fallingnight.chat.application.compatibility.v1;
import java.util.UUID;
public record LegacyV1RoomMessageCommand(UUID senderAccountId, UUID senderDeviceId,
        long legacyRoomId, String clientMessageId, String content, String contentType) { }
