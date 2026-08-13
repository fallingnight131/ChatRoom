package com.fallingnight.chat.application.messaging;

/** Publishes one committed event; implementations load authoritative content by claim identity. */
@FunctionalInterface
public interface ConversationEventPublicationPort {
    ConversationEventPublicationOutcome publish(ConversationEventOutboxClaim claim);
}
