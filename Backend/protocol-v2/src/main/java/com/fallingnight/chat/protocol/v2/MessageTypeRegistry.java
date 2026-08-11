package com.fallingnight.chat.protocol.v2;

import java.util.Optional;

/** Stable V2 message-type registry; numeric values are never reinterpreted. */
public final class MessageTypeRegistry {
    private MessageTypeRegistry() {
    }

    public static Optional<MessageType> find(int value) {
        MessageType type = MessageType.forNumber(value);
        if (type == null || type == MessageType.MESSAGE_TYPE_UNSPECIFIED) {
            return Optional.empty();
        }
        return Optional.of(type);
    }

    public static MessageKind requiredKind(MessageType type) {
        return switch (type) {
            case MESSAGE_TYPE_CLIENT_HELLO -> MessageKind.MESSAGE_KIND_COMMAND;
            case MESSAGE_TYPE_SERVER_HELLO -> MessageKind.MESSAGE_KIND_RESPONSE;
            case MESSAGE_TYPE_PROTOCOL_ERROR -> MessageKind.MESSAGE_KIND_ERROR;
            case MESSAGE_TYPE_AUTHENTICATE, MESSAGE_TYPE_RESUME_SESSION ->
                    MessageKind.MESSAGE_KIND_COMMAND;
            case MESSAGE_TYPE_SESSION_ESTABLISHED -> MessageKind.MESSAGE_KIND_RESPONSE;
            case MESSAGE_TYPE_AUTHENTICATION_REJECTED -> MessageKind.MESSAGE_KIND_ERROR;
            case MESSAGE_TYPE_UNSPECIFIED, UNRECOGNIZED ->
                    throw new IllegalArgumentException("unregistered message type");
        };
    }

    public static void requireRegisteredKind(Envelope envelope) {
        MessageType type = find(envelope.getMessageType())
                .orElseThrow(() -> new IllegalArgumentException("unregistered messageType"));
        MessageKind required = requiredKind(type);
        if (envelope.getKind() != required) {
            throw new IllegalArgumentException(
                    type.name() + " requires envelope kind " + required.name());
        }
    }
}
