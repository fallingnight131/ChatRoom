package com.fallingnight.chat.application.identity;

import com.fallingnight.chat.application.security.SecretBytes;
import java.util.Objects;
import java.util.UUID;

/** One session-resume attempt; closing it destroys its owned token bytes. */
public final class ResumeSessionCommand implements AutoCloseable {
    public static final int RESUME_TOKEN_BYTES = 32;
    private final UUID sessionId;
    private final ClientDescriptor client;
    private final SecretBytes resumeToken;

    public ResumeSessionCommand(UUID sessionId, byte[] resumeToken, ClientDescriptor client) {
        this.sessionId = Objects.requireNonNull(sessionId, "sessionId");
        this.client = Objects.requireNonNull(client, "client");
        Objects.requireNonNull(resumeToken, "resumeToken");
        if (resumeToken.length != RESUME_TOKEN_BYTES) {
            throw new IllegalArgumentException("resumeToken must contain exactly 32 bytes");
        }
        this.resumeToken = SecretBytes.copyOf(resumeToken);
    }

    public UUID sessionId() {
        return sessionId;
    }

    public ClientDescriptor client() {
        return client;
    }

    SecretBytes resumeToken() {
        return resumeToken;
    }

    public boolean isClosed() {
        return resumeToken.isClosed();
    }

    @Override
    public void close() {
        resumeToken.close();
    }
}
