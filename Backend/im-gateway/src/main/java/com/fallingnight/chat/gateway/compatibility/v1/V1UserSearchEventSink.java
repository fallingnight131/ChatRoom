package com.fallingnight.chat.gateway.compatibility.v1;

public interface V1UserSearchEventSink {
    enum Outcome { FOUND, INPUT_REJECTED }
    void completed(Outcome outcome, int resultCount, long executionNanos);
    void failed();
    void saturated();

    static V1UserSearchEventSink noop() {
        return new V1UserSearchEventSink() {
            @Override public void completed(Outcome outcome, int count, long nanos) { }
            @Override public void failed() { }
            @Override public void saturated() { }
        };
    }
}
