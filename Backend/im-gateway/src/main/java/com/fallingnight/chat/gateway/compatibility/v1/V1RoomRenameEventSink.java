package com.fallingnight.chat.gateway.compatibility.v1;

public interface V1RoomRenameEventSink {
    enum Outcome { CHANGED_ROUTED, CHANGED_NO_LOCAL_RECIPIENT,
        UNCHANGED, ADMIN_REQUIRED, INVALID_INPUT }
    void completed(Outcome outcome, int routedRecipients, long executionNanos);
    void failed();
    void saturated();

    static V1RoomRenameEventSink noop() {
        return new V1RoomRenameEventSink() {
            @Override public void completed(Outcome outcome, int routed, long elapsed) { }
            @Override public void failed() { }
            @Override public void saturated() { }
        };
    }
}
