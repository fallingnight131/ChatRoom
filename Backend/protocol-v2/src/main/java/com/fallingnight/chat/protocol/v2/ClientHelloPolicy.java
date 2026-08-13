package com.fallingnight.chat.protocol.v2;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;

/** Structural limits for the unauthenticated V2 handshake payload. */
public final class ClientHelloPolicy {
    public static final int MAX_APP_VERSION_BYTES = 64;
    public static final int MAX_DEVICE_ID_BYTES = 128;

    private ClientHelloPolicy() {
    }

    public static List<String> violations(ClientHello hello) {
        List<String> violations = new ArrayList<>();
        if (hello.getMinimumProtocolVersion() == 0
                || hello.getMaximumProtocolVersion() < hello.getMinimumProtocolVersion()) {
            violations.add("invalid protocol version range");
        }
        if (hello.getPlatform() == ClientPlatform.CLIENT_PLATFORM_UNSPECIFIED
                || hello.getPlatform() == ClientPlatform.UNRECOGNIZED) {
            violations.add("supported client platform is required");
        }
        validateRequiredBounded(
                "appVersion", hello.getAppVersion(), MAX_APP_VERSION_BYTES, violations);
        validateRequiredBounded(
                "clientDeviceId", hello.getClientDeviceId(), MAX_DEVICE_ID_BYTES, violations);
        if (hello.getCapabilitiesCount() > 16) {
            violations.add("too many client capabilities");
        }
        HashSet<ClientCapability> capabilities = new HashSet<>();
        for (ClientCapability capability : hello.getCapabilitiesList()) {
            if (capability != ClientCapability.CLIENT_CAPABILITY_MESSAGE_REACTIONS
                    || !capabilities.add(capability)) {
                violations.add("client capability is unsupported or duplicated");
            }
        }
        return List.copyOf(violations);
    }

    public static void requireValid(ClientHello hello) {
        List<String> violations = violations(hello);
        if (!violations.isEmpty()) {
            throw new IllegalArgumentException(String.join("; ", violations));
        }
    }

    public static boolean supportsCurrentVersion(ClientHello hello) {
        return hello.getMinimumProtocolVersion() <= EnvelopePolicy.PROTOCOL_VERSION
                && hello.getMaximumProtocolVersion() >= EnvelopePolicy.PROTOCOL_VERSION;
    }

    private static void validateRequiredBounded(
            String field, String value, int maximumBytes, List<String> violations) {
        int bytes = value.getBytes(StandardCharsets.UTF_8).length;
        if (bytes == 0 || bytes > maximumBytes) {
            violations.add(field + " must contain 1.." + maximumBytes + " UTF-8 bytes");
        }
    }
}
