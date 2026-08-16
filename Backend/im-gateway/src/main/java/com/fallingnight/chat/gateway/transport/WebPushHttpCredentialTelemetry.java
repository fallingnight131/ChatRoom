package com.fallingnight.chat.gateway.transport;

import java.util.concurrent.atomic.LongAdder;

/** Fixed-name, identity-free Web Push HTTP credential counters. */
public final class WebPushHttpCredentialTelemetry implements WebPushHttpCredentialEventSink {
    private final LongAdder issued = new LongAdder();
    private final LongAdder denied = new LongAdder();
    private final LongAdder saturated = new LongAdder();
    private final LongAdder failed = new LongAdder();

    @Override public void issued() { issued.increment(); }
    @Override public void denied() { denied.increment(); }
    @Override public void saturated() { saturated.increment(); }
    @Override public void failed() { failed.increment(); }

    public WebPushHttpCredentialTelemetrySnapshot snapshot() {
        return new WebPushHttpCredentialTelemetrySnapshot(
                issued.sum(), denied.sum(), saturated.sum(), failed.sum());
    }
}
