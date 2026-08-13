package com.fallingnight.chat.application.messaging;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Objects;
import java.util.UUID;

/** Authenticated, transport-neutral intent to edit one V2-native text message. */
public record MessageEditCommand(
        UUID conversationId,
        UUID messageId,
        UUID actorAccountId,
        UUID actorDeviceId,
        int expectedRevision,
        int contentType,
        byte[] content,
        String clientOperationId) {
    public static final int TEXT_UTF8_CONTENT_TYPE = 1;
    public static final int MAX_CONTENT_BYTES = 65_536;
    public static final int MAX_REVISION = 100;

    public MessageEditCommand {
        Objects.requireNonNull(conversationId, "conversationId");
        Objects.requireNonNull(messageId, "messageId");
        Objects.requireNonNull(actorAccountId, "actorAccountId");
        Objects.requireNonNull(actorDeviceId, "actorDeviceId");
        Objects.requireNonNull(content, "content");
        Objects.requireNonNull(clientOperationId, "clientOperationId");
        int operationBytes = clientOperationId.getBytes(StandardCharsets.UTF_8).length;
        if (clientOperationId.isBlank() || operationBytes > 128) {
            throw new IllegalArgumentException("clientOperationId UTF-8 length must be 1..128");
        }
        if (expectedRevision < 0 || expectedRevision > MAX_REVISION) {
            throw new IllegalArgumentException("expectedRevision must be 0..100");
        }
        if (contentType != TEXT_UTF8_CONTENT_TYPE) {
            throw new IllegalArgumentException("only UTF-8 text messages are editable");
        }
        if (content.length == 0 || content.length > MAX_CONTENT_BYTES) {
            throw new IllegalArgumentException("content byte length must be 1..65536");
        }
        try {
            StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(content));
        } catch (CharacterCodingException invalidUtf8) {
            throw new IllegalArgumentException("content must be valid UTF-8", invalidUtf8);
        }
        content = Arrays.copyOf(content, content.length);
    }

    @Override
    public byte[] content() {
        return Arrays.copyOf(content, content.length);
    }
}
