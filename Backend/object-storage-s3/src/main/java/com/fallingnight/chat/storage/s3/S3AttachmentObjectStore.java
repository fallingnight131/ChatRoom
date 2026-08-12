package com.fallingnight.chat.storage.s3;

import com.fallingnight.chat.application.attachment.AttachmentObjectStorePort;
import com.fallingnight.chat.application.attachment.AttachmentUploadGrant;
import com.fallingnight.chat.application.attachment.AttachmentUploadTarget;
import com.fallingnight.chat.application.attachment.StoredAttachmentObject;
import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.ChecksumMode;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectResponse;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.PutObjectPresignRequest;
import software.amazon.awssdk.services.s3.presigner.model.PresignedPutObjectRequest;

/** S3-compatible create-only PUT signer and checksum-backed object inspector. */
public final class S3AttachmentObjectStore implements AttachmentObjectStorePort {
    public static final long SINGLE_PUT_MAX_BYTE_SIZE = 5L * 1024 * 1024 * 1024;

    private final HeadReader heads;
    private final PutSigner signer;
    private final String bucket;
    private final Clock clock;

    public S3AttachmentObjectStore(
            S3Client client, S3Presigner presigner, String bucket, Clock clock) {
        this(client::headObject, request -> presigner.presignPutObject(request), bucket, clock);
        Objects.requireNonNull(client, "client");
        Objects.requireNonNull(presigner, "presigner");
    }

    S3AttachmentObjectStore(
            HeadReader heads, PutSigner signer, String bucket, Clock clock) {
        this.heads = Objects.requireNonNull(heads, "heads");
        this.signer = Objects.requireNonNull(signer, "signer");
        this.bucket = requireBucket(bucket);
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Override
    public AttachmentUploadGrant issueCreateOnlyPut(
            AttachmentUploadTarget target, Instant expiresAt) {
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(expiresAt, "expiresAt");
        if (target.byteSize() > SINGLE_PUT_MAX_BYTE_SIZE) {
            throw new IllegalArgumentException(
                    "attachment requires a reviewed multipart upload flow");
        }
        Duration lifetime = Duration.between(clock.instant(), expiresAt);
        if (lifetime.isZero() || lifetime.isNegative()
                || lifetime.compareTo(Duration.ofMinutes(10)) > 0) {
            throw new IllegalArgumentException("S3 grant lifetime must be in (0, 10 minutes]");
        }
        String checksum = Base64.getEncoder().encodeToString(target.contentSha256());
        PutObjectRequest put = PutObjectRequest.builder()
                .bucket(bucket)
                .key(target.objectKey())
                .contentType(target.mediaType())
                .contentLength(target.byteSize())
                .checksumSHA256(checksum)
                .ifNoneMatch("*")
                .build();
        PresignedPutObjectRequest signed;
        try {
            signed = signer.sign(PutObjectPresignRequest.builder()
                    .signatureDuration(lifetime)
                    .putObjectRequest(put)
                    .build());
        } catch (RuntimeException exception) {
            throw new AttachmentObjectStoreException("S3 PUT signing failed", exception);
        }
        Map<String, String> headers = clientHeaders(signed.signedHeaders());
        requireSigned(headers, "content-type", target.mediaType());
        requireSigned(headers, "x-amz-checksum-sha256", checksum);
        requireSigned(headers, "if-none-match", "*");
        URI uri = URI.create(signed.url().toString());
        return new AttachmentUploadGrant(uri, headers, expiresAt);
    }

    @Override
    public Optional<StoredAttachmentObject> inspectSealedObject(
            AttachmentUploadTarget target) {
        Objects.requireNonNull(target, "target");
        HeadObjectResponse response;
        try {
            response = heads.head(HeadObjectRequest.builder()
                    .bucket(bucket)
                    .key(target.objectKey())
                    .checksumMode(ChecksumMode.ENABLED)
                    .build());
        } catch (NoSuchKeyException exception) {
            return Optional.empty();
        } catch (S3Exception exception) {
            if (exception.statusCode() == 404) {
                return Optional.empty();
            }
            throw new AttachmentObjectStoreException("S3 HEAD failed", exception);
        } catch (RuntimeException exception) {
            throw new AttachmentObjectStoreException("S3 HEAD failed", exception);
        }
        byte[] checksum = decodeChecksum(response.checksumSHA256());
        Long length = response.contentLength();
        if (length == null || length < 0) {
            throw new AttachmentObjectStoreException("S3 HEAD omitted content length");
        }
        return Optional.of(new StoredAttachmentObject(
                target.objectKey(), length, checksum));
    }

    private static Map<String, String> clientHeaders(Map<String, List<String>> signed) {
        LinkedHashMap<String, String> result = new LinkedHashMap<>();
        signed.forEach((rawName, values) -> {
            String name = rawName.toLowerCase(Locale.ROOT);
            if (name.equals("host") || name.equals("content-length")) {
                return;
            }
            if (values.size() != 1) {
                throw new AttachmentObjectStoreException(
                        "S3 signer returned a multi-value required header");
            }
            result.put(name, values.getFirst());
        });
        return Map.copyOf(result);
    }

    private static void requireSigned(
            Map<String, String> headers, String name, String expected) {
        if (!expected.equals(headers.get(name))) {
            throw new AttachmentObjectStoreException(
                    "S3 signer omitted an attachment integrity constraint");
        }
    }

    private static byte[] decodeChecksum(String value) {
        if (value == null) {
            throw new AttachmentObjectStoreException("S3 HEAD omitted SHA-256");
        }
        try {
            byte[] decoded = Base64.getDecoder().decode(value);
            if (decoded.length != 32) {
                throw new AttachmentObjectStoreException("S3 HEAD returned invalid SHA-256");
            }
            return decoded;
        } catch (IllegalArgumentException exception) {
            throw new AttachmentObjectStoreException(
                    "S3 HEAD returned invalid SHA-256", exception);
        }
    }

    private static String requireBucket(String value) {
        Objects.requireNonNull(value, "bucket");
        if (value.isBlank() || value.length() > 255
                || value.codePoints().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException("bucket is invalid");
        }
        return value;
    }

    @FunctionalInterface
    interface HeadReader {
        HeadObjectResponse head(HeadObjectRequest request);
    }

    @FunctionalInterface
    interface PutSigner {
        PresignedPutObjectRequest sign(PutObjectPresignRequest request);
    }
}
