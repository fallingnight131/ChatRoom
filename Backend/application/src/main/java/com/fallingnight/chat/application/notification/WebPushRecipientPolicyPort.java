package com.fallingnight.chat.application.notification;

/** Reauthorizes a committed message against current membership/account/privacy truth. */
public interface WebPushRecipientPolicyPort {
    WebPushRecipientResolution resolve(WebPushNotificationIntent intent, int limit);
}
