package com.fallingnight.chat.gateway.compatibility.v1;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.core.StreamWriteConstraints;
import com.fasterxml.jackson.core.json.JsonReadFeature;
import com.fasterxml.jackson.core.json.JsonWriteFeature;
import com.fallingnight.chat.application.compatibility.v1.LegacyV1FriendDirectoryService;
import com.fallingnight.chat.application.compatibility.v1.LegacyV1FriendDirectorySnapshot;
import com.fallingnight.chat.application.compatibility.v1.LegacyV1FriendSummary;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.time.Clock;
import java.util.Objects;
import java.util.UUID;

/** Strict bounded codec for the existing V1 friend-list envelope. */
public final class V1JsonFriendDirectoryCodec {
    public static final int MAX_REQUEST_WIRE_BYTES = 4 * 1024;
    public static final int MAX_RESPONSE_WIRE_BYTES = 1024 * 1024;

    public enum RequestKind { FRIEND_LIST, MALFORMED_FRIEND_LIST, OTHER }

    private final JsonFactory json;
    private final Clock clock;

    public V1JsonFriendDirectoryCodec(Clock clock) {
        this.clock = Objects.requireNonNull(clock, "clock");
        this.json = JsonFactory.builder()
                .streamReadConstraints(StreamReadConstraints.builder()
                        .maxNestingDepth(8).maxStringLength(1024).maxNumberLength(32).build())
                .streamWriteConstraints(StreamWriteConstraints.builder()
                        .maxNestingDepth(6).build())
                .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
                .disable(JsonReadFeature.ALLOW_JAVA_COMMENTS)
                .disable(JsonReadFeature.ALLOW_TRAILING_COMMA)
                .disable(JsonWriteFeature.ESCAPE_NON_ASCII)
                .build();
    }

    public RequestKind classify(byte[] wire) {
        Objects.requireNonNull(wire, "wire");
        if (wire.length == 0 || wire.length > MAX_REQUEST_WIRE_BYTES) return RequestKind.OTHER;
        String type = null;
        boolean dataObject = false;
        try (JsonParser parser = json.createParser(wire)) {
            if (parser.nextToken() != JsonToken.START_OBJECT) return RequestKind.OTHER;
            while (parser.nextToken() != JsonToken.END_OBJECT) {
                if (parser.currentToken() != JsonToken.FIELD_NAME) {
                    return "FRIEND_LIST_REQ".equals(type)
                            ? RequestKind.MALFORMED_FRIEND_LIST : RequestKind.OTHER;
                }
                String field = parser.currentName();
                JsonToken value = parser.nextToken();
                if ("type".equals(field)) {
                    if (value != JsonToken.VALUE_STRING) return RequestKind.OTHER;
                    type = parser.getText();
                } else if ("data".equals(field)) {
                    dataObject = value == JsonToken.START_OBJECT;
                    parser.skipChildren();
                } else {
                    parser.skipChildren();
                }
            }
            if (parser.nextToken() != null) {
                return "FRIEND_LIST_REQ".equals(type)
                        ? RequestKind.MALFORMED_FRIEND_LIST : RequestKind.OTHER;
            }
        } catch (IOException | RuntimeException exception) {
            return "FRIEND_LIST_REQ".equals(type)
                    ? RequestKind.MALFORMED_FRIEND_LIST : RequestKind.OTHER;
        }
        if (!"FRIEND_LIST_REQ".equals(type)) return RequestKind.OTHER;
        return dataObject ? RequestKind.FRIEND_LIST : RequestKind.MALFORMED_FRIEND_LIST;
    }

    public byte[] encode(LegacyV1FriendDirectorySnapshot snapshot) {
        Objects.requireNonNull(snapshot, "snapshot");
        if (snapshot.friends().size() > LegacyV1FriendDirectoryService.MAX_FRIENDS) {
            throw new IllegalArgumentException("friend response exceeds its fixed row bound");
        }
        ByteArrayOutputStream output = new ByteArrayOutputStream(4096);
        try (JsonGenerator generator = json.createGenerator(output)) {
            generator.writeStartObject();
            generator.writeStringField("type", "FRIEND_LIST_RSP");
            generator.writeStringField("id", UUID.randomUUID().toString());
            generator.writeNumberField("timestamp", clock.millis());
            generator.writeObjectFieldStart("data");
            generator.writeArrayFieldStart("friends");
            for (LegacyV1FriendSummary friend : snapshot.friends()) {
                generator.writeStartObject();
                generator.writeNumberField("friendshipId", friend.friendshipId());
                generator.writeNumberField("friendId", friend.friendId());
                generator.writeStringField("username", friend.username());
                generator.writeStringField("displayName", friend.displayName());
                generator.writeBooleanField("isOnline", friend.online());
                generator.writeNumberField("unread", Math.min(friend.unread(), Integer.MAX_VALUE));
                generator.writeNumberField("peerLastReadMessageId",
                        friend.peerLastReadMessageId());
                generator.writeEndObject();
            }
            generator.writeEndArray();
            generator.writeNumberField("pendingFriendRequests", snapshot.pendingFriendRequests());
            generator.writeEndObject();
            generator.writeEndObject();
        } catch (IOException exception) {
            throw new IllegalStateException("V1 friend directory encoding failed", exception);
        }
        byte[] encoded = output.toByteArray();
        if (encoded.length > MAX_RESPONSE_WIRE_BYTES) {
            throw new IllegalStateException("V1 friend response exceeded its fixed wire bound");
        }
        return encoded;
    }
}
