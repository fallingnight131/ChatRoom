package com.fallingnight.chat.gateway.compatibility.v1;

import com.fallingnight.chat.gateway.transport.AuthenticationLimitDimension;

public interface V1RoomJoinEventSink {
    enum Outcome {
        FIRST_ROUTED, FIRST_NO_LOCAL_RECIPIENT, DUPLICATE, INVALID_INPUT, NOT_FOUND,
        PASSWORD_REQUIRED, INVALID_PASSWORD, ROOM_FULL, DENIED, ACCESS_CHANGED
    }
    void completed(Outcome outcome, int routedRecipients, long executionNanos);
    void admissionDenied(AuthenticationLimitDimension dimension);
    void failed();
    void saturated();
    static V1RoomJoinEventSink noop() {
        return new V1RoomJoinEventSink() {
            @Override public void completed(Outcome outcome, int routed, long nanos) { }
            @Override public void admissionDenied(AuthenticationLimitDimension dimension) { }
            @Override public void failed() { }
            @Override public void saturated() { }
        };
    }
}
