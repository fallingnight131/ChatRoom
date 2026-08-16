package com.fallingnight.chat.application.notification;

import java.util.Optional;
import java.util.UUID;

/** Reauthorizes a committed message against current membership/account/privacy truth. */
public interface WebPushRecipientPolicyPort {
    WebPushRecipientResolution resolve(WebPushNotificationIntent intent, int limit);

    Optional<WebPushRecipient> reauthorize(
            WebPushNotificationIntent intent, UUID recipientAccountId);
}
