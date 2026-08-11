package com.fallingnight.chat.gateway.transport;

/** Fixed-label result of resolving a client peer at the trusted proxy boundary. */
public enum PeerResolutionDecision {
    DIRECT,
    DIRECT_FORWARDING_IGNORED,
    TRUSTED_FORWARDING,
    REJECTED_MISSING_DIRECT_PEER,
    REJECTED_MISSING_FORWARDING,
    REJECTED_INVALID_FORWARDING
}
