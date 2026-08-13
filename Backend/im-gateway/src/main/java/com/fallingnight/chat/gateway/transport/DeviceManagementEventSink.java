package com.fallingnight.chat.gateway.transport;

/** Fixed-cardinality outcomes; device/account identifiers are never metric labels. */
public interface DeviceManagementEventSink {
    void listed();
    void revoked(boolean changed);
    void disconnected(int connections);
    void denied();
    void invalid();
    void saturated();
    void failed();

    static DeviceManagementEventSink noop() {
        return new DeviceManagementEventSink() {
            @Override public void listed() { }
            @Override public void revoked(boolean changed) { }
            @Override public void disconnected(int connections) { }
            @Override public void denied() { }
            @Override public void invalid() { }
            @Override public void saturated() { }
            @Override public void failed() { }
        };
    }
}
