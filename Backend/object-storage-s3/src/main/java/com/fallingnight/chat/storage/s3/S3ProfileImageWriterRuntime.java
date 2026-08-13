package com.fallingnight.chat.storage.s3;

import java.time.Clock;
import java.util.Objects;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;

/** Owns default-chain credentials and SDK resources for one offline import. */
public final class S3ProfileImageWriterRuntime implements AutoCloseable {
    private final DefaultCredentialsProvider credentials;
    private final S3AttachmentObjectStoreRuntime runtime;

    private S3ProfileImageWriterRuntime(DefaultCredentialsProvider credentials,
            S3AttachmentObjectStoreRuntime runtime) {
        this.credentials = credentials; this.runtime = runtime;
    }

    public static S3ProfileImageWriterRuntime open(
            S3AttachmentStorageConfig config, Clock clock) {
        Objects.requireNonNull(config, "config"); Objects.requireNonNull(clock, "clock");
        DefaultCredentialsProvider credentials =
                DefaultCredentialsProvider.builder().build();
        try {
            return new S3ProfileImageWriterRuntime(credentials,
                    S3AttachmentObjectStoreRuntime.open(config, credentials, clock));
        } catch (RuntimeException exception) {
            credentials.close(); throw exception;
        }
    }

    public S3ProfileImageObjectWriter writer() { return runtime.profileImageWriter(); }

    @Override public void close() {
        try { runtime.close(); } finally { credentials.close(); }
    }
}
