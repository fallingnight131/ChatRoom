package com.fallingnight.chat.application.compatibility.v1;

import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.Set;

/** Bounded V1 text-like direct-message submission policy. */
public final class LegacyV1DirectMessageService implements LegacyV1DirectMessageUseCase {
    public static final int MAX_USERNAME_UTF8_BYTES = 128;
    public static final int MAX_CLIENT_MESSAGE_ID_UTF8_BYTES = 128;
    public static final int MAX_CONTENT_UTF8_BYTES = 65_536;
    private static final Set<String> CONTENT_TYPES = Set.of("text", "emoji");
    private final LegacyV1DirectMessagePort messages;

    public LegacyV1DirectMessageService(LegacyV1DirectMessagePort messages) {
        this.messages = Objects.requireNonNull(messages, "messages");
    }

    @Override
    public LegacyV1DirectMessageResult submit(LegacyV1DirectMessageCommand command) {
        Objects.requireNonNull(command, "command");
        if (!exactBounded(command.targetUsername(), MAX_USERNAME_UTF8_BYTES)) {
            return LegacyV1DirectMessageResult.Rejected.FRIENDSHIP_ACCESS_DENIED;
        }
        if (!bounded(command.clientMessageId(), MAX_CLIENT_MESSAGE_ID_UTF8_BYTES)) {
            return LegacyV1DirectMessageResult.Rejected.INVALID_CLIENT_MESSAGE_ID;
        }
        if (!bounded(command.content(), MAX_CONTENT_UTF8_BYTES)
                || !CONTENT_TYPES.contains(command.contentType())) {
            return LegacyV1DirectMessageResult.Rejected.INVALID_MESSAGE;
        }
        LegacyV1DirectMessageResult result = Objects.requireNonNull(
                messages.submit(command), "direct message result");
        if (result instanceof LegacyV1DirectMessageResult.Accepted accepted
                && !accepted.targetUsername().equals(command.targetUsername())) {
            throw new IllegalStateException("direct message target changed");
        }
        return result;
    }

    private static boolean exactBounded(String value, int maximumBytes) {
        return bounded(value, maximumBytes)
                && value.equals(value.strip())
                && value.codePoints().noneMatch(Character::isISOControl);
    }

    private static boolean bounded(String value, int maximumBytes) {
        return value != null && !value.isEmpty()
                && value.getBytes(StandardCharsets.UTF_8).length <= maximumBytes;
    }
}
