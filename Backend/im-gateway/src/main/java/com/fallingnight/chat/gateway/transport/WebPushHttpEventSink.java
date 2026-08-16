package com.fallingnight.chat.gateway.transport;

@FunctionalInterface
public interface WebPushHttpEventSink {
    WebPushHttpEventSink NOOP = outcome -> { };
    void record(WebPushHttpOutcome outcome);
}
