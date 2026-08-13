package com.fallingnight.chat.application.messaging;

/** PostgreSQL-backed authority for one idempotent message edit operation. */
public interface MessageEditPort {
    MessageEditResult edit(MessageEditCommand command);
}
