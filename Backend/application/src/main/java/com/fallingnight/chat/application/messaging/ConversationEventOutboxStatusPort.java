package com.fallingnight.chat.application.messaging;

import java.time.Instant;

@FunctionalInterface
public interface ConversationEventOutboxStatusPort {
    ConversationEventOutboxStatus readStatus(Instant observedAt);
}
