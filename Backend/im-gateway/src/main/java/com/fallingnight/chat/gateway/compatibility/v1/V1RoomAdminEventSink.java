package com.fallingnight.chat.gateway.compatibility.v1;

public interface V1RoomAdminEventSink {
    enum Outcome { CHANGED_ROUTED, CHANGED_NO_LOCAL_TARGET, UNCHANGED, REJECTED }
    void completed(Outcome outcome, long elapsedNanos);
    void failed();
    void saturated();
    static V1RoomAdminEventSink noop() { return new V1RoomAdminEventSink() {
        @Override public void completed(Outcome outcome, long elapsedNanos) { }
        @Override public void failed() { }
        @Override public void saturated() { }
    }; }
}
