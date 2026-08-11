package com.fallingnight.chat.gateway.transport;

import java.util.Objects;

/** Canonical non-hostname client address or a fixed rejection decision. */
public record PeerResolution(
        boolean accepted,
        String clientAddress,
        PeerResolutionDecision decision) {
    public PeerResolution {
        Objects.requireNonNull(clientAddress, "clientAddress");
        Objects.requireNonNull(decision, "decision");
        if (accepted != !clientAddress.isEmpty()) {
            throw new IllegalArgumentException(
                    "accepted peer resolutions require exactly one client address");
        }
    }

    static PeerResolution accepted(String address, PeerResolutionDecision decision) {
        return new PeerResolution(true, address, decision);
    }

    static PeerResolution rejected(PeerResolutionDecision decision) {
        return new PeerResolution(false, "", decision);
    }
}
