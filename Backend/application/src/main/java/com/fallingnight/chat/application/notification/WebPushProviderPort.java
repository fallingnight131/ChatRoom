package com.fallingnight.chat.application.notification;

/** Synchronous provider attempt invoked only by a bounded worker pool. */
public interface WebPushProviderPort {
    WebPushProviderResult deliver(WebPushProviderCommand command);
}
