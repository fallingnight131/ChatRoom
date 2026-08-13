package com.fallingnight.chat.storage.s3;

import com.fallingnight.chat.application.profile.ProfileImageObjectDeletionPort;
import java.util.Objects;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.DeleteObjectResponse;
import software.amazon.awssdk.services.s3.model.S3Exception;

/** Idempotent deletion of exact private content-addressed profile image keys. */
public final class S3ProfileImageObjectDeleter implements ProfileImageObjectDeletionPort {
    private final DeleteWriter deletes;
    private final String bucket;

    public S3ProfileImageObjectDeleter(S3Client client, String bucket) {
        this(client::deleteObject, bucket); Objects.requireNonNull(client, "client");
    }

    S3ProfileImageObjectDeleter(DeleteWriter deletes, String bucket) {
        this.deletes = Objects.requireNonNull(deletes, "deletes");
        this.bucket = requireBucket(bucket);
    }

    @Override public void deleteIfPresent(String objectKey) {
        requireObjectKey(objectKey);
        try {
            deletes.delete(DeleteObjectRequest.builder()
                    .bucket(bucket).key(objectKey).build());
        } catch (S3Exception exception) {
            if (exception.statusCode() == 404) return;
            throw new S3ProfileImageObjectDeletionException(
                    "S3 profile image DELETE failed", exception);
        } catch (RuntimeException exception) {
            throw new S3ProfileImageObjectDeletionException(
                    "S3 profile image DELETE failed", exception);
        }
    }

    private static String requireBucket(String value) {
        Objects.requireNonNull(value, "bucket");
        if (value.isBlank() || value.length() > 255
                || value.codePoints().anyMatch(Character::isISOControl))
            throw new IllegalArgumentException("bucket is invalid");
        return value;
    }

    private static void requireObjectKey(String value) {
        Objects.requireNonNull(value, "objectKey");
        String prefix = "avatars/sha256/";
        String digest = value.length() == prefix.length() + 64 + 4
                ? value.substring(prefix.length(), prefix.length() + 64) : "";
        if (!value.startsWith(prefix) || !value.endsWith(".png")
                || digest.length() != 64
                || !digest.chars().allMatch(character -> character >= '0' && character <= '9'
                    || character >= 'a' && character <= 'f'))
            throw new IllegalArgumentException("profile image object key is invalid");
    }

    @FunctionalInterface interface DeleteWriter {
        DeleteObjectResponse delete(DeleteObjectRequest request);
    }
}
