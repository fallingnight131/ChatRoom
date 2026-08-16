package com.fallingnight.chat.application.notification;

import java.util.Objects;
import java.util.UUID;

/** Current authorized recipient projection with recipient-specific mention classification. */
public record WebPushRecipient(UUID accountId, boolean mentioned) {
    public WebPushRecipient {
        Objects.requireNonNull(accountId, "accountId");
    }
}
