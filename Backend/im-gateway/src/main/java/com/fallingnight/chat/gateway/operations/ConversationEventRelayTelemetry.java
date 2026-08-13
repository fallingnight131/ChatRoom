package com.fallingnight.chat.gateway.operations;

import com.fallingnight.chat.application.messaging.ConversationEventRelayReport;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;

/** Fixed-cardinality relay telemetry without event, conversation, or claim identities. */
public final class ConversationEventRelayTelemetry {
    private final LongAdder runs = new LongAdder();
    private final LongAdder runFailures = new LongAdder();
    private final LongAdder claimed = new LongAdder();
    private final LongAdder published = new LongAdder();
    private final LongAdder deferred = new LongAdder();
    private final LongAdder ownershipLost = new LongAdder();
    private final LongAdder publisherFailures = new LongAdder();
    private final AtomicInteger consecutiveFailures = new AtomicInteger();
    private final AtomicLong nextDelayMillis = new AtomicLong();

    public void completed(
            ConversationEventRelayReport report, int failures, Duration nextDelay) {
        Objects.requireNonNull(report, "report");
        runs.increment();
        claimed.add(report.claimed());
        published.add(report.published());
        deferred.add(report.deferred());
        ownershipLost.add(report.ownershipLost());
        publisherFailures.add(report.publisherFailures());
        scheduled(failures, nextDelay);
    }

    public void failed(int failures, Duration nextDelay) {
        runs.increment();
        runFailures.increment();
        scheduled(failures, nextDelay);
    }

    public ConversationEventRelayTelemetrySnapshot snapshot() {
        return new ConversationEventRelayTelemetrySnapshot(
                runs.sum(), runFailures.sum(), claimed.sum(), published.sum(), deferred.sum(),
                ownershipLost.sum(), publisherFailures.sum(), consecutiveFailures.get(),
                nextDelayMillis.get());
    }

    private void scheduled(int failures, Duration delay) {
        if (failures < 0) {
            throw new IllegalArgumentException("failures must not be negative");
        }
        Objects.requireNonNull(delay, "delay");
        consecutiveFailures.set(failures);
        nextDelayMillis.set(delay.toMillis());
    }
}
