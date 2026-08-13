package com.fallingnight.chat.application.messaging;

/** Fixed-cardinality result of one bounded relay pass. */
public record ConversationEventRelayReport(
        int claimed,
        int published,
        int deferred,
        int ownershipLost,
        int publisherFailures) {
    public ConversationEventRelayReport {
        if (claimed < 0 || published < 0 || deferred < 0 || ownershipLost < 0
                || publisherFailures < 0
                || published + deferred + ownershipLost != claimed
                || publisherFailures > claimed) {
            throw new IllegalArgumentException("invalid conversation event relay report");
        }
    }
}
