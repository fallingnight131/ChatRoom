package com.fallingnight.chat.gateway.transport;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.core.json.JsonReadFeature;
import com.fallingnight.chat.application.notification.WebPushSubscriptionMutationRequest;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Arrays;
import java.util.Base64;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** Strict bounded decoder for the browser PushSubscription JSON subset. */
public final class WebPushSubscriptionJsonCodec {
    public static final int MAX_WIRE_BYTES = 8_192;
    private static final int MAX_STRING_CHARS = 2_048;

    private final JsonFactory json = JsonFactory.builder()
            .streamReadConstraints(StreamReadConstraints.builder()
                    .maxNestingDepth(4)
                    .maxStringLength(MAX_STRING_CHARS)
                    .maxNumberLength(20)
                    .build())
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .disable(JsonReadFeature.ALLOW_JAVA_COMMENTS)
            .disable(JsonReadFeature.ALLOW_TRAILING_COMMA)
            .build();

    /** Consumes and clears {@code wire}; the returned request owns defensive copies. */
    public WebPushSubscriptionMutationRequest decode(UUID installationId, byte[] wire) {
        Objects.requireNonNull(installationId, "installationId");
        Objects.requireNonNull(wire, "wire");
        byte[] endpoint = null;
        byte[] p256dh = null;
        byte[] auth = null;
        try {
            if (wire.length == 0 || wire.length > MAX_WIRE_BYTES) throw invalid();
            Optional<Instant> expiration = Optional.empty();
            boolean expirationSeen = false;
            try (JsonParser parser = json.createParser(wire)) {
                require(parser.nextToken() == JsonToken.START_OBJECT);
                while (parser.nextToken() != JsonToken.END_OBJECT) {
                    require(parser.currentToken() == JsonToken.FIELD_NAME);
                    String field = parser.currentName();
                    JsonToken value = parser.nextToken();
                    switch (field) {
                        case "endpoint" -> {
                            require(value == JsonToken.VALUE_STRING);
                            String text = parser.getText();
                            require(text.chars().allMatch(character -> character <= 0x7f));
                            endpoint = text.getBytes(StandardCharsets.US_ASCII);
                        }
                        case "expirationTime" -> {
                            expirationSeen = true;
                            if (value == JsonToken.VALUE_NULL) {
                                expiration = Optional.empty();
                            } else {
                                require(value == JsonToken.VALUE_NUMBER_INT);
                                long millis = parser.getLongValue();
                                require(millis > 0);
                                expiration = Optional.of(Instant.ofEpochMilli(millis));
                            }
                        }
                        case "keys" -> {
                            require(value == JsonToken.START_OBJECT);
                            byte[][] keys = readKeys(parser);
                            p256dh = keys[0];
                            auth = keys[1];
                        }
                        default -> throw invalid();
                    }
                }
                require(parser.nextToken() == null);
            }
            require(endpoint != null && p256dh != null && auth != null && expirationSeen);
            return WebPushSubscriptionMutationRequest.copyOf(
                    installationId, expiration, endpoint, p256dh, auth);
        } catch (IOException | RuntimeException exception) {
            throw invalid();
        } finally {
            Arrays.fill(wire, (byte) 0);
            clear(endpoint); clear(p256dh); clear(auth);
        }
    }

    private static byte[][] readKeys(JsonParser parser) throws IOException {
        byte[] p256dh = null;
        byte[] auth = null;
        try {
            while (parser.nextToken() != JsonToken.END_OBJECT) {
                require(parser.currentToken() == JsonToken.FIELD_NAME);
                String field = parser.currentName();
                require(parser.nextToken() == JsonToken.VALUE_STRING);
                if ("p256dh".equals(field)) p256dh = decodeBase64Url(parser.getText());
                else if ("auth".equals(field)) auth = decodeBase64Url(parser.getText());
                else throw invalid();
            }
            require(p256dh != null && auth != null);
            return new byte[][] {p256dh, auth};
        } catch (IOException | RuntimeException exception) {
            clear(p256dh); clear(auth);
            throw exception;
        }
    }

    private static byte[] decodeBase64Url(String value) {
        if (!value.matches("[A-Za-z0-9_-]{1,128}")) throw invalid();
        try {
            return Base64.getUrlDecoder().decode(value);
        } catch (IllegalArgumentException exception) {
            throw invalid();
        }
    }

    private static void require(boolean condition) {
        if (!condition) throw invalid();
    }

    private static IllegalArgumentException invalid() {
        return new IllegalArgumentException("invalid Web Push subscription JSON");
    }

    private static void clear(byte[] value) {
        if (value != null) Arrays.fill(value, (byte) 0);
    }
}
