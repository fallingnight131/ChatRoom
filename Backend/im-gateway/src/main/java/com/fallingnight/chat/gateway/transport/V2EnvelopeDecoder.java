package com.fallingnight.chat.gateway.transport;

import com.fallingnight.chat.protocol.v2.Envelope;
import com.fallingnight.chat.protocol.v2.EnvelopePolicy;
import com.google.protobuf.InvalidProtocolBufferException;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.MessageToMessageDecoder;
import io.netty.handler.codec.http.websocketx.BinaryWebSocketFrame;
import io.netty.handler.codec.http.websocketx.ContinuationWebSocketFrame;
import io.netty.handler.codec.http.websocketx.TextWebSocketFrame;
import io.netty.handler.codec.http.websocketx.WebSocketFrame;
import java.util.List;

/** Converts one complete V2 binary WebSocket frame into a validated envelope. */
public final class V2EnvelopeDecoder extends MessageToMessageDecoder<WebSocketFrame> {
    // Three bounded identifiers plus protobuf tags and future envelope metadata.
    public static final int MAX_WIRE_BYTES = EnvelopePolicy.MAX_PAYLOAD_BYTES + 1024;

    @Override
    public boolean acceptInboundMessage(Object message) {
        return message instanceof BinaryWebSocketFrame
                || message instanceof TextWebSocketFrame
                || message instanceof ContinuationWebSocketFrame;
    }

    @Override
    protected void decode(
            ChannelHandlerContext context, WebSocketFrame frame, List<Object> output) {
        if (!(frame instanceof BinaryWebSocketFrame)) {
            throw new V2FrameException(
                    V2FrameException.Reason.UNSUPPORTED_FRAME_TYPE,
                    "V2 accepts binary WebSocket messages only");
        }
        if (frame.content().readableBytes() > MAX_WIRE_BYTES) {
            throw new V2FrameException(
                    V2FrameException.Reason.FRAME_TOO_LARGE,
                    "V2 frame exceeds " + MAX_WIRE_BYTES + " bytes");
        }

        final Envelope envelope;
        try {
            envelope = Envelope.parseFrom(frame.content().nioBuffer());
        } catch (InvalidProtocolBufferException exception) {
            throw new V2FrameException(
                    V2FrameException.Reason.MALFORMED_PROTOBUF,
                    "V2 frame is not a valid protobuf envelope",
                    exception);
        }

        try {
            EnvelopePolicy.requireValid(envelope);
        } catch (IllegalArgumentException exception) {
            throw new V2FrameException(
                    V2FrameException.Reason.INVALID_ENVELOPE,
                    exception.getMessage(),
                    exception);
        }
        output.add(envelope);
    }
}
