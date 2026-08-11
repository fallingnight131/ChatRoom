package com.fallingnight.chat.gateway.compatibility.v1;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.StreamWriteConstraints;
import com.fasterxml.jackson.core.json.JsonWriteFeature;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.time.Clock;
import java.util.Objects;
import java.util.UUID;

/** Bounded encoder for the inactive V1 connection-lifecycle slice. */
public final class V1JsonLifecycleCodec {
    public static final int MAX_LIFECYCLE_WIRE_BYTES = 4 * 1024;
    private static final String REPLACED_REASON =
            "您的账号在其他地方登录，当前连接已被断开";

    private final JsonFactory json;
    private final Clock clock;

    public V1JsonLifecycleCodec(Clock clock) {
        this.clock = Objects.requireNonNull(clock, "clock");
        this.json = JsonFactory.builder()
                .streamWriteConstraints(StreamWriteConstraints.builder()
                        .maxNestingDepth(4)
                        .build())
                .disable(JsonWriteFeature.ESCAPE_NON_ASCII)
                .build();
    }

    public byte[] encodeForceOffline() {
        ByteArrayOutputStream output = new ByteArrayOutputStream(256);
        try (JsonGenerator generator = json.createGenerator(output)) {
            generator.writeStartObject();
            generator.writeStringField("type", "FORCE_OFFLINE");
            generator.writeStringField("id", UUID.randomUUID().toString());
            generator.writeNumberField("timestamp", clock.millis());
            generator.writeObjectFieldStart("data");
            generator.writeStringField("reason", REPLACED_REASON);
            generator.writeEndObject();
            generator.writeEndObject();
        } catch (IOException exception) {
            throw new IllegalStateException("V1 lifecycle response encoding failed", exception);
        }
        byte[] encoded = output.toByteArray();
        if (encoded.length > MAX_LIFECYCLE_WIRE_BYTES) {
            throw new IllegalStateException("V1 lifecycle response exceeded its fixed bound");
        }
        return encoded;
    }
}
