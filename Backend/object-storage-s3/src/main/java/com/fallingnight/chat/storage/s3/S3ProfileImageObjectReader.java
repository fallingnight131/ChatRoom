package com.fallingnight.chat.storage.s3;

import com.fallingnight.chat.application.profile.ProfileImageObjectEvidence;
import com.fallingnight.chat.application.profile.ProfileImageObjectPayload;
import com.fallingnight.chat.application.profile.ProfileImageObjectReadPort;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.Objects;
import java.util.Optional;
import software.amazon.awssdk.core.ResponseBytes;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.ChecksumMode;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.S3Exception;

/** Private, checksum-bound profile image reader; it never creates a public URL. */
public final class S3ProfileImageObjectReader implements ProfileImageObjectReadPort {
    private final GetReader reader;
    private final String bucket;

    public S3ProfileImageObjectReader(S3Client client, String bucket) {
        this(client::getObjectAsBytes, bucket);
        Objects.requireNonNull(client, "client");
    }

    S3ProfileImageObjectReader(GetReader reader, String bucket) {
        this.reader = Objects.requireNonNull(reader, "reader");
        this.bucket = requireBucket(bucket);
    }

    @Override public Optional<ProfileImageObjectPayload> read(
            ProfileImageObjectEvidence evidence) {
        Objects.requireNonNull(evidence, "evidence");
        ResponseBytes<GetObjectResponse> downloaded;
        try {
            downloaded = reader.get(GetObjectRequest.builder()
                    .bucket(bucket)
                    .key(evidence.objectKey())
                    .checksumMode(ChecksumMode.ENABLED)
                    .build());
        } catch (NoSuchKeyException exception) {
            return Optional.empty();
        } catch (S3Exception exception) {
            if (exception.statusCode() == 404) return Optional.empty();
            throw new S3ProfileImageObjectReadException("S3 profile image GET failed",
                    exception);
        } catch (RuntimeException exception) {
            throw new S3ProfileImageObjectReadException("S3 profile image GET failed",
                    exception);
        }

        GetObjectResponse response = downloaded.response();
        byte[] bytes = downloaded.asByteArray();
        Long contentLength = response.contentLength();
        if (contentLength == null || contentLength != evidence.byteSize()
                || bytes.length != evidence.byteSize())
            throw new S3ProfileImageObjectReadException(
                    "S3 profile image length does not match durable evidence");
        if (!evidence.mediaType().equals(response.contentType()))
            throw new S3ProfileImageObjectReadException(
                    "S3 profile image media type does not match durable evidence");
        byte[] checksum = decodeChecksum(response.checksumSHA256());
        if (!MessageDigest.isEqual(checksum, evidence.contentSha256()))
            throw new S3ProfileImageObjectReadException(
                    "S3 profile image checksum does not match durable evidence");
        try {
            return Optional.of(ProfileImageObjectPayload.copyOf(bytes));
        } finally {
            java.util.Arrays.fill(bytes, (byte) 0);
        }
    }

    private static byte[] decodeChecksum(String value) {
        if (value == null)
            throw new S3ProfileImageObjectReadException(
                    "S3 profile image GET omitted SHA-256");
        try {
            byte[] decoded = Base64.getDecoder().decode(value);
            if (decoded.length != 32)
                throw new S3ProfileImageObjectReadException(
                        "S3 profile image GET returned invalid SHA-256");
            return decoded;
        } catch (IllegalArgumentException exception) {
            throw new S3ProfileImageObjectReadException(
                    "S3 profile image GET returned invalid SHA-256", exception);
        }
    }

    private static String requireBucket(String value) {
        Objects.requireNonNull(value, "bucket");
        if (value.isBlank() || value.length() > 255
                || value.codePoints().anyMatch(Character::isISOControl))
            throw new IllegalArgumentException("bucket is invalid");
        return value;
    }

    @FunctionalInterface
    interface GetReader {
        ResponseBytes<GetObjectResponse> get(GetObjectRequest request);
    }
}
