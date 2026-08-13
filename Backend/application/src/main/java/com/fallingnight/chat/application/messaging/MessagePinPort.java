package com.fallingnight.chat.application.messaging;

/** Durable authority for one idempotent shared pin operation. */
public interface MessagePinPort {
    MessagePinResult set(MessagePinCommand command);
}
