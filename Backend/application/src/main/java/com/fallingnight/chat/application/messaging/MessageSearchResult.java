package com.fallingnight.chat.application.messaging;

/** Authorized search page or a fixed authorization denial. */
public sealed interface MessageSearchResult {
    record Found(MessageSearchPage page) implements MessageSearchResult {}

    enum Rejected implements MessageSearchResult {
        NOT_AUTHORIZED
    }
}
