package com.fallingnight.chat.application.messaging;

import java.util.Objects;

/** Accepted destination message or a stable, content-opaque rejection. */
public sealed interface MessageForwardResult {
    record Accepted(StoredMessage message, boolean duplicate) implements MessageForwardResult {
        public Accepted {
            Objects.requireNonNull(message, "message");
            if (!message.forwarded() || message.reply().isPresent() || !message.mentions().isEmpty()) {
                throw new IllegalArgumentException("forwarded message projection is invalid");
            }
        }
    }

    enum Rejected implements MessageForwardResult {
        NOT_AUTHORIZED,
        SOURCE_REVISION_CONFLICT,
        IDEMPOTENCY_CONFLICT,
        RATE_LIMITED
    }
}
