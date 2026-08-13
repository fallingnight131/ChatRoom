package com.fallingnight.chat.storage.s3;

import static org.junit.jupiter.api.Assertions.*;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.junit.jupiter.api.Test;

final class S3ProfileImageCapabilityProbeMainTest {
    @Test void refusesProviderConfigurationWithoutExactDestructiveConfirmation() {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        ByteArrayOutputStream errors = new ByteArrayOutputStream();
        int status = S3ProfileImageCapabilityProbeMain.execute(Map.of(
                "CHATROOM_ATTACHMENT_S3_ENDPOINT",
                "https://user:secret@objects.example.test"),
                new PrintStream(output, true, StandardCharsets.UTF_8),
                new PrintStream(errors, true, StandardCharsets.UTF_8));
        assertEquals(1, status); assertEquals("", output.toString(StandardCharsets.UTF_8));
        String message = errors.toString(StandardCharsets.UTF_8);
        assertFalse(message.contains("objects.example.test")); assertFalse(message.contains("secret"));
        assertEquals("profile-image object-store capability probe: FAIL "
                + "(explicit profile-image create/read/delete confirmation is required)\n",
                message);
    }
}
