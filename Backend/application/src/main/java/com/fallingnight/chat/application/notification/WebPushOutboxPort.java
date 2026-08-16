package com.fallingnight.chat.application.notification;

/** Transaction participant for stable, idempotent notification intent persistence. */
public interface WebPushOutboxPort {
    EnqueueResult enqueue(WebPushNotificationIntent intent);

    enum EnqueueResult {
        INSERTED,
        ALREADY_EXISTS
    }
}
