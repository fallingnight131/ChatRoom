package com.fallingnight.chat.gateway.compatibility.v1;

public interface V1RoomDissolutionEventSink {
    enum Outcome { DISSOLVED_ROUTED, DISSOLVED_NO_LOCAL_RECIPIENT,
        ALREADY_DISSOLVED, ADMIN_REQUIRED, NOT_FOUND, INVALID_INPUT }
    void completed(Outcome outcome, int routedRecipients, long executionNanos);
    void failed();
    void saturated();

    static V1RoomDissolutionEventSink noop() {
        return new V1RoomDissolutionEventSink() {
            @Override public void completed(Outcome outcome, int routed, long elapsed) { }
            @Override public void failed() { }
            @Override public void saturated() { }
        };
    }
}
