package com.fallingnight.chat.application.attachment;

import java.util.Objects;
import java.util.UUID;

/** Server-authenticated account/device identity for attachment operations. */
public record AttachmentActor(UUID accountId, UUID deviceId) {
    public AttachmentActor {
        Objects.requireNonNull(accountId, "accountId");
        Objects.requireNonNull(deviceId, "deviceId");
    }
}
