package com.fallingnight.chat.protocol.v2;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/** Rejects malformed envelopes before they cross into the application core. */
public final class EnvelopePolicy {
    public static final int PROTOCOL_VERSION = 2;
    public static final int MAX_IDENTIFIER_BYTES = 128;
    public static final int MAX_PAYLOAD_BYTES = 1024 * 1024;

    private EnvelopePolicy() {
    }

    public static List<String> violations(Envelope envelope) {
        List<String> violations = new ArrayList<>();
        if (envelope.getProtocolVersion() != PROTOCOL_VERSION) {
            violations.add("unsupported protocolVersion");
        }
        if (envelope.getKind() == MessageKind.MESSAGE_KIND_UNSPECIFIED
                || envelope.getKind() == MessageKind.UNRECOGNIZED) {
            violations.add("message kind is required");
        }
        if (envelope.getMessageType() == 0) {
            violations.add("messageType is required");
        }
        if (requiresRequestId(envelope.getKind()) && envelope.getRequestId().isBlank()) {
            violations.add("requestId is required for command/response/error envelopes");
        }
        validateIdentifier("requestId", envelope.getRequestId(), violations);
        validateIdentifier("sessionId", envelope.getSessionId(), violations);
        validateIdentifier("clientMessageId", envelope.getClientMessageId(), violations);
        if (envelope.getSentAtEpochMs() <= 0) {
            violations.add("sentAtEpochMs must be positive");
        }
        if (envelope.getPayload().size() > MAX_PAYLOAD_BYTES) {
            violations.add("payload exceeds the envelope limit");
        }
        return List.copyOf(violations);
    }

    public static void requireValid(Envelope envelope) {
        List<String> violations = violations(envelope);
        if (!violations.isEmpty()) {
            throw new IllegalArgumentException(String.join("; ", violations));
        }
    }

    private static boolean requiresRequestId(MessageKind kind) {
        return kind == MessageKind.MESSAGE_KIND_COMMAND
                || kind == MessageKind.MESSAGE_KIND_RESPONSE
                || kind == MessageKind.MESSAGE_KIND_ERROR;
    }

    private static void validateIdentifier(
            String field, String value, List<String> violations) {
        if (value.getBytes(StandardCharsets.UTF_8).length > MAX_IDENTIFIER_BYTES) {
            violations.add(field + " exceeds " + MAX_IDENTIFIER_BYTES + " UTF-8 bytes");
        }
    }
}
