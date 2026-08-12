package com.fallingnight.chat.storage.s3;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fallingnight.chat.application.attachment.AttachmentObjectDeletionPort;
import com.fallingnight.chat.application.attachment.AttachmentObjectStorePort;
import com.fallingnight.chat.application.attachment.AttachmentUploadGrant;
import com.fallingnight.chat.application.attachment.AttachmentUploadTarget;
import com.fallingnight.chat.application.attachment.StoredAttachmentObject;
import com.fallingnight.chat.storage.s3.S3AttachmentCapabilityProbe.CapabilityReport;
import com.fallingnight.chat.storage.s3.S3AttachmentCapabilityProbe.ProbeHttpClient;
import com.fallingnight.chat.storage.s3.S3AttachmentCapabilityProbe.ProbeHttpResponse;
import java.net.URI;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class S3AttachmentCapabilityProbeTest {
    private static final Instant NOW = Instant.parse("2026-08-12T12:00:00Z");
    private static final URI ORIGIN = URI.create("https://chat.example.test");
    private static final URI SIGNED_URI = URI.create(
            "https://objects.example.test/private?X-Amz-Signature=do-not-print");
    private static final UUID ID = UUID.fromString(
            "00000000-0000-0000-0000-000000000123");
    private static final byte[] PAYLOAD = "random-probe-fixture".getBytes();

    @Test
    void provesCorsCreateOnlyChecksumAndCleanupWithoutDurableData() {
        FakeObjects objects = new FakeObjects();
        ScriptedHttp http = healthyHttp(objects);

        CapabilityReport report = probe(objects, http).run(ORIGIN);

        assertTrue(report.corsAccepted());
        assertTrue(report.firstPutAccepted());
        assertTrue(report.replayRejected());
        assertTrue(report.checksumVerified());
        assertEquals(1, objects.deleteCalls);
        assertEquals(Optional.empty(), objects.stored);
        assertEquals("attachments/capability-probe-" + ID,
                objects.target.objectKey());
        assertEquals(NOW.plus(S3AttachmentCapabilityProbe.GRANT_LIFETIME),
                objects.expiresAt);
        assertEquals(2, http.putCalls);
        assertTrue(MessageDigest.isEqual(
                objects.target.contentSha256(), sha256(http.firstPayload)));
        assertEquals(objects.headers, http.firstHeaders);
    }

    @Test
    void failsClosedWhenCorsOrInitialPutIsRejectedAndStillCleansUp() {
        FakeObjects corsObjects = new FakeObjects();
        ScriptedHttp missingCors = new ScriptedHttp(corsObjects);
        missingCors.preflight = response(204, Map.of());

        AttachmentObjectStoreCapabilityProbeException corsFailure = assertThrows(
                AttachmentObjectStoreCapabilityProbeException.class,
                () -> probe(corsObjects, missingCors).run(ORIGIN));
        assertTrue(corsFailure.getMessage().contains("CORS origin"));
        assertEquals(1, corsObjects.deleteCalls);
        assertEquals(0, missingCors.putCalls);

        FakeObjects putObjects = new FakeObjects();
        ScriptedHttp rejectedPut = new ScriptedHttp(putObjects);
        rejectedPut.preflight = corsPreflight();
        rejectedPut.puts.add(response(403, corsActual()));

        AttachmentObjectStoreCapabilityProbeException putFailure = assertThrows(
                AttachmentObjectStoreCapabilityProbeException.class,
                () -> probe(putObjects, rejectedPut).run(ORIGIN));
        assertEquals("attachment create-only PUT was rejected", putFailure.getMessage());
        assertEquals(1, putObjects.deleteCalls);
    }

    @Test
    void rejectsProviderWhichAcceptsReplayOrReturnsMismatchedChecksum() {
        FakeObjects replayObjects = new FakeObjects();
        ScriptedHttp replay = new ScriptedHttp(replayObjects);
        replay.preflight = corsPreflight();
        replay.puts.add(response(200, corsActual()));
        replay.puts.add(response(200, corsActual()));

        AttachmentObjectStoreCapabilityProbeException replayFailure = assertThrows(
                AttachmentObjectStoreCapabilityProbeException.class,
                () -> probe(replayObjects, replay).run(ORIGIN));
        assertTrue(replayFailure.getMessage().contains("accepted a create-only PUT replay"));
        assertEquals(1, replayObjects.deleteCalls);

        FakeObjects checksumObjects = new FakeObjects();
        ScriptedHttp checksum = healthyHttp(checksumObjects);
        checksumObjects.mismatchChecksum = true;

        AttachmentObjectStoreCapabilityProbeException checksumFailure = assertThrows(
                AttachmentObjectStoreCapabilityProbeException.class,
                () -> probe(checksumObjects, checksum).run(ORIGIN));
        assertTrue(checksumFailure.getMessage().contains("mismatched sealed-object metadata"));
        assertEquals(1, checksumObjects.deleteCalls);
    }

    @Test
    void preservesPrimaryFailureAndAddsCleanupFailureWithoutLeakingSignedUri() {
        FakeObjects objects = new FakeObjects();
        objects.deleteFailure = true;
        ScriptedHttp replay = new ScriptedHttp(objects);
        replay.preflight = corsPreflight();
        replay.puts.add(response(200, corsActual()));
        replay.puts.add(response(200, corsActual()));

        AttachmentObjectStoreCapabilityProbeException failure = assertThrows(
                AttachmentObjectStoreCapabilityProbeException.class,
                () -> probe(objects, replay).run(ORIGIN));

        assertTrue(failure.getMessage().contains("create-only PUT replay"));
        assertEquals(1, failure.getSuppressed().length);
        assertEquals("attachment object-store cleanup failed",
                failure.getSuppressed()[0].getMessage());
        assertFalse(failure.toString().contains("X-Amz-Signature"));
        assertFalse(failure.getSuppressed()[0].toString().contains("objects.example"));
    }

    @Test
    void failsWhenDeleteReturnsButObjectRemainsVisible() {
        FakeObjects objects = new FakeObjects();
        objects.retainAfterDelete = true;

        AttachmentObjectStoreCapabilityProbeException failure = assertThrows(
                AttachmentObjectStoreCapabilityProbeException.class,
                () -> probe(objects, healthyHttp(objects)).run(ORIGIN));

        assertEquals("attachment object-store cleanup verification failed",
                failure.getMessage());
        assertEquals(1, objects.deleteCalls);
    }

    @Test
    void rejectsUnsafeOriginBeforeCreatingAuthorization() {
        FakeObjects objects = new FakeObjects();

        assertThrows(IllegalArgumentException.class,
                () -> probe(objects, new ScriptedHttp(objects))
                        .run(URI.create("http://chat.example.test")));

        assertEquals(0, objects.issueCalls);
        assertEquals(0, objects.deleteCalls);
    }

    private static S3AttachmentCapabilityProbe probe(
            FakeObjects objects, ProbeHttpClient http) {
        return new S3AttachmentCapabilityProbe(
                objects,
                objects,
                http,
                Clock.fixed(NOW, ZoneOffset.UTC),
                () -> ID,
                () -> Arrays.copyOf(PAYLOAD, PAYLOAD.length));
    }

    private static ScriptedHttp healthyHttp(FakeObjects objects) {
        ScriptedHttp http = new ScriptedHttp(objects);
        http.preflight = corsPreflight();
        http.puts.add(response(200, corsActual()));
        http.puts.add(response(412, Map.of()));
        return http;
    }

    private static ProbeHttpResponse corsPreflight() {
        return response(204, Map.of(
                "Access-Control-Allow-Origin", List.of(ORIGIN.toString()),
                "Access-Control-Allow-Methods", List.of("GET, PUT"),
                "Access-Control-Allow-Headers", List.of(
                        "content-type, x-amz-checksum-sha256, if-none-match")));
    }

    private static Map<String, List<String>> corsActual() {
        return Map.of("Access-Control-Allow-Origin", List.of(ORIGIN.toString()));
    }

    private static ProbeHttpResponse response(
            int status, Map<String, List<String>> headers) {
        return new ProbeHttpResponse(status, headers);
    }

    private static byte[] sha256(byte[] value) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(value);
        } catch (NoSuchAlgorithmException exception) {
            throw new AssertionError(exception);
        }
    }

    private static final class FakeObjects
            implements AttachmentObjectStorePort, AttachmentObjectDeletionPort {
        private final Map<String, String> headers = Map.of(
                "content-type", "application/octet-stream",
                "x-amz-checksum-sha256", "fixture",
                "if-none-match", "*");
        private AttachmentUploadTarget target;
        private Instant expiresAt;
        private Optional<StoredAttachmentObject> stored = Optional.empty();
        private int issueCalls;
        private int deleteCalls;
        private boolean mismatchChecksum;
        private boolean deleteFailure;
        private boolean retainAfterDelete;

        @Override
        public AttachmentUploadGrant issueCreateOnlyPut(
                AttachmentUploadTarget value, Instant expiry) {
            issueCalls++;
            target = value;
            expiresAt = expiry;
            return new AttachmentUploadGrant(SIGNED_URI, headers, expiry);
        }

        @Override
        public Optional<StoredAttachmentObject> inspectSealedObject(
                AttachmentUploadTarget value) {
            return stored;
        }

        @Override
        public void deleteIfPresent(String objectKey) {
            deleteCalls++;
            if (deleteFailure) {
                throw new RuntimeException(SIGNED_URI.toString());
            }
            if (!retainAfterDelete) {
                stored = Optional.empty();
            }
        }

        private void markUploaded() {
            byte[] checksum = target.contentSha256();
            if (mismatchChecksum) {
                checksum[0] ^= 1;
            }
            stored = Optional.of(new StoredAttachmentObject(
                    target.objectKey(), target.byteSize(), checksum));
        }
    }

    private static final class ScriptedHttp implements ProbeHttpClient {
        private final FakeObjects objects;
        private final Deque<ProbeHttpResponse> puts = new ArrayDeque<>();
        private ProbeHttpResponse preflight;
        private int putCalls;
        private byte[] firstPayload;
        private Map<String, String> firstHeaders;

        private ScriptedHttp(FakeObjects objects) {
            this.objects = objects;
        }

        @Override
        public ProbeHttpResponse preflight(
                URI uploadUri, URI webOrigin, Set<String> requestedHeaders) {
            assertEquals(SIGNED_URI, uploadUri);
            assertEquals(ORIGIN, webOrigin);
            assertEquals(Set.of(
                    "content-type", "x-amz-checksum-sha256", "if-none-match"),
                    requestedHeaders);
            return preflight;
        }

        @Override
        public ProbeHttpResponse put(
                URI uploadUri,
                URI webOrigin,
                Map<String, String> requiredHeaders,
                byte[] payload) {
            putCalls++;
            if (putCalls == 1) {
                firstPayload = Arrays.copyOf(payload, payload.length);
                firstHeaders = Map.copyOf(requiredHeaders);
                objects.markUploaded();
            }
            return puts.removeFirst();
        }
    }
}
