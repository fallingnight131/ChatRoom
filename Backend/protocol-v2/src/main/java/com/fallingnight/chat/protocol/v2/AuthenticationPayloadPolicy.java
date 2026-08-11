package com.fallingnight.chat.protocol.v2;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/** Bounds secret-bearing authentication payloads before application dispatch. */
public final class AuthenticationPayloadPolicy {
    public static final int MAX_USERNAME_BYTES = 128;
    public static final int MAX_PASSWORD_BYTES = 1024;
    public static final int RESUME_TOKEN_BYTES = 32;

    private AuthenticationPayloadPolicy() {
    }

    public static List<String> violations(Authenticate command) {
        List<String> violations = new ArrayList<>();
        int usernameBytes = command.getUsername().getBytes(StandardCharsets.UTF_8).length;
        if (usernameBytes == 0 || usernameBytes > MAX_USERNAME_BYTES) {
            violations.add("username must contain 1..128 UTF-8 bytes");
        }
        int passwordBytes = command.getPasswordUtf8().size();
        if (passwordBytes == 0 || passwordBytes > MAX_PASSWORD_BYTES) {
            violations.add("passwordUtf8 must contain 1..1024 bytes");
        } else if (!command.getPasswordUtf8().isValidUtf8()) {
            violations.add("passwordUtf8 must be valid UTF-8");
        }
        return List.copyOf(violations);
    }

    public static List<String> violations(ResumeSession command) {
        List<String> violations = new ArrayList<>();
        int sessionIdBytes = command.getSessionId().getBytes(StandardCharsets.UTF_8).length;
        if (sessionIdBytes == 0 || sessionIdBytes > EnvelopePolicy.MAX_IDENTIFIER_BYTES) {
            violations.add("sessionId must contain 1..128 UTF-8 bytes");
        }
        if (command.getResumeToken().size() != RESUME_TOKEN_BYTES) {
            violations.add("resumeToken must contain exactly 32 bytes");
        }
        return List.copyOf(violations);
    }

    public static void requireValid(Authenticate command) {
        requireNoViolations(violations(command));
    }

    public static void requireValid(ResumeSession command) {
        requireNoViolations(violations(command));
    }

    private static void requireNoViolations(List<String> violations) {
        if (!violations.isEmpty()) {
            throw new IllegalArgumentException(String.join("; ", violations));
        }
    }
}
