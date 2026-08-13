package com.fallingnight.chat.storage.s3;

import com.fallingnight.chat.application.profile.*;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Base64;
import java.util.Objects;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.*;

/** Checksum-bound create-only writer for immutable private profile images. */
public final class S3ProfileImageObjectWriter implements ProfileImageObjectWritePort {
    private final PutWriter puts;
    private final HeadReader heads;
    private final String bucket;

    public S3ProfileImageObjectWriter(S3Client client, String bucket) {
        this(client::putObject, client::headObject, bucket);
        Objects.requireNonNull(client, "client");
    }

    S3ProfileImageObjectWriter(PutWriter puts, HeadReader heads, String bucket) {
        this.puts = Objects.requireNonNull(puts, "puts");
        this.heads = Objects.requireNonNull(heads, "heads");
        this.bucket = requireBucket(bucket);
    }

    @Override public ProfileImageObjectWriteResult storeIfAbsent(CanonicalProfileImage image) {
        Objects.requireNonNull(image, "image");
        byte[] digest = image.contentSha256();
        byte[] bytes = image.pngBytes();
        ProfileImageObjectEvidence evidence = new ProfileImageObjectEvidence(
                ProfileImageObjectEvidence.objectKey(digest), bytes.length, digest, "image/png");
        String checksum = Base64.getEncoder().encodeToString(digest);
        PutObjectRequest request = PutObjectRequest.builder()
                .bucket(bucket)
                .key(evidence.objectKey())
                .contentType(evidence.mediaType())
                .contentLength(evidence.byteSize())
                .checksumSHA256(checksum)
                .ifNoneMatch("*")
                .build();
        try {
            PutObjectResponse response = puts.put(request, RequestBody.fromBytes(bytes));
            requireChecksum(response.checksumSHA256(), digest, "S3 profile image PUT");
            return new ProfileImageObjectWriteResult(evidence, true);
        } catch (S3Exception exception) {
            if (exception.statusCode() == 412) return inspectExisting(evidence);
            throw new S3ProfileImageObjectWriteException("S3 profile image PUT failed",
                    exception);
        } catch (RuntimeException exception) {
            throw new S3ProfileImageObjectWriteException("S3 profile image PUT failed",
                    exception);
        } finally {
            Arrays.fill(bytes, (byte) 0); Arrays.fill(digest, (byte) 0);
        }
    }

    private ProfileImageObjectWriteResult inspectExisting(ProfileImageObjectEvidence evidence) {
        HeadObjectResponse response;
        try {
            response = heads.head(HeadObjectRequest.builder()
                    .bucket(bucket)
                    .key(evidence.objectKey())
                    .checksumMode(ChecksumMode.ENABLED)
                    .build());
        } catch (RuntimeException exception) {
            throw new S3ProfileImageObjectWriteException(
                    "S3 profile image conflict inspection failed", exception);
        }
        Long length = response.contentLength();
        if (length == null || length != evidence.byteSize()
                || !evidence.mediaType().equals(response.contentType()))
            throw new S3ProfileImageObjectWriteException(
                    "existing S3 profile image metadata does not match canonical bytes");
        requireChecksum(response.checksumSHA256(), evidence.contentSha256(),
                "existing S3 profile image");
        return new ProfileImageObjectWriteResult(evidence, false);
    }

    private static void requireChecksum(String encoded, byte[] expected, String operation) {
        if (encoded == null)
            throw new S3ProfileImageObjectWriteException(operation + " omitted SHA-256");
        try {
            byte[] actual = Base64.getDecoder().decode(encoded);
            if (actual.length != 32 || !MessageDigest.isEqual(actual, expected))
                throw new S3ProfileImageObjectWriteException(
                        operation + " returned mismatched SHA-256");
        } catch (IllegalArgumentException exception) {
            throw new S3ProfileImageObjectWriteException(
                    operation + " returned invalid SHA-256", exception);
        }
    }

    private static String requireBucket(String value) {
        Objects.requireNonNull(value, "bucket");
        if (value.isBlank() || value.length() > 255
                || value.codePoints().anyMatch(Character::isISOControl))
            throw new IllegalArgumentException("bucket is invalid");
        return value;
    }

    @FunctionalInterface interface PutWriter {
        PutObjectResponse put(PutObjectRequest request, RequestBody body);
    }
    @FunctionalInterface interface HeadReader {
        HeadObjectResponse head(HeadObjectRequest request);
    }
}
