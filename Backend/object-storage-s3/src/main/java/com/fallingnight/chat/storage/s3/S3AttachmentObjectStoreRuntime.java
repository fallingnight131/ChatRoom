package com.fallingnight.chat.storage.s3;

import com.fallingnight.chat.application.attachment.AttachmentObjectDeletionPort;
import com.fallingnight.chat.application.attachment.AttachmentObjectStorePort;
import com.fallingnight.chat.application.attachment.AttachmentUploadGrant;
import com.fallingnight.chat.application.attachment.AttachmentUploadTarget;
import com.fallingnight.chat.application.attachment.StoredAttachmentObject;
import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

/** Owns the SDK resources behind one configured but externally activated adapter. */
public final class S3AttachmentObjectStoreRuntime
        implements AttachmentObjectStorePort, AttachmentObjectDeletionPort, AutoCloseable {
    private final S3Client client;
    private final S3Presigner presigner;
    private final S3AttachmentObjectStore delegate;
    private final S3ProfileImageObjectWriter profileImageWriter;
    private final S3ProfileImageObjectReader profileImageReader;
    private final S3ProfileImageObjectDeleter profileImageDeleter;

    private S3AttachmentObjectStoreRuntime(
            S3Client client,
            S3Presigner presigner,
            S3AttachmentObjectStore delegate,
            String bucket) {
        this.client = client;
        this.presigner = presigner;
        this.delegate = delegate;
        this.profileImageWriter = new S3ProfileImageObjectWriter(client, bucket);
        this.profileImageReader = new S3ProfileImageObjectReader(client, bucket);
        this.profileImageDeleter = new S3ProfileImageObjectDeleter(client, bucket);
    }

    public static S3AttachmentObjectStoreRuntime open(
            S3AttachmentStorageConfig config,
            AwsCredentialsProvider credentials,
            Clock clock) {
        Objects.requireNonNull(config, "config");
        Objects.requireNonNull(credentials, "credentials");
        Objects.requireNonNull(clock, "clock");
        S3Configuration service = S3Configuration.builder()
                .pathStyleAccessEnabled(config.pathStyleAccess())
                .build();
        S3Client client = S3Client.builder()
                .credentialsProvider(credentials)
                .region(config.region())
                .endpointOverride(config.endpoint())
                .serviceConfiguration(service)
                .httpClientBuilder(UrlConnectionHttpClient.builder())
                .build();
        try {
            S3Presigner presigner = S3Presigner.builder()
                    .credentialsProvider(credentials)
                    .region(config.region())
                    .endpointOverride(config.endpoint())
                    .serviceConfiguration(service)
                    .build();
            return new S3AttachmentObjectStoreRuntime(
                    client, presigner,
                    new S3AttachmentObjectStore(
                            client, presigner, config.bucket(), clock),
                    config.bucket());
        } catch (RuntimeException exception) {
            client.close();
            throw exception;
        }
    }

    @Override
    public AttachmentUploadGrant issueCreateOnlyPut(
            AttachmentUploadTarget target, Instant expiresAt) {
        return delegate.issueCreateOnlyPut(target, expiresAt);
    }

    @Override
    public Optional<StoredAttachmentObject> inspectSealedObject(
            AttachmentUploadTarget target) {
        return delegate.inspectSealedObject(target);
    }

    @Override
    public void deleteIfPresent(String objectKey) {
        delegate.deleteIfPresent(objectKey);
    }

    public S3ProfileImageObjectWriter profileImageWriter() { return profileImageWriter; }
    public S3ProfileImageObjectReader profileImageReader() { return profileImageReader; }
    public S3ProfileImageObjectDeleter profileImageDeleter() { return profileImageDeleter; }

    @Override
    public void close() {
        try {
            presigner.close();
        } finally {
            client.close();
        }
    }
}
