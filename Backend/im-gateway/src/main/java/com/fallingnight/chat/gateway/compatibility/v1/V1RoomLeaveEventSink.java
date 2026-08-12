package com.fallingnight.chat.gateway.compatibility.v1;

public interface V1RoomLeaveEventSink {
    enum Outcome {
        FIRST_ROUTED, FIRST_NO_LOCAL_RECIPIENT, FIRST_DISSOLVED, DUPLICATE,
        INVALID_INPUT, NOT_FOUND, NOT_MEMBER, DENIED
    }
    void completed(Outcome outcome, int routedRecipients,
            boolean ownershipRouteScheduled, long executionNanos);
    void failed();
    void saturated();
    static V1RoomLeaveEventSink noop() {
        return new V1RoomLeaveEventSink() {
            @Override public void completed(Outcome outcome, int routed,
                    boolean ownership, long nanos) { }
            @Override public void failed() { }
            @Override public void saturated() { }
        };
    }
}
