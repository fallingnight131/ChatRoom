package com.fallingnight.chat.storage.s3;

import com.fallingnight.chat.application.attachment.AttachmentObjectDeletionPort;
import com.fallingnight.chat.application.attachment.AttachmentObjectStorePort;
import com.fallingnight.chat.application.attachment.AttachmentUploadGrant;
import com.fallingnight.chat.application.attachment.AttachmentUploadTarget;
import com.fallingnight.chat.application.attachment.StoredAttachmentObject;
import java.net.URI;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Creates one random, non-durable test object and proves the provider behavior
 * required by the inactive attachment path. The object is always cleaned up.
 */
public final class S3AttachmentCapabilityProbe {
    static final Duration GRANT_LIFETIME = Duration.ofMinutes(2);
    private static final String MEDIA_TYPE = "application/octet-stream";

    private final AttachmentObjectStorePort objects;
    private final AttachmentObjectDeletionPort deletions;
    private final ProbeHttpClient http;
    private final Clock clock;
    private final Supplier<UUID> identifiers;
    private final Supplier<byte[]> payloads;

    public S3AttachmentCapabilityProbe(
            AttachmentObjectStorePort objects,
            AttachmentObjectDeletionPort deletions,
            ProbeHttpClient http,
            Clock clock,
            Supplier<UUID> identifiers,
            Supplier<byte[]> payloads) {
        this.objects = Objects.requireNonNull(objects, "objects");
        this.deletions = Objects.requireNonNull(deletions, "deletions");
        this.http = Objects.requireNonNull(http, "http");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.identifiers = Objects.requireNonNull(identifiers, "identifiers");
        this.payloads = Objects.requireNonNull(payloads, "payloads");
    }

    public CapabilityReport run(URI webOrigin) {
        requireWebOrigin(webOrigin);
        UUID identifier = Objects.requireNonNull(
                identifiers.get(), "identifier supplier returned null");
        byte[] payload = requirePayload(payloads.get());
        AttachmentUploadTarget target = new AttachmentUploadTarget(
                "attachments/capability-probe-" + identifier,
                MEDIA_TYPE,
                payload.length,
                sha256(payload));
        AttachmentObjectStoreCapabilityProbeException failure = null;
        CapabilityReport report = null;
        try {
            report = execute(target, payload, webOrigin);
        } catch (RuntimeException exception) {
            failure = normalize("attachment object-store capability probe failed", exception);
        }

        try {
            deletions.deleteIfPresent(target.objectKey());
            if (objects.inspectSealedObject(target).isPresent()) {
                throw new AttachmentObjectStoreCapabilityProbeException(
                        "attachment object-store cleanup verification failed");
            }
        } catch (RuntimeException exception) {
            AttachmentObjectStoreCapabilityProbeException cleanup = normalize(
                    "attachment object-store cleanup failed", exception);
            if (failure == null) {
                failure = cleanup;
            } else {
                failure.addSuppressed(cleanup);
            }
        }

        if (failure != null) {
            throw failure;
        }
        return Objects.requireNonNull(report, "capability report");
    }

    private CapabilityReport execute(
            AttachmentUploadTarget target, byte[] payload, URI webOrigin) {
        AttachmentUploadGrant grant;
        try {
            grant = objects.issueCreateOnlyPut(
                    target, clock.instant().plus(GRANT_LIFETIME));
        } catch (RuntimeException exception) {
            throw normalize("attachment upload authorization probe failed", exception);
        }

        Set<String> requestedHeaders = normalizedNames(grant.requiredHeaders().keySet());
        ProbeHttpResponse preflight = http.preflight(
                grant.uploadUri(), webOrigin, requestedHeaders);
        requireSuccess(preflight, "attachment Web CORS preflight was rejected");
        requireCors(preflight, webOrigin, requestedHeaders, true);

        ProbeHttpResponse firstPut = http.put(
                grant.uploadUri(), webOrigin, grant.requiredHeaders(), payload);
        requireSuccess(firstPut, "attachment create-only PUT was rejected");
        requireCors(firstPut, webOrigin, Set.of(), false);

        ProbeHttpResponse repeatedPut = http.put(
                grant.uploadUri(), webOrigin, grant.requiredHeaders(), payload);
        if (repeatedPut.statusCode() != 409 && repeatedPut.statusCode() != 412) {
            throw new AttachmentObjectStoreCapabilityProbeException(
                    "attachment provider accepted a create-only PUT replay");
        }

        Optional<StoredAttachmentObject> inspected;
        try {
            inspected = objects.inspectSealedObject(target);
        } catch (RuntimeException exception) {
            throw normalize("attachment checksum inspection probe failed", exception);
        }
        StoredAttachmentObject stored = inspected.orElseThrow(() ->
                new AttachmentObjectStoreCapabilityProbeException(
                        "attachment provider omitted the uploaded probe object"));
        if (!target.objectKey().equals(stored.objectKey())
                || target.byteSize() != stored.byteSize()
                || !MessageDigest.isEqual(
                        target.contentSha256(), stored.contentSha256())) {
            throw new AttachmentObjectStoreCapabilityProbeException(
                    "attachment provider returned mismatched sealed-object metadata");
        }
        return new CapabilityReport(true, true, true, true);
    }

