package com.fallingnight.chat.application.messaging;

/** Durable server-authoritative message-forwarding boundary. */
public interface MessageForwardPort {
    MessageForwardResult forward(MessageForwardCommand command);
}
