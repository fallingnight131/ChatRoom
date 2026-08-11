package com.fallingnight.chat.gateway.transport;

/** Fixed-cardinality operational outcomes for authenticated message work. */
public interface MessagingEventSink {
    void accepted(boolean duplicate);

    void historyPage();

    void directoryPage();

    void livePublished(int count);

    void liveSlowConsumerClosed(int count);

    void denied();

    void conflict();

    void saturated();

    void failed();

    static MessagingEventSink noop() {
        return new MessagingEventSink() {
            @Override public void accepted(boolean duplicate) { }
            @Override public void historyPage() { }
            @Override public void directoryPage() { }
            @Override public void livePublished(int count) { }
            @Override public void liveSlowConsumerClosed(int count) { }
            @Override public void denied() { }
            @Override public void conflict() { }
            @Override public void saturated() { }
            @Override public void failed() { }
        };
    }
}
