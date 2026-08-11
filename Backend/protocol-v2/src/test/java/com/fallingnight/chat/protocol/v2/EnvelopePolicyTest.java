package com.fallingnight.chat.protocol.v2;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.protobuf.ByteString;
import java.util.HexFormat;
import org.junit.jupiter.api.Test;

class EnvelopePolicyTest {
    @Test
    void acceptsAndRoundTripsAValidCommand() throws Exception {
        Envelope original = validCommand().build();
        EnvelopePolicy.requireValid(original);

        Envelope decoded = Envelope.parseFrom(original.toByteArray());
        assertEquals(original, decoded);
        assertEquals(
                "08021001186422057265712d312a0973657373696f6e2d31"
                        + "3208636c69656e742d313880d095ffbc314203616263",
                HexFormat.of().formatHex(original.toByteArray()));
    }

    @Test
    void rejectsMissingRoutingAndOversizedUtf8Identity() {
        Envelope invalid = validCommand()
                .setProtocolVersion(1)
                .setKind(MessageKind.MESSAGE_KIND_UNSPECIFIED)
                .setMessageType(0)
                .clearRequestId()
                .setClientMessageId("聊".repeat(43))
                .build();

        var violations = EnvelopePolicy.violations(invalid);
        assertTrue(violations.contains("unsupported protocolVersion"));
        assertTrue(violations.contains("message kind is required"));
        assertTrue(violations.contains("messageType is required"));
        assertTrue(violations.stream().anyMatch(item -> item.startsWith("clientMessageId")));
        assertThrows(IllegalArgumentException.class, () -> EnvelopePolicy.requireValid(invalid));
    }

    @Test
    void permitsEventsWithoutRequestIdsButNotCommands() {
        Envelope event = validCommand()
                .setKind(MessageKind.MESSAGE_KIND_EVENT)
                .clearRequestId()
                .build();
        EnvelopePolicy.requireValid(event);

        Envelope command = event.toBuilder()
                .setKind(MessageKind.MESSAGE_KIND_COMMAND)
                .build();
        assertThrows(IllegalArgumentException.class, () -> EnvelopePolicy.requireValid(command));
    }

    private static Envelope.Builder validCommand() {
        return Envelope.newBuilder()
                .setProtocolVersion(2)
                .setKind(MessageKind.MESSAGE_KIND_COMMAND)
                .setMessageType(100)
                .setRequestId("req-1")
                .setSessionId("session-1")
                .setClientMessageId("client-1")
                .setSentAtEpochMs(1_700_000_000_000L)
                .setPayload(ByteString.copyFromUtf8("abc"));
    }
}
