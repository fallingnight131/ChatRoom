package com.fallingnight.chat.gateway.transport;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fallingnight.chat.protocol.v2.Envelope;
import com.fallingnight.chat.protocol.v2.MessageKind;
import com.google.protobuf.ByteString;
import io.netty.buffer.Unpooled;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.http.websocketx.BinaryWebSocketFrame;
import io.netty.handler.codec.http.websocketx.CloseWebSocketFrame;
import io.netty.handler.codec.http.websocketx.ContinuationWebSocketFrame;
import io.netty.handler.codec.http.websocketx.PingWebSocketFrame;
import io.netty.handler.codec.http.websocketx.TextWebSocketFrame;
import io.netty.handler.codec.http.websocketx.WebSocketFrame;
import io.netty.handler.codec.http.websocketx.WebSocketCloseStatus;
import java.util.Arrays;
import org.junit.jupiter.api.Test;

class V2EnvelopeDecoderTest {
    @Test
    void decodesACompleteValidBinaryEnvelope() {
        EmbeddedChannel channel = channel();
        try {
            Envelope expected = validEnvelope();
            assertTrue(channel.writeInbound(binary(expected.toByteArray())));
            assertEquals(expected, channel.readInbound());
        } finally {
            channel.finishAndReleaseAll();
        }
    }

    @Test
    void aggregatesAFragmentedBinaryEnvelopeBeforeParsing() {
        EmbeddedChannel channel = channel();
        try {
            byte[] bytes = validEnvelope().toByteArray();
            int middle = bytes.length / 2;
            assertFalse(channel.writeInbound(new BinaryWebSocketFrame(
                    false, 0, Unpooled.wrappedBuffer(Arrays.copyOfRange(bytes, 0, middle)))));
            assertTrue(channel.writeInbound(new ContinuationWebSocketFrame(
                    true, 0, Unpooled.wrappedBuffer(Arrays.copyOfRange(bytes, middle, bytes.length)))));
            assertEquals(validEnvelope(), channel.readInbound());
        } finally {
            channel.finishAndReleaseAll();
        }
    }

    @Test
    void rejectsTextMalformedOversizedAndPolicyInvalidFrames() {
        assertFailure(new TextWebSocketFrame("{}"), WebSocketCloseStatus.PROTOCOL_ERROR);
        assertFailure(new ContinuationWebSocketFrame(
                true, 0, Unpooled.wrappedBuffer(new byte[] {1})),
                WebSocketCloseStatus.PROTOCOL_ERROR);
        assertFailure(binary(new byte[] {(byte) 0x80}),
                WebSocketCloseStatus.PROTOCOL_ERROR);
        assertFailure(binary(new byte[V2EnvelopeDecoder.MAX_WIRE_BYTES + 1]),
                WebSocketCloseStatus.MESSAGE_TOO_BIG);
        assertFailure(binary(validEnvelope().toBuilder().setProtocolVersion(1).build().toByteArray()),
                WebSocketCloseStatus.PROTOCOL_ERROR);
    }

    @Test
    void boundsMemoryWhileAggregatingFragmentedMessages() {
        EmbeddedChannel channel = channel();
        try {
            int firstLength = V2EnvelopeDecoder.MAX_WIRE_BYTES / 2;
            assertFalse(channel.writeInbound(new BinaryWebSocketFrame(
                    false, 0, Unpooled.wrappedBuffer(new byte[firstLength]))));
            channel.writeInbound(new ContinuationWebSocketFrame(
                    true,
                    0,
                    Unpooled.wrappedBuffer(new byte[
                            V2EnvelopeDecoder.MAX_WIRE_BYTES - firstLength + 1])));
            assertClose(channel, WebSocketCloseStatus.MESSAGE_TOO_BIG);
        } finally {
            channel.finishAndReleaseAll();
        }
    }

    @Test
    void leavesControlFramesForTheWebSocketControlHandler() {
        EmbeddedChannel channel = channel();
        try {
            PingWebSocketFrame ping = new PingWebSocketFrame(Unpooled.wrappedBuffer(new byte[] {1}));
            assertTrue(channel.writeInbound(ping));
            PingWebSocketFrame forwarded = assertInstanceOf(
                    PingWebSocketFrame.class, channel.readInbound());
            forwarded.release();
        } finally {
            channel.finishAndReleaseAll();
        }
    }

    private static void assertFailure(WebSocketFrame frame, WebSocketCloseStatus status) {
        EmbeddedChannel channel = channel();
        try {
            channel.writeInbound(frame);
            assertClose(channel, status);
        } finally {
            channel.finishAndReleaseAll();
        }
    }

    private static void assertClose(
            EmbeddedChannel channel, WebSocketCloseStatus expectedStatus) {
        CloseWebSocketFrame close = assertInstanceOf(
                CloseWebSocketFrame.class, channel.readOutbound());
        try {
            assertEquals(expectedStatus.code(), close.statusCode());
            String expectedReason = expectedStatus == WebSocketCloseStatus.MESSAGE_TOO_BIG
                    ? "V2 frame too large"
                    : "invalid V2 frame";
            assertEquals(expectedReason, close.reasonText());
        } finally {
            close.release();
        }
        assertFalse(channel.isActive());
    }

    private static EmbeddedChannel channel() {
        EmbeddedChannel channel = new EmbeddedChannel();
        V2FramePipeline.install(channel.pipeline());
        return channel;
    }

    private static BinaryWebSocketFrame binary(byte[] bytes) {
        return new BinaryWebSocketFrame(Unpooled.wrappedBuffer(bytes));
    }

    private static Envelope validEnvelope() {
        return Envelope.newBuilder()
                .setProtocolVersion(2)
                .setKind(MessageKind.MESSAGE_KIND_COMMAND)
                .setMessageType(100)
                .setRequestId("request-1")
                .setSessionId("session-1")
                .setClientMessageId("client-1")
                .setSentAtEpochMs(1_700_000_000_000L)
                .setPayload(ByteString.copyFromUtf8("payload"))
                .build();
    }
}
