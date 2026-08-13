package com.fallingnight.chat.gateway.transport;

import java.util.concurrent.atomic.LongAdder;

/** Thread-safe device security counters without user/device/session labels. */
public final class DeviceManagementTelemetry implements DeviceManagementEventSink {
    private final LongAdder listed = new LongAdder();
    private final LongAdder revoked = new LongAdder();
    private final LongAdder duplicate = new LongAdder();
    private final LongAdder disconnected = new LongAdder();
    private final LongAdder denied = new LongAdder();
    private final LongAdder invalid = new LongAdder();
    private final LongAdder saturated = new LongAdder();
    private final LongAdder failed = new LongAdder();
    @Override public void listed() { listed.increment(); }
    @Override public void revoked(boolean changed) {
        if (changed) revoked.increment(); else duplicate.increment();
    }
    @Override public void disconnected(int connections) { disconnected.add(connections); }
    @Override public void denied() { denied.increment(); }
    @Override public void invalid() { invalid.increment(); }
    @Override public void saturated() { saturated.increment(); }
    @Override public void failed() { failed.increment(); }
    public DeviceManagementTelemetrySnapshot snapshot() {
        return new DeviceManagementTelemetrySnapshot(listed.sum(), revoked.sum(), duplicate.sum(),
                disconnected.sum(), denied.sum(), invalid.sum(), saturated.sum(), failed.sum());
    }
}
