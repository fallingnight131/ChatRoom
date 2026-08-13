package com.fallingnight.chat.storage.s3;

import static org.junit.jupiter.api.Assertions.*;

import com.fallingnight.chat.application.profile.ProfileImageObjectEvidence;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.core.ResponseBytes;
import software.amazon.awssdk.services.s3.model.ChecksumMode;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.S3Exception;

final class S3ProfileImageObjectReaderTest {
    private static final byte[] PNG = {
            (byte) 0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a, 1};

    @Test void getsExactPrivateObjectWithProviderChecksumEnabled() throws Exception {
        ProfileImageObjectEvidence evidence = evidence(PNG);
        AtomicReference<GetObjectRequest> requested = new AtomicReference<>();
        var reader = new S3ProfileImageObjectReader(request -> {
            requested.set(request);
            return response(PNG, evidence.contentSha256(), "image/png", PNG.length);
        }, "chat-private");

        try (var payload = reader.read(evidence).orElseThrow()) {
            assertEquals(PNG.length, payload.byteSize());
            assertArrayEquals(PNG, payload.withCopy(bytes -> bytes.clone()));
        }
        assertEquals("chat-private", requested.get().bucket());
        assertEquals(evidence.objectKey(), requested.get().key());
        assertEquals(ChecksumMode.ENABLED, requested.get().checksumMode());
    }

    @Test void treatsOnlyNotFoundAsMissing() throws Exception {
        ProfileImageObjectEvidence evidence = evidence(PNG);
        var missing = new S3ProfileImageObjectReader(request -> {
            throw S3Exception.builder().statusCode(404).message("missing").build();
        }, "chat-private");
        assertEquals(Optional.empty(), missing.read(evidence));

        var denied = new S3ProfileImageObjectReader(request -> {
            throw S3Exception.builder().statusCode(403).message("denied").build();
        }, "chat-private");
        assertThrows(S3ProfileImageObjectReadException.class,
                () -> denied.read(evidence));
    }

    @Test void failsClosedOnMissingOrMismatchedResponseEvidence() throws Exception {
        ProfileImageObjectEvidence evidence = evidence(PNG);
        assertRejected(evidence, response(PNG, null, "image/png", PNG.length));
        byte[] otherHash = new byte[32]; otherHash[0] = 7;
        assertRejected(evidence, response(PNG, otherHash, "image/png", PNG.length));
        assertRejected(evidence, response(PNG, evidence.contentSha256(),
                "application/octet-stream", PNG.length));
        assertRejected(evidence, response(PNG, evidence.contentSha256(),
                "image/png", PNG.length + 1L));
    }

    private static void assertRejected(ProfileImageObjectEvidence evidence,
            ResponseBytes<GetObjectResponse> response) {
        var reader = new S3ProfileImageObjectReader(request -> response, "chat-private");
        assertThrows(S3ProfileImageObjectReadException.class,
                () -> reader.read(evidence));
    }

    private static ResponseBytes<GetObjectResponse> response(byte[] bytes, byte[] hash,
            String mediaType, long contentLength) {
        var builder = GetObjectResponse.builder()
                .contentLength(contentLength)
                .contentType(mediaType);
        if (hash != null)
            builder.checksumSHA256(Base64.getEncoder().encodeToString(hash));
        return ResponseBytes.fromByteArray(builder.build(), bytes);
    }

    private static ProfileImageObjectEvidence evidence(byte[] bytes) throws Exception {
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(bytes);
        return new ProfileImageObjectEvidence(ProfileImageObjectEvidence.objectKey(digest),
                bytes.length, digest, "image/png");
    }
}
