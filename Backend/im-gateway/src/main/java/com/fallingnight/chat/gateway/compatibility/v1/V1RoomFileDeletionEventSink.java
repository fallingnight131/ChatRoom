package com.fallingnight.chat.gateway.compatibility.v1;

public interface V1RoomFileDeletionEventSink {
    enum Outcome { FIRST_ROUTED, FIRST_NO_LOCAL_RECIPIENT, DUPLICATE,
        ADMIN_REQUIRED, INVALID_INPUT, OPERATION_CONFLICT }
    void completed(Outcome outcome, int routedRecipients, long executionNanos);
    void failed();
    void saturated();

    static V1RoomFileDeletionEventSink noop() {
        return new V1RoomFileDeletionEventSink() {
            @Override public void completed(Outcome outcome, int routed, long elapsed) { }
            @Override public void failed() { }
            @Override public void saturated() { }
        };
    }
}
