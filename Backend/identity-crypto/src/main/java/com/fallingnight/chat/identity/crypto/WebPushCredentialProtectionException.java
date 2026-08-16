package com.fallingnight.chat.identity.crypto;

/** Fail-closed Web Push credential protection or authentication failure. */
public final class WebPushCredentialProtectionException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    WebPushCredentialProtectionException(String message, Throwable cause) {
        super(message, cause);
    }
}
