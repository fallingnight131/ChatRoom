package com.fallingnight.chat.gateway.compatibility.v1;

public interface V1RoomMemberListEventSink {
    enum Outcome { LISTED, INVALID_INPUT, ACCESS_DENIED, ROOM_TOO_LARGE }
    void completed(Outcome outcome, int memberCount, long executionNanos);
    void failed();
    void saturated();
    static V1RoomMemberListEventSink noop() {
        return new V1RoomMemberListEventSink() {
            @Override public void completed(Outcome outcome, int count, long nanos) { }
            @Override public void failed() { }
            @Override public void saturated() { }
        };
    }
}
