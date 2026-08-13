package com.fallingnight.chat.gateway.compatibility.v1;

public interface V1RegistrationEventSink {
    enum Outcome { CREATED, DUPLICATE, INVALID_INPUT, USERNAME_TAKEN,
        UNAVAILABLE, ADMISSION_DENIED }
    void completed(Outcome outcome, long executionNanos);
    void failed();
    void saturated();
    static V1RegistrationEventSink noop() {
        return new V1RegistrationEventSink() {
            @Override public void completed(Outcome outcome, long elapsed) { }
            @Override public void failed() { }
            @Override public void saturated() { }
        };
    }
}
