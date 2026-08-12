package com.fallingnight.chat.gateway.runtime;

import com.fallingnight.chat.application.security.SecretBytes;
import com.fallingnight.chat.identity.crypto.LegacyV1RoomPasswordEncoder;
import java.util.Arrays;
import java.util.Base64;
import java.util.Map;
import java.util.Objects;

/** Owns the mandatory decoded V1 protected-room retry key. */
public final class V1RoomPasswordKeyMaterial implements AutoCloseable {
    public static final String ENVIRONMENT_KEY =
            "CHATROOM_V1_ROOM_PASSWORD_HMAC_KEY_BASE64";
    private final SecretBytes key;
    private V1RoomPasswordKeyMaterial(byte[] decoded) {
        key = SecretBytes.copyOf(decoded);
    }

    public static V1RoomPasswordKeyMaterial fromEnvironment(Map<String, String> environment) {
        Objects.requireNonNull(environment, "environment");
        String encoded = environment.get(ENVIRONMENT_KEY);
        if (encoded == null || encoded.isBlank()) {
            throw new IllegalArgumentException(ENVIRONMENT_KEY + " is required");
        }
        byte[] decoded;
        try { decoded = Base64.getDecoder().decode(encoded); }
        catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException(ENVIRONMENT_KEY + " must be canonical Base64",
                    exception);
        }
        try {
            if (decoded.length != LegacyV1RoomPasswordEncoder.KEY_BYTES
                    || !Base64.getEncoder().encodeToString(decoded).equals(encoded)) {
                throw new IllegalArgumentException(
                        ENVIRONMENT_KEY + " must encode exactly 32 bytes as canonical Base64");
            }
            return new V1RoomPasswordKeyMaterial(decoded);
        } finally { Arrays.fill(decoded, (byte) 0); }
    }

    public LegacyV1RoomPasswordEncoder newEncoder() {
        return key.withCopy(LegacyV1RoomPasswordEncoder::new);
    }
    public boolean isClosed() { return key.isClosed(); }
    @Override public void close() { key.close(); }
}
