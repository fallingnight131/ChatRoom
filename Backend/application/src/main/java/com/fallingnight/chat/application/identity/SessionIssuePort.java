package com.fallingnight.chat.application.identity;

import java.time.Instant;
import java.util.Optional;

/** Persists a device/session and returns a newly generated raw token once. */
@FunctionalInterface
public interface SessionIssuePort {
    Optional<IssuedSession> issue(
            AccountCredential account, ClientDescriptor client, Instant now);
}
