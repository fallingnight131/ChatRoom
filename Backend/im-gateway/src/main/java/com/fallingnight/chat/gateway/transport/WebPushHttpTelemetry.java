package com.fallingnight.chat.gateway.transport;

import java.util.Objects;
import java.util.concurrent.atomic.LongAdder;

/** Fixed-cardinality counters without tokens, origins, accounts, or installations. */
public final class WebPushHttpTelemetry implements WebPushHttpEventSink {
    private final LongAdder[] counters;

    public WebPushHttpTelemetry() {
        counters = new LongAdder[WebPushHttpOutcome.values().length];
        for (int index = 0; index < counters.length; index++) counters[index] = new LongAdder();
    }

    @Override public void record(WebPushHttpOutcome outcome) {
        counters[Objects.requireNonNull(outcome, "outcome").ordinal()].increment();
    }

    public long count(WebPushHttpOutcome outcome) {
        return counters[Objects.requireNonNull(outcome, "outcome").ordinal()].sum();
    }
}
