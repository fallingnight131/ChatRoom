package com.fallingnight.chat.gateway.compatibility.v1;

public interface V1RoomMessageDeletionEventSink {
    enum Outcome { FIRST_ROUTED, FIRST_NO_LOCAL_RECIPIENT, DUPLICATE,
        ADMIN_REQUIRED, INVALID_INPUT, OPERATION_CONFLICT, SCOPE_TOO_LARGE }
    void completed(Outcome outcome, int routedRecipients, long executionNanos);
    void failed();
    void saturated();

    static V1RoomMessageDeletionEventSink noop() {
        return new V1RoomMessageDeletionEventSink() {
            @Override public void completed(Outcome outcome, int routed, long elapsed) { }
            @Override public void failed() { }
            @Override public void saturated() { }
        };
    }
}
