package com.fallingnight.chat.gateway.transport;

/** Non-identifying authentication admission dimensions safe for metrics. */
public enum AuthenticationLimitDimension {
    NONE,
    GATEWAY,
    DIRECT_PEER,
    ACCOUNT,
    DIRECT_PEER_CAPACITY,
    ACCOUNT_CAPACITY
}
