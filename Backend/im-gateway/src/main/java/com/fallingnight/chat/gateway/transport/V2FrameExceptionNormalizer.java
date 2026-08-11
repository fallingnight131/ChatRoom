package com.fallingnight.chat.gateway.transport;

import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.handler.codec.DecoderException;
import io.netty.handler.codec.TooLongFrameException;

/** Normalizes Netty decoder failures into gateway-owned protocol categories. */
final class V2FrameExceptionNormalizer extends ChannelInboundHandlerAdapter {
    @Override
    public void exceptionCaught(ChannelHandlerContext context, Throwable cause) {
        Throwable failure = unwrapDecoder(cause);
        if (failure instanceof V2FrameException) {
            context.fireExceptionCaught(failure);
            return;
        }
        if (failure instanceof TooLongFrameException) {
            context.fireExceptionCaught(new V2FrameException(
                    V2FrameException.Reason.FRAME_TOO_LARGE,
                    "V2 frame exceeds " + V2EnvelopeDecoder.MAX_WIRE_BYTES + " bytes",
                    failure));
            return;
        }
        context.fireExceptionCaught(cause);
    }

    private static Throwable unwrapDecoder(Throwable cause) {
        if (cause instanceof DecoderException && cause.getCause() != null) {
            return cause.getCause();
        }
        return cause;
    }
}
