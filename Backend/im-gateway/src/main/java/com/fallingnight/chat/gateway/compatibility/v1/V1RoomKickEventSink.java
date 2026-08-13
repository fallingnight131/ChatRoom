package com.fallingnight.chat.gateway.compatibility.v1;

public interface V1RoomKickEventSink {
    enum Outcome { FIRST_ROUTED, FIRST_NO_LOCAL_RECIPIENT, DUPLICATE, REJECTED }
    void completed(Outcome outcome, int remainingMembersRouted,
            boolean targetRouted, long elapsedNanos);
    void failed();
    void saturated();
    static V1RoomKickEventSink noop() { return new V1RoomKickEventSink() {
        @Override public void completed(Outcome outcome, int members,
                boolean target, long elapsed) { }
        @Override public void failed() { }
        @Override public void saturated() { }
    }; }
}
