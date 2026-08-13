package com.fallingnight.chat.gateway.compatibility.v1;

public interface V1ProfileImageMutationEventSink {
    enum Outcome { CHANGED_ROUTED, CHANGED_NO_LOCAL_RECIPIENT, UNCHANGED,
        INVALID_IMAGE, ACCOUNT_UNAVAILABLE, ROOM_ADMIN_REQUIRED, OBJECT_EVIDENCE_CONFLICT }
    void completed(Outcome outcome, int routedDeliveries, int byteSize, long executionNanos);
    void failed();
    void saturated();

    static V1ProfileImageMutationEventSink noop() {
        return new V1ProfileImageMutationEventSink() {
            @Override public void completed(Outcome outcome, int routed, int bytes, long elapsed) { }
            @Override public void failed() { }
            @Override public void saturated() { }
        };
    }
}
