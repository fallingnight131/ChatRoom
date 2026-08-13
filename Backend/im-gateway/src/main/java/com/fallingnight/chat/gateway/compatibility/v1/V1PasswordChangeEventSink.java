package com.fallingnight.chat.gateway.compatibility.v1;

public interface V1PasswordChangeEventSink {
    enum Outcome { CHANGED, ALREADY_CURRENT, CURRENT_PASSWORD_INCORRECT,
        SESSION_INVALID, CONCURRENT_CHANGE, INVALID_INPUT, ADMISSION_DENIED }
    void completed(Outcome outcome, long executionNanos);
    void failed();
    void saturated();

    static V1PasswordChangeEventSink noop() {
        return new V1PasswordChangeEventSink() {
            @Override public void completed(Outcome outcome, long elapsed) { }
            @Override public void failed() { }
            @Override public void saturated() { }
        };
    }
}
