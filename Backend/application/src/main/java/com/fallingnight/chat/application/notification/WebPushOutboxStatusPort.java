package com.fallingnight.chat.application.notification;

import java.time.Instant;

@FunctionalInterface
public interface WebPushOutboxStatusPort {
    WebPushOutboxStatus readStatus(Instant observedAt);
}
