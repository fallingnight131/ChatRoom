package com.fallingnight.chat.gateway.compatibility.v1;

public interface V1UsernameChangeEventSink {
    enum Outcome { CHANGED_ROUTED, CHANGED_NO_LOCAL_RECIPIENT, UNCHANGED,
        INVALID_INPUT, SAME_AS_CURRENT, USERNAME_TAKEN, COOLDOWN, ACCOUNT_UNAVAILABLE }
    void completed(Outcome outcome, int routedDeliveries, long executionNanos);
    void failed();
    void saturated();

    static V1UsernameChangeEventSink noop() {
        return new V1UsernameChangeEventSink() {
            @Override public void completed(Outcome outcome, int routed, long elapsed) { }
            @Override public void failed() { }
            @Override public void saturated() { }
        };
    }
}
