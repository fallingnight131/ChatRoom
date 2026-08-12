package com.fallingnight.chat.gateway.compatibility.v1;

public interface V1DirectMessageEventSink {
    enum Outcome { FIRST_ROUTE_SCHEDULED, FIRST_NO_LOCAL_ROUTE, DUPLICATE,
        ACCESS_DENIED, INVALID_MESSAGE, INVALID_CLIENT_MESSAGE_ID, CONFLICT }
    void completed(Outcome outcome, long executionNanos);
    void failed();
    void saturated();
    static V1DirectMessageEventSink noop() {
        return new V1DirectMessageEventSink() {
            @Override public void completed(Outcome outcome, long nanos) { }
            @Override public void failed() { }
            @Override public void saturated() { }
        };
    }
}
