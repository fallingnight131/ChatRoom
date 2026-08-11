package com.fallingnight.chat.gateway.transport;

import io.netty.handler.codec.CorruptedFrameException;
import java.io.Serial;

/** A client-visible protocol failure detected before application dispatch. */
public final class V2FrameException extends CorruptedFrameException {
    @Serial
    private static final long serialVersionUID = 1L;

    public enum Reason {
        UNSUPPORTED_FRAME_TYPE,
        FRAME_TOO_LARGE,
        MALFORMED_PROTOBUF,
        INVALID_ENVELOPE
    }

    private final Reason reason;

    public V2FrameException(Reason reason, String message) {
        super(message);
        this.reason = reason;
    }

    public V2FrameException(Reason reason, String message, Throwable cause) {
        super(message, cause);
        this.reason = reason;
    }

    public Reason reason() {
        return reason;
    }
}
