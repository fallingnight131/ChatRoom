package com.fallingnight.chat.gateway.transport;

/** Fixed-cardinality operational outcomes for authenticated message work. */
public interface MessagingEventSink {
    void accepted(boolean duplicate);

    void historyPage();

    void directoryPage();

    void reactionApplied(boolean changed, boolean duplicate);

    void editApplied(boolean changed, boolean duplicate);

    default void forwardAccepted(boolean duplicate) { }

    default void forwardRateLimited() { }

    default void searchPage() { }

    default void accountBlockApplied(boolean changed) { }

    void livePublished(int count);

    void liveSlowConsumerClosed(int count);

    default void liveSlowConsumerBacklog(long maximumBytesBeforeWritable) { }

    void denied();

    void conflict();

    void saturated();

    void failed();

    static MessagingEventSink noop() {
        return new MessagingEventSink() {
            @Override public void accepted(boolean duplicate) { }
            @Override public void historyPage() { }
            @Override public void directoryPage() { }
            @Override public void reactionApplied(boolean changed, boolean duplicate) { }
            @Override public void editApplied(boolean changed, boolean duplicate) { }
            @Override public void livePublished(int count) { }
            @Override public void liveSlowConsumerClosed(int count) { }
            @Override public void denied() { }
            @Override public void conflict() { }
            @Override public void saturated() { }
            @Override public void failed() { }
        };
    }
}
