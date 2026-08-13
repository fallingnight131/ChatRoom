package com.fallingnight.chat.gateway.compatibility.v1;

public interface V1RoomPasswordEventSink {
    enum Outcome { STATUS_AUTHORIZED, SET_CHANGED_ROUTED,
        SET_CHANGED_NO_LOCAL_RECIPIENT, SET_UNCHANGED,
        ADMIN_REQUIRED, INVALID_INPUT }
    void completed(Outcome outcome, int routedRecipients, long executionNanos);
    void failed();
    void saturated();

    static V1RoomPasswordEventSink noop() {
        return new V1RoomPasswordEventSink() {
            @Override public void completed(Outcome outcome, int routed, long elapsed) { }
            @Override public void failed() { }
            @Override public void saturated() { }
        };
    }
}
