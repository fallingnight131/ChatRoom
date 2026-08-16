package com.fallingnight.chat.gateway.operations;

import com.fallingnight.chat.application.notification.WebPushTerminalOutcome;
import com.fallingnight.chat.application.notification.WebPushWorkerEventSink;
import java.util.concurrent.atomic.LongAdder;

/** Lock-free fixed-cardinality Web Push worker telemetry without dynamic labels. */
public final class WebPushWorkerTelemetry implements WebPushWorkerEventSink {
    private final LongAdder recipientSaturated = new LongAdder();
    private final LongAdder delivered = new LongAdder();
    private final LongAdder invalidSubscriptions = new LongAdder();
    private final LongAdder transientFailures = new LongAdder();
    private final LongAdder authenticationFailures = new LongAdder();
    private final LongAdder ineligible = new LongAdder();
    private final LongAdder deferred = new LongAdder();
    private final LongAdder completedDelivered = new LongAdder();
    private final LongAdder completedExpired = new LongAdder();
    private final LongAdder completedIneligible = new LongAdder();
    private final LongAdder completedInvalidSubscription = new LongAdder();
    private final LongAdder fenceLost = new LongAdder();

    @Override public void recipientSaturated() { recipientSaturated.increment(); }
    @Override public void delivered() { delivered.increment(); }
    @Override public void invalidSubscription() { invalidSubscriptions.increment(); }
    @Override public void transientFailure() { transientFailures.increment(); }
    @Override public void authenticationFailure() { authenticationFailures.increment(); }
    @Override public void ineligible() { ineligible.increment(); }
    @Override public void deferred() { deferred.increment(); }
    @Override public void fenceLost() { fenceLost.increment(); }

    @Override
    public void completed(WebPushTerminalOutcome outcome) {
        switch (outcome) {
            case DELIVERED -> completedDelivered.increment();
            case EXPIRED -> completedExpired.increment();
            case INELIGIBLE -> completedIneligible.increment();
            case INVALID_SUBSCRIPTION -> completedInvalidSubscription.increment();
        }
    }

    public WebPushWorkerTelemetrySnapshot snapshot() {
        return new WebPushWorkerTelemetrySnapshot(
                recipientSaturated.sum(), delivered.sum(), invalidSubscriptions.sum(),
                transientFailures.sum(), authenticationFailures.sum(), ineligible.sum(),
                deferred.sum(), completedDelivered.sum(), completedExpired.sum(),
                completedIneligible.sum(), completedInvalidSubscription.sum(),
                fenceLost.sum());
    }
}
