package com.fallingnight.chat.gateway.transport;

/** Fixed-cardinality outcomes for the detached Web Push HTTP credential handler. */
public interface WebPushHttpCredentialEventSink {
    void issued();
    void denied();
    void saturated();
    void failed();

    static WebPushHttpCredentialEventSink noop() {
        return new WebPushHttpCredentialEventSink() {
            @Override public void issued() { }
            @Override public void denied() { }
            @Override public void saturated() { }
            @Override public void failed() { }
        };
    }
}
