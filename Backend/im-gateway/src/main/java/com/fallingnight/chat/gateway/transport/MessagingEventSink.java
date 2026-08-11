package com.fallingnight.chat.gateway.transport;

/** Fixed-cardinality operational outcomes for authenticated message work. */
public interface MessagingEventSink {
    void accepted(boolean duplicate);

    void historyPage();

    void denied();

    void conflict();

    void saturated();

    void failed();

    static MessagingEventSink noop() {
        return new MessagingEventSink() {
            @Override public void accepted(boolean duplicate) { }
            @Override public void historyPage() { }
            @Override public void denied() { }
            @Override public void conflict() { }
            @Override public void saturated() { }
            @Override public void failed() { }
        };
    }
}
