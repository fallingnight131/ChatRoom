package com.fallingnight.chat.gateway.transport;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fallingnight.chat.protocol.v2.Envelope;
import com.fallingnight.chat.protocol.v2.MessageKind;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.http.websocketx.BinaryWebSocketFrame;
import org.junit.jupiter.api.Test;

class V2EnvelopeEncoderTest {
    @Test
    void emitsExactlyOneBinaryWebSocketMessage() throws Exception {
        EmbeddedChannel channel = new EmbeddedChannel(new V2EnvelopeEncoder());
        try {
            Envelope expected = Envelope.newBuilder()
                    .setProtocolVersion(2)
                    .setKind(MessageKind.MESSAGE_KIND_RESPONSE)
                    .setMessageType(2)
                    .setRequestId("request-1")
                    .setSentAtEpochMs(1_700_000_000_000L)
                    .build();

            channel.writeOutbound(expected);
            BinaryWebSocketFrame frame = channel.readOutbound();
            try {
                assertTrueBinaryFinalFrame(frame);
                assertEquals(expected, Envelope.parseFrom(frame.content().nioBuffer()));
            } finally {
                frame.release();
            }
        } finally {
            channel.finishAndReleaseAll();
        }
    }

    private static void assertTrueBinaryFinalFrame(BinaryWebSocketFrame frame) {
        assertTrue(frame.content().isReadable());
        assertTrue(frame.isFinalFragment());
    }
}
