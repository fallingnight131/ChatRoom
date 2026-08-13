package com.fallingnight.chat.gateway.compatibility.v1;

public interface V1ProfileImageReadEventSink {
    enum Outcome { ACCOUNT_FOUND, ROOM_FOUND, MISSING, ACCESS_DENIED }
    void completed(Outcome outcome, int byteSize, long executionNanos);
    void failed();
    void saturated();

    static V1ProfileImageReadEventSink noop() {
        return new V1ProfileImageReadEventSink() {
            @Override public void completed(Outcome outcome, int byteSize, long elapsed) { }
            @Override public void failed() { }
            @Override public void saturated() { }
        };
    }
}
