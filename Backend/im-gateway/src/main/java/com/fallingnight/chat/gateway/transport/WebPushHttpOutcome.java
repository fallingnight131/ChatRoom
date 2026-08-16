package com.fallingnight.chat.gateway.transport;

/** Fixed-cardinality HTTP outcome; values contain no request or identity data. */
public enum WebPushHttpOutcome {
    DISABLED, BAD_REQUEST, ORIGIN_REJECTED, METHOD_REJECTED, MEDIA_REJECTED,
    BODY_REJECTED, INVALID_SESSION, INVALID_CSRF, REPLACED, DELETED, UNCHANGED,
    ACCOUNT_UNAVAILABLE, LIMIT_REACHED, RATE_LIMITED, WORKER_REJECTED, FAILURE
}
