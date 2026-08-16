package com.fallingnight.chat.application.notification;

/** Explicit feature policy; Web Push remains disabled until a bootstrap adapter opts in. */
public record WebPushDeliveryPolicy(boolean enabled) {
    public static final WebPushDeliveryPolicy DEFAULT = new WebPushDeliveryPolicy(false);
}
