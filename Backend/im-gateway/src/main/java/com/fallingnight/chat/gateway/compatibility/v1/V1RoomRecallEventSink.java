package com.fallingnight.chat.gateway.compatibility.v1;

public interface V1RoomRecallEventSink {
    enum Outcome { FIRST_ROUTED, FIRST_NO_LOCAL_RECIPIENT, DUPLICATE,
        ACCESS_DENIED, RECALL_REJECTED, INVALID_REQUEST }
    void completed(Outcome outcome, int routedRecipients, long executionNanos);
    void failed();
    void saturated();
    static V1RoomRecallEventSink noop() {
        return new V1RoomRecallEventSink() {
            @Override public void completed(Outcome outcome, int recipients, long nanos) { }
            @Override public void failed() { }
            @Override public void saturated() { }
        };
    }
}
