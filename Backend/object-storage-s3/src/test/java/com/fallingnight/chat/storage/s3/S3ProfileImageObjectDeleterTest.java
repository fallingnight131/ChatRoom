package com.fallingnight.chat.storage.s3;

import static org.junit.jupiter.api.Assertions.*;

import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.DeleteObjectResponse;
import software.amazon.awssdk.services.s3.model.S3Exception;

final class S3ProfileImageObjectDeleterTest {
    private static final String KEY = "avatars/sha256/" + "0a".repeat(32) + ".png";

    @Test void deletesOnlyExactPrivateProfileImageKeyAndTreats404AsComplete() {
        AtomicReference<DeleteObjectRequest> request = new AtomicReference<>();
        var deleter = new S3ProfileImageObjectDeleter(value -> {
            request.set(value); return DeleteObjectResponse.builder().build();
        }, "chat-private");
        deleter.deleteIfPresent(KEY);
        assertEquals("chat-private", request.get().bucket()); assertEquals(KEY, request.get().key());

        new S3ProfileImageObjectDeleter(value -> {
            throw S3Exception.builder().statusCode(404).message("missing").build();
        }, "chat-private").deleteIfPresent(KEY);
    }

    @Test void rejectsUnsafeKeysAndFailsOnProviderDenial() {
        var deleter = new S3ProfileImageObjectDeleter(value -> {
            throw new AssertionError("DELETE must not run");
        }, "chat-private");
        for (String key : java.util.List.of("attachments/id",
                "avatars/sha256/" + "AA".repeat(32) + ".png",
                "avatars/sha256/" + "00".repeat(31) + ".png", KEY + "\n"))
            assertThrows(IllegalArgumentException.class, () -> deleter.deleteIfPresent(key));

        var denied = new S3ProfileImageObjectDeleter(value -> {
            throw S3Exception.builder().statusCode(403).message("denied").build();
        }, "chat-private");
        assertThrows(S3ProfileImageObjectDeletionException.class,
                () -> denied.deleteIfPresent(KEY));
    }
}
