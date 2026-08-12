package com.fallingnight.chat.gateway.runtime;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;
import org.junit.jupiter.api.Test;

final class V1RoomPasswordKeyMaterialTest {
    @Test void requiresCanonicalBase64WithExactly32BytesAndCloses() {
        assertThrows(IllegalArgumentException.class,
                () -> V1RoomPasswordKeyMaterial.fromEnvironment(Map.of()));
        assertThrows(IllegalArgumentException.class,
                () -> V1RoomPasswordKeyMaterial.fromEnvironment(Map.of(
                        V1RoomPasswordKeyMaterial.ENVIRONMENT_KEY, "not base64")));
        assertThrows(IllegalArgumentException.class,
                () -> V1RoomPasswordKeyMaterial.fromEnvironment(Map.of(
                        V1RoomPasswordKeyMaterial.ENVIRONMENT_KEY,
                        Base64.getEncoder().withoutPadding().encodeToString(new byte[32]))));
        assertThrows(IllegalArgumentException.class,
                () -> V1RoomPasswordKeyMaterial.fromEnvironment(Map.of(
                        V1RoomPasswordKeyMaterial.ENVIRONMENT_KEY,
                        Base64.getEncoder().encodeToString(new byte[31]))));

        String encoded = Base64.getEncoder().encodeToString(new byte[32]);
        V1RoomPasswordKeyMaterial material = V1RoomPasswordKeyMaterial.fromEnvironment(Map.of(
                V1RoomPasswordKeyMaterial.ENVIRONMENT_KEY, encoded));
        try (var encoder = material.newEncoder()) {
            assertTrue(encoder.hash("secret".getBytes(StandardCharsets.UTF_8))
                    .idempotencyTag().startsWith("hmac-sha256:v1:"));
        }
        material.close(); assertTrue(material.isClosed());
        assertThrows(IllegalStateException.class, material::newEncoder);
    }
}
