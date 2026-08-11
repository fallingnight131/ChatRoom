package com.fallingnight.chat.gateway.transport;

/** Bounded admission outcome; contains no account or peer identity. */
public record AuthenticationAdmissionDecision(
        boolean allowed,
        AuthenticationLimitDimension dimension,
        long retryAfterMs) {
    public AuthenticationAdmissionDecision {
        if (dimension == null) {
            throw new NullPointerException("dimension");
        }
        if (allowed && dimension != AuthenticationLimitDimension.NONE) {
            throw new IllegalArgumentException("allowed decision must use NONE");
        }
        if (!allowed && dimension == AuthenticationLimitDimension.NONE) {
            throw new IllegalArgumentException("denied decision requires a dimension");
        }
        if (retryAfterMs < 0 || (!allowed && retryAfterMs == 0)) {
            throw new IllegalArgumentException("denied decision requires positive retryAfterMs");
        }
    }

    public static AuthenticationAdmissionDecision allow() {
        return new AuthenticationAdmissionDecision(
                true, AuthenticationLimitDimension.NONE, 0);
    }

    public static AuthenticationAdmissionDecision deny(
            AuthenticationLimitDimension dimension, long retryAfterMs) {
        return new AuthenticationAdmissionDecision(false, dimension, retryAfterMs);
    }
}
