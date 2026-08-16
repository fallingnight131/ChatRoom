package com.fallingnight.chat.application.notification;

/** Fixed-cardinality worker observations; implementations must not add identity labels. */
public interface WebPushWorkerEventSink {
    default void recipientSaturated() { }
    default void delivered() { }
    default void invalidSubscription() { }
    default void transientFailure() { }
    default void authenticationFailure() { }
    default void ineligible() { }
    default void deferred() { }
    default void completed(WebPushTerminalOutcome outcome) { }
    default void fenceLost() { }
}
