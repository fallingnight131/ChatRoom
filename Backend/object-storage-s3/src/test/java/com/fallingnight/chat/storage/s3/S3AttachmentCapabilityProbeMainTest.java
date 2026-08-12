package com.fallingnight.chat.storage.s3;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.junit.jupiter.api.Test;

class S3AttachmentCapabilityProbeMainTest {
    @Test
    void refusesToReadProviderConfigurationWithoutExactConfirmation() {
        ByteArrayOutputStream outputBytes = new ByteArrayOutputStream();
        ByteArrayOutputStream errorBytes = new ByteArrayOutputStream();

        int status = S3AttachmentCapabilityProbeMain.execute(
                Map.of(
                        "CHATROOM_ATTACHMENT_S3_ENDPOINT",
                        "https://user:secret@objects.example.test"),
                new PrintStream(outputBytes, true, StandardCharsets.UTF_8),
                new PrintStream(errorBytes, true, StandardCharsets.UTF_8));

        String errors = errorBytes.toString(StandardCharsets.UTF_8);
        assertEquals(1, status);
        assertEquals("", outputBytes.toString(StandardCharsets.UTF_8));
        assertFalse(errors.contains("objects.example.test"));
        assertFalse(errors.contains("secret"));
        assertEquals(
                "attachment object-store capability probe: FAIL "
                        + "(explicit create-and-delete confirmation is required)\n",
                errors);
    }
}
