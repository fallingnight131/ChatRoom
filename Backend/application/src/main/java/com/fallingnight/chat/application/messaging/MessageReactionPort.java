package com.fallingnight.chat.application.messaging;

/** Durable authorization, idempotency, state, and ordering boundary for reactions. */
public interface MessageReactionPort {
    MessageReactionResult set(MessageReactionCommand command);
}
