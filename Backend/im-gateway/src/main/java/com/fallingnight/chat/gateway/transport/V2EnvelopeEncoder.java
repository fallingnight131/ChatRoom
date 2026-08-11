package com.fallingnight.chat.gateway.transport;

import com.fallingnight.chat.protocol.v2.Envelope;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.MessageToMessageEncoder;
import io.netty.handler.codec.http.websocketx.BinaryWebSocketFrame;
import java.util.List;

/** Encodes one validated V2 envelope as one binary WebSocket message. */
public final class V2EnvelopeEncoder extends MessageToMessageEncoder<Envelope> {
    @Override
    protected void encode(
            ChannelHandlerContext context, Envelope envelope, List<Object> output) {
        byte[] bytes = envelope.toByteArray();
        if (bytes.length > V2EnvelopeDecoder.MAX_WIRE_BYTES) {
            throw new V2FrameException(
                    V2FrameException.Reason.FRAME_TOO_LARGE,
                    "outbound V2 frame exceeds " + V2EnvelopeDecoder.MAX_WIRE_BYTES + " bytes");
        }
        output.add(new BinaryWebSocketFrame(Unpooled.wrappedBuffer(bytes)));
    }
}
