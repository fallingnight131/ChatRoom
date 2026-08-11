package com.fallingnight.chat.gateway.transport;

import java.util.concurrent.atomic.LongAdder;

/** Thread-safe messaging counters with no account, peer, or conversation labels. */
public final class MessagingTelemetry implements MessagingEventSink {
    private final LongAdder accepted = new LongAdder();
    private final LongAdder duplicates = new LongAdder();
    private final LongAdder historyPages = new LongAdder();
    private final LongAdder denied = new LongAdder();
    private final LongAdder conflicts = new LongAdder();
    private final LongAdder saturated = new LongAdder();
    private final LongAdder failed = new LongAdder();

    @Override
    public void accepted(boolean duplicate) {
        if (duplicate) {
            duplicates.increment();
        } else {
            accepted.increment();
        }
    }

    @Override public void historyPage() { historyPages.increment(); }
    @Override public void denied() { denied.increment(); }
    @Override public void conflict() { conflicts.increment(); }
    @Override public void saturated() { saturated.increment(); }
    @Override public void failed() { failed.increment(); }

    public MessagingTelemetrySnapshot snapshot() {
        return new MessagingTelemetrySnapshot(
                accepted.sum(), duplicates.sum(), historyPages.sum(), denied.sum(),
                conflicts.sum(), saturated.sum(), failed.sum());
    }
}
