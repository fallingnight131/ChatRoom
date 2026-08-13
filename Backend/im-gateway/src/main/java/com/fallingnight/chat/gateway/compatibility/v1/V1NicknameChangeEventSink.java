package com.fallingnight.chat.gateway.compatibility.v1;

public interface V1NicknameChangeEventSink {
    enum Outcome { CHANGED_ROUTED, CHANGED_NO_LOCAL_RECIPIENT,
        UNCHANGED, INVALID_INPUT, ACCOUNT_UNAVAILABLE }
    void completed(Outcome outcome, int routedDeliveries, long executionNanos);
    void failed();
    void saturated();

    static V1NicknameChangeEventSink noop() {
        return new V1NicknameChangeEventSink() {
            @Override public void completed(Outcome outcome, int routed, long elapsed) { }
            @Override public void failed() { }
            @Override public void saturated() { }
        };
    }
}
