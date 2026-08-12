package com.fallingnight.chat.gateway.transport;

import java.util.concurrent.atomic.LongAdder;

/** Thread-safe attachment outcomes without identity, object, or URL labels. */
public final class AttachmentTelemetry implements AttachmentEventSink {
    private final LongAdder registered = new LongAdder();
    private final LongAdder registrationDuplicates = new LongAdder();
    private final LongAdder uploadAuthorizations = new LongAdder();
    private final LongAdder ready = new LongAdder();
    private final LongAdder readyDuplicates = new LongAdder();
    private final LongAdder denied = new LongAdder();
    private final LongAdder conflicts = new LongAdder();
    private final LongAdder invalid = new LongAdder();
    private final LongAdder saturated = new LongAdder();
    private final LongAdder failed = new LongAdder();

    @Override
    public void registered(boolean duplicate) {
        (duplicate ? registrationDuplicates : registered).increment();
    }

    @Override public void uploadAuthorized() { uploadAuthorizations.increment(); }

    @Override
    public void ready(boolean duplicate) {
        (duplicate ? readyDuplicates : ready).increment();
    }

    @Override public void denied() { denied.increment(); }
    @Override public void conflict() { conflicts.increment(); }
    @Override public void invalid() { invalid.increment(); }
    @Override public void saturated() { saturated.increment(); }
    @Override public void failed() { failed.increment(); }

    public AttachmentTelemetrySnapshot snapshot() {
        return new AttachmentTelemetrySnapshot(
                registered.sum(), registrationDuplicates.sum(), uploadAuthorizations.sum(),
                ready.sum(), readyDuplicates.sum(), denied.sum(), conflicts.sum(), invalid.sum(),
                saturated.sum(), failed.sum());
    }
}
