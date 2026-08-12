package com.fallingnight.chat.gateway.transport;

/** Fixed-cardinality outcomes for the inactive authenticated attachment path. */
public interface AttachmentEventSink {
    void registered(boolean duplicate);

    void uploadAuthorized();

    void ready(boolean duplicate);

    void denied();

    void conflict();

    void invalid();

    void saturated();

    void failed();

    static AttachmentEventSink noop() {
        return new AttachmentEventSink() {
            @Override public void registered(boolean duplicate) { }
            @Override public void uploadAuthorized() { }
            @Override public void ready(boolean duplicate) { }
            @Override public void denied() { }
            @Override public void conflict() { }
            @Override public void invalid() { }
            @Override public void saturated() { }
            @Override public void failed() { }
        };
    }
}