    private static void requireSuccess(ProbeHttpResponse response, String message) {
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new AttachmentObjectStoreCapabilityProbeException(message);
        }
    }

    private static void requireCors(
            ProbeHttpResponse response,
            URI origin,
            Set<String> requestedHeaders,
            boolean requirePutMethod) {
        Set<String> allowedOrigins = tokens(response.headers("access-control-allow-origin"));
        if (!allowedOrigins.contains("*") && !allowedOrigins.contains(origin.toString())) {
            throw new AttachmentObjectStoreCapabilityProbeException(
                    "attachment provider omitted the required Web CORS origin");
        }
        if (requirePutMethod
                && !tokens(response.headers("access-control-allow-methods")).contains("PUT")) {
            throw new AttachmentObjectStoreCapabilityProbeException(
                    "attachment provider omitted PUT from Web CORS methods");
        }
        if (!requestedHeaders.isEmpty()) {
            Set<String> allowedHeaders = lowerTokens(
                    response.headers("access-control-allow-headers"));
            if (!allowedHeaders.contains("*") && !allowedHeaders.containsAll(requestedHeaders)) {
                throw new AttachmentObjectStoreCapabilityProbeException(
                        "attachment provider omitted signed Web CORS headers");
            }
        }
    }

    private static Set<String> tokens(List<String> values) {
        LinkedHashSet<String> result = new LinkedHashSet<>();
        for (String value : values) {
            for (String token : value.split(",")) {
                if (!token.isBlank()) {
                    result.add(token.trim());
                }
            }
        }
        return Set.copyOf(result);
    }

    private static Set<String> lowerTokens(List<String> values) {
        LinkedHashSet<String> result = new LinkedHashSet<>();
        for (String token : tokens(values)) {
            result.add(token.toLowerCase(Locale.ROOT));
        }
        return Set.copyOf(result);
    }

    private static Set<String> normalizedNames(Set<String> names) {
        LinkedHashSet<String> result = new LinkedHashSet<>();
        for (String name : names) {
            result.add(name.toLowerCase(Locale.ROOT));
        }
        return Set.copyOf(result);
    }

    private static AttachmentObjectStoreCapabilityProbeException normalize(
            String message, RuntimeException exception) {
        if (exception instanceof AttachmentObjectStoreCapabilityProbeException safe) {
            return safe;
        }
        return new AttachmentObjectStoreCapabilityProbeException(message);
    }

    private static byte[] requirePayload(byte[] value) {
        Objects.requireNonNull(value, "payload supplier returned null");
        if (value.length < 1 || value.length > 4096) {
            throw new IllegalArgumentException("probe payload length is invalid");
        }
        return Arrays.copyOf(value, value.length);
    }

    private static byte[] sha256(byte[] value) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(value);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    static void requireWebOrigin(URI origin) {
        Objects.requireNonNull(origin, "webOrigin");
        if (!"https".equalsIgnoreCase(origin.getScheme())
                || origin.getHost() == null
                || origin.getUserInfo() != null
                || origin.getQuery() != null
                || origin.getFragment() != null
                || !origin.getPath().isEmpty()) {
            throw new IllegalArgumentException("probe Web origin must be an HTTPS origin");
        }
    }

    /** Fixed, non-identifying success evidence safe to print or retain. */
    public record CapabilityReport(
            boolean corsAccepted,
            boolean firstPutAccepted,
            boolean replayRejected,
            boolean checksumVerified) {
    }

    public interface ProbeHttpClient {
        ProbeHttpResponse preflight(
                URI uploadUri, URI webOrigin, Set<String> requestedHeaders);

        ProbeHttpResponse put(
                URI uploadUri,
                URI webOrigin,
                Map<String, String> requiredHeaders,
                byte[] payload);
    }

    /** Immutable, case-insensitive subset of a provider HTTP response. */
    public record ProbeHttpResponse(int statusCode, Map<String, List<String>> headers) {
        public ProbeHttpResponse {
            if (statusCode < 100 || statusCode > 599) {
                throw new IllegalArgumentException("HTTP status is invalid");
            }
            Objects.requireNonNull(headers, "headers");
            LinkedHashMap<String, List<String>> copied = new LinkedHashMap<>();
            headers.forEach((name, values) -> {
                Objects.requireNonNull(name, "header name");
                Objects.requireNonNull(values, "header values");
                copied.put(name.toLowerCase(Locale.ROOT), List.copyOf(values));
            });
            headers = Map.copyOf(copied);
        }

        public List<String> headers(String name) {
            return headers.getOrDefault(name.toLowerCase(Locale.ROOT), List.of());
        }
    }
}
