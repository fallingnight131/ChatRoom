package com.fallingnight.chat.storage.s3;

import static org.junit.jupiter.api.Assertions.*;

import com.fallingnight.chat.application.profile.CanonicalProfileImage;
import java.io.IOException;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.s3.model.*;

final class S3ProfileImageObjectWriterTest {
    private static final byte[] PNG = {
            (byte) 0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a, 1};

    @Test void createsExactPrivateObjectWithChecksumAndNoOverwrite() throws Exception {
        CanonicalProfileImage image = image();
        AtomicReference<PutObjectRequest> request = new AtomicReference<>();
        AtomicReference<byte[]> body = new AtomicReference<>();
        var writer = new S3ProfileImageObjectWriter((value, content) -> {
            request.set(value); body.set(read(content));
            return PutObjectResponse.builder()
                    .checksumSHA256(hashBase64(image)).build();
        }, value -> { throw new AssertionError("HEAD must not run"); }, "chat-private");

        var stored = writer.storeIfAbsent(image);

        assertTrue(stored.created()); assertArrayEquals(PNG, body.get());
        assertEquals("chat-private", request.get().bucket());
        assertEquals(stored.evidence().objectKey(), request.get().key());
        assertEquals("image/png", request.get().contentType());
        assertEquals((long) PNG.length, request.get().contentLength());
        assertEquals(hashBase64(image), request.get().checksumSHA256());
        assertEquals("*", request.get().ifNoneMatch());
    }

    @Test void convergesCreateConflictOnlyAfterExactChecksumHead() throws Exception {
        CanonicalProfileImage image = image();
        AtomicReference<HeadObjectRequest> head = new AtomicReference<>();
        var writer = new S3ProfileImageObjectWriter((request, body) -> {
            throw S3Exception.builder().statusCode(412).message("exists").build();
        }, request -> {
            head.set(request);
            return HeadObjectResponse.builder().contentLength((long) PNG.length)
                    .contentType("image/png").checksumSHA256(hashBase64(image)).build();
        }, "chat-private");

        var stored = writer.storeIfAbsent(image);

        assertFalse(stored.created());
        assertEquals(stored.evidence().objectKey(), head.get().key());
        assertEquals(ChecksumMode.ENABLED, head.get().checksumMode());
    }

    @Test void failsClosedOnProviderDenialOrMissingAndMismatchedEvidence()
            throws Exception {
        CanonicalProfileImage image = image();
        assertThrows(S3ProfileImageObjectWriteException.class,
                () -> writerThrowing(403).storeIfAbsent(image));
        var missingPutChecksum = new S3ProfileImageObjectWriter(
                (request, body) -> PutObjectResponse.builder().build(),
                request -> { throw new AssertionError(); }, "chat-private");
        assertThrows(S3ProfileImageObjectWriteException.class,
                () -> missingPutChecksum.storeIfAbsent(image));

        byte[] other = new byte[32]; other[0] = 7;
        for (HeadObjectResponse head : java.util.List.of(
                HeadObjectResponse.builder().contentLength(PNG.length + 1L)
                        .contentType("image/png").checksumSHA256(hashBase64(image)).build(),
                HeadObjectResponse.builder().contentLength((long) PNG.length)
                        .contentType("application/octet-stream")
                        .checksumSHA256(hashBase64(image)).build(),
                HeadObjectResponse.builder().contentLength((long) PNG.length)
                        .contentType("image/png").checksumSHA256(
                                Base64.getEncoder().encodeToString(other)).build())) {
            var conflict = new S3ProfileImageObjectWriter((request, body) -> {
                throw S3Exception.builder().statusCode(412).message("exists").build();
            }, request -> head, "chat-private");
            assertThrows(S3ProfileImageObjectWriteException.class,
                    () -> conflict.storeIfAbsent(image));
        }
    }

    private static S3ProfileImageObjectWriter writerThrowing(int status) {
        return new S3ProfileImageObjectWriter((request, body) -> {
            throw S3Exception.builder().statusCode(status).message("failure").build();
        }, request -> { throw new AssertionError(); }, "chat-private");
    }

    private static byte[] read(software.amazon.awssdk.core.sync.RequestBody body) {
        try (var input = body.contentStreamProvider().newStream()) {
            return input.readAllBytes();
        } catch (IOException exception) { throw new IllegalStateException(exception); }
    }

    private static String hashBase64(CanonicalProfileImage image) {
        return Base64.getEncoder().encodeToString(image.contentSha256());
    }

    private static CanonicalProfileImage image() throws Exception {
        return new CanonicalProfileImage(PNG, 16, 24,
                MessageDigest.getInstance("SHA-256").digest(PNG));
    }
}
