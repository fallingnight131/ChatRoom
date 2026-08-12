package com.fallingnight.chat.storage.s3;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fallingnight.chat.application.attachment.AttachmentUploadGrant;
import com.fallingnight.chat.application.attachment.AttachmentUploadTarget;
import com.fallingnight.chat.application.attachment.StoredAttachmentObject;
import java.net.URI;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.model.ChecksumMode;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.DeleteObjectResponse;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectResponse;
import software.amazon.awssdk.services.s3.model.S3Exception;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

class S3AttachmentObjectStoreTest {
    private static final Instant NOW = Instant.parse("2026-08-12T12:00:00Z");
    private static final byte[] HASH = hash();
    private static final AttachmentUploadTarget TARGET = new AttachmentUploadTarget(
            "attachments/00000000-0000-0000-0000-000000000001",
            "application/pdf", 1024, HASH);

    @Test
    void presignsExactCreateOnlyPutWithoutReturningHostOrLengthHeaders() {
        try (S3Presigner presigner = presigner()) {
            S3AttachmentObjectStore store = new S3AttachmentObjectStore(
                    request -> {
                        throw new AssertionError("HEAD should not run");
                    }, presigner::presignPutObject, "chat-private", clock());

            AttachmentUploadGrant grant = store.issueCreateOnlyPut(
                    TARGET, NOW.plusSeconds(300));

            assertEquals("https", grant.uploadUri().getScheme());
            assertTrue(grant.uploadUri().toString().contains("X-Amz-Signature="));
            assertEquals("application/pdf", grant.requiredHeaders().get("content-type"));
            assertEquals(Base64.getEncoder().encodeToString(HASH),
                    grant.requiredHeaders().get("x-amz-checksum-sha256"));
            assertEquals("*", grant.requiredHeaders().get("if-none-match"));
            assertFalse(grant.requiredHeaders().containsKey("host"));
            assertFalse(grant.requiredHeaders().containsKey("content-length"));
            assertEquals(NOW.plusSeconds(300), grant.expiresAt());
        }
    }

    @Test
    void requestsTrustedSha256AndMapsHeadMetadata() {
        AtomicReference<HeadObjectRequest> request = new AtomicReference<>();
        S3AttachmentObjectStore store = store(value -> {
            request.set(value);
            return HeadObjectResponse.builder()
                    .contentLength(TARGET.byteSize())
                    .checksumSHA256(Base64.getEncoder().encodeToString(HASH))
                    .build();
        });

        StoredAttachmentObject result = store.inspectSealedObject(TARGET).orElseThrow();

        assertEquals("chat-private", request.get().bucket());
        assertEquals(TARGET.objectKey(), request.get().key());
        assertEquals(ChecksumMode.ENABLED, request.get().checksumMode());
        assertEquals(TARGET.objectKey(), result.objectKey());
        assertEquals(TARGET.byteSize(), result.byteSize());
        assertArrayEquals(HASH, result.contentSha256());
    }

    @Test
    void treatsOnlyNotFoundAsMissingAndFailsClosedWithoutChecksum() {
        S3AttachmentObjectStore missing = store(request -> {
            throw S3Exception.builder().statusCode(404).message("missing").build();
        });
        assertEquals(Optional.empty(), missing.inspectSealedObject(TARGET));

        S3AttachmentObjectStore denied = store(request -> {
            throw S3Exception.builder().statusCode(403).message("denied").build();
        });
        assertThrows(AttachmentObjectStoreException.class,
                () -> denied.inspectSealedObject(TARGET));

        S3AttachmentObjectStore noChecksum = store(request ->
                HeadObjectResponse.builder().contentLength(TARGET.byteSize()).build());
        assertThrows(AttachmentObjectStoreException.class,
                () -> noChecksum.inspectSealedObject(TARGET));
    }

    @Test
    void rejectsGrantOutsideTenMinuteBoundary() {
        try (S3Presigner presigner = presigner()) {
            S3AttachmentObjectStore store = new S3AttachmentObjectStore(
                    request -> {
                        throw new AssertionError("HEAD should not run");
                    }, presigner::presignPutObject, "chat-private", clock());
            assertThrows(IllegalArgumentException.class,
                    () -> store.issueCreateOnlyPut(TARGET, NOW));
            assertThrows(IllegalArgumentException.class,
                    () -> store.issueCreateOnlyPut(TARGET, NOW.plusSeconds(601)));
            AttachmentUploadTarget tooLarge = new AttachmentUploadTarget(
                    TARGET.objectKey(), TARGET.mediaType(),
                    S3AttachmentObjectStore.SINGLE_PUT_MAX_BYTE_SIZE + 1, HASH);
            assertThrows(IllegalArgumentException.class,
                    () -> store.issueCreateOnlyPut(tooLarge, NOW.plusSeconds(300)));
        }
    }

    @Test
    void deletesExactObjectIdempotentlyAndFailsClosedOnProviderDenial() {
        AtomicReference<DeleteObjectRequest> deleted = new AtomicReference<>();
        S3AttachmentObjectStore store = deleteStore(request -> {
            deleted.set(request);
            return DeleteObjectResponse.builder().build();
        });

        store.deleteIfPresent(TARGET.objectKey());

        assertEquals("chat-private", deleted.get().bucket());
        assertEquals(TARGET.objectKey(), deleted.get().key());

        deleteStore(request -> {
            throw S3Exception.builder().statusCode(404).message("missing").build();
        }).deleteIfPresent(TARGET.objectKey());
        S3AttachmentObjectStore denied = deleteStore(request -> {
            throw S3Exception.builder().statusCode(403).message("denied").build();
        });
        assertThrows(AttachmentObjectStoreException.class,
                () -> denied.deleteIfPresent(TARGET.objectKey()));
        assertThrows(IllegalArgumentException.class,
                () -> store.deleteIfPresent("\n"));
    }

    private static S3AttachmentObjectStore store(S3AttachmentObjectStore.HeadReader reader) {
        return new S3AttachmentObjectStore(
                reader,
                request -> {
                    throw new AssertionError("signer should not run");
                },
                "chat-private",
                clock());
    }

    private static S3AttachmentObjectStore deleteStore(
            S3AttachmentObjectStore.DeleteWriter writer) {
        return new S3AttachmentObjectStore(
                request -> {
                    throw new AssertionError("HEAD should not run");
                },
                request -> {
                    throw new AssertionError("signer should not run");
                },
                writer,
                "chat-private",
                clock());
    }

    private static S3Presigner presigner() {
        return S3Presigner.builder()
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create("test-access-key", "test-secret-key")))
                .region(Region.US_EAST_1)
                .endpointOverride(URI.create("https://objects.example.test"))
                .serviceConfiguration(S3Configuration.builder()
                        .pathStyleAccessEnabled(true)
                        .build())
                .build();
    }

    private static Clock clock() {
        return Clock.fixed(NOW, ZoneOffset.UTC);
    }

    private static byte[] hash() {
        byte[] value = new byte[32];
        value[0] = 7;
        return value;
    }
}
