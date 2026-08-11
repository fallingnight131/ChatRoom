package com.fallingnight.chat.application.identity;

import com.fallingnight.chat.application.security.SecretBytes;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/** Atomically verifies and rotates a durable device-session proof. */
@FunctionalInterface
public interface SessionResumePort {
    Optional<IssuedSession> resumeAndRotate(
            UUID sessionId,
            SecretBytes presentedToken,
            ClientDescriptor client,
            Instant now);
}
