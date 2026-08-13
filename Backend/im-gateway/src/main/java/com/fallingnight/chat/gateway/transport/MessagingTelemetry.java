package com.fallingnight.chat.gateway.transport;

import java.util.concurrent.atomic.LongAdder;

/** Thread-safe messaging counters with no account, peer, or conversation labels. */
public final class MessagingTelemetry implements MessagingEventSink {
    private final LongAdder accepted = new LongAdder();
    private final LongAdder duplicates = new LongAdder();
    private final LongAdder historyPages = new LongAdder();
    private final LongAdder directoryPages = new LongAdder();
    private final LongAdder reactionChanged = new LongAdder();
    private final LongAdder reactionNoOp = new LongAdder();
    private final LongAdder reactionDuplicates = new LongAdder();
    private final LongAdder editChanged = new LongAdder();
    private final LongAdder editNoOp = new LongAdder();
    private final LongAdder editDuplicates = new LongAdder();
    private final LongAdder forwardAccepted = new LongAdder();
    private final LongAdder forwardDuplicates = new LongAdder();
    private final LongAdder forwardRateLimited = new LongAdder();
    private final LongAdder livePublished = new LongAdder();
    private final LongAdder liveSlowConsumerClosed = new LongAdder();
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
    @Override public void directoryPage() { directoryPages.increment(); }
    @Override public void reactionApplied(boolean changed, boolean duplicate) {
        if (duplicate) reactionDuplicates.increment();
        else if (changed) reactionChanged.increment();
        else reactionNoOp.increment();
    }
    @Override public void editApplied(boolean changed, boolean duplicate) {
        if (duplicate) editDuplicates.increment();
        else if (changed) editChanged.increment();
        else editNoOp.increment();
    }
    @Override public void forwardAccepted(boolean duplicate) {
        if (duplicate) forwardDuplicates.increment();
        else forwardAccepted.increment();
    }
    @Override public void forwardRateLimited() { forwardRateLimited.increment(); }
    @Override public void livePublished(int count) { livePublished.add(count); }
    @Override public void liveSlowConsumerClosed(int count) { liveSlowConsumerClosed.add(count); }
    @Override public void denied() { denied.increment(); }
    @Override public void conflict() { conflicts.increment(); }
    @Override public void saturated() { saturated.increment(); }
    @Override public void failed() { failed.increment(); }

    public MessagingTelemetrySnapshot snapshot() {
        return new MessagingTelemetrySnapshot(
                accepted.sum(), duplicates.sum(), historyPages.sum(), directoryPages.sum(),
                reactionChanged.sum(), reactionNoOp.sum(), reactionDuplicates.sum(),
                editChanged.sum(), editNoOp.sum(), editDuplicates.sum(),
                forwardAccepted.sum(), forwardDuplicates.sum(), forwardRateLimited.sum(),
                livePublished.sum(), liveSlowConsumerClosed.sum(), denied.sum(),
                conflicts.sum(), saturated.sum(), failed.sum());
    }
}
