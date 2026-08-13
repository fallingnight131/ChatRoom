package com.fallingnight.chat.application.messaging;

/** Fixed-cardinality outcome from a live-event publication dependency. */
public enum ConversationEventPublicationOutcome {
    PUBLISHED(null),
    DEPENDENCY_UNAVAILABLE("DEPENDENCY_UNAVAILABLE"),
    DEPENDENCY_REJECTED("DEPENDENCY_REJECTED");

    private final String failureCode;

    ConversationEventPublicationOutcome(String failureCode) {
        this.failureCode = failureCode;
    }

    public boolean published() {
        return this == PUBLISHED;
    }

    public String failureCode() {
        if (published()) {
            throw new IllegalStateException("published outcome has no failure code");
        }
        return failureCode;
    }
}
