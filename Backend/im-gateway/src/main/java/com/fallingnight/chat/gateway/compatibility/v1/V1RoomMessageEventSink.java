package com.fallingnight.chat.gateway.compatibility.v1;
public interface V1RoomMessageEventSink {
    enum Outcome { FIRST_ROUTED, FIRST_NO_LOCAL_RECIPIENT, DUPLICATE, ACCESS_DENIED,
        INVALID_MESSAGE, INVALID_CLIENT_MESSAGE_ID, CONFLICT }
    void completed(Outcome outcome, int routedRecipients, long executionNanos);
    void failed();
    void saturated();
    static V1RoomMessageEventSink noop() {
        return new V1RoomMessageEventSink() {
            @Override public void completed(Outcome outcome, int recipients, long nanos) { }
            @Override public void failed() { }
            @Override public void saturated() { }
        };
    }
}
