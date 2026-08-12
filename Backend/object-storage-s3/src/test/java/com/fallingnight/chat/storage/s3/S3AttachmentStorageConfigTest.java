package com.fallingnight.chat.storage.s3;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;

class S3AttachmentStorageConfigTest {
    @Test
    void parsesExplicitNonSecretEnvironmentAndDefaultsVirtualHostStyle() {
        S3AttachmentStorageConfig config = S3AttachmentStorageConfig.fromEnvironment(Map.of(
                "CHATROOM_ATTACHMENT_S3_ENDPOINT", "https://s3.example.test",
                "CHATROOM_ATTACHMENT_S3_REGION", "ap-test-1",
                "CHATROOM_ATTACHMENT_S3_BUCKET", "chat-private"));

        assertEquals(URI.create("https://s3.example.test"), config.endpoint());
        assertEquals(Region.of("ap-test-1"), config.region());
        assertEquals("chat-private", config.bucket());
        assertFalse(config.pathStyleAccess());
    }

    @Test
    void acceptsExplicitPathStyleWithoutReadingCredentials() {
        Map<String, String> values = new HashMap<>(Map.of(
                "CHATROOM_ATTACHMENT_S3_ENDPOINT", "https://cos.example.test/",
                "CHATROOM_ATTACHMENT_S3_REGION", "ap-guangzhou",
                "CHATROOM_ATTACHMENT_S3_BUCKET", "chat-123456",
                "CHATROOM_ATTACHMENT_S3_PATH_STYLE", "true"));
        values.put("AWS_SECRET_ACCESS_KEY", "must-not-be-read-by-config");

        S3AttachmentStorageConfig config =
                S3AttachmentStorageConfig.fromEnvironment(values);

        assertTrue(config.pathStyleAccess());
        assertEquals("chat-123456", config.bucket());
    }

    @Test
    void rejectsMissingAmbiguousOrUnsafeEndpointConfiguration() {
        assertThrows(IllegalArgumentException.class,
                () -> S3AttachmentStorageConfig.fromEnvironment(Map.of()));
        assertThrows(IllegalArgumentException.class, () -> config("http://s3.example.test"));
        assertThrows(IllegalArgumentException.class,
                () -> config("https://user:secret@s3.example.test"));
        assertThrows(IllegalArgumentException.class,
                () -> config("https://s3.example.test/bucket"));
        assertThrows(IllegalArgumentException.class,
                () -> S3AttachmentStorageConfig.fromEnvironment(Map.of(
                        "CHATROOM_ATTACHMENT_S3_ENDPOINT", "https://s3.example.test",
                        "CHATROOM_ATTACHMENT_S3_REGION", "ap-test-1",
                        "CHATROOM_ATTACHMENT_S3_BUCKET", "chat-private",
                        "CHATROOM_ATTACHMENT_S3_PATH_STYLE", "TRUE")));
    }

    @Test
    void opensAndClosesSdkResourcesWithoutNetworkAccess() {
        S3AttachmentStorageConfig config = config("https://s3.example.test");
        try (S3AttachmentObjectStoreRuntime runtime =
                S3AttachmentObjectStoreRuntime.open(
                        config,
                        StaticCredentialsProvider.create(AwsBasicCredentials.create(
                                "fixture-access", "fixture-secret")),
                        Clock.fixed(
                                Instant.parse("2026-08-12T12:00:00Z"), ZoneOffset.UTC))) {
            assertTrue(runtime instanceof AutoCloseable);
        }
    }

    private static S3AttachmentStorageConfig config(String endpoint) {
        return S3AttachmentStorageConfig.fromEnvironment(Map.of(
                "CHATROOM_ATTACHMENT_S3_ENDPOINT", endpoint,
                "CHATROOM_ATTACHMENT_S3_REGION", "ap-test-1",
                "CHATROOM_ATTACHMENT_S3_BUCKET", "chat-private"));
    }
}
