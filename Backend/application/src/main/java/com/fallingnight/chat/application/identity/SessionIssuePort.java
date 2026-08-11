package com.fallingnight.chat.application.identity;

import java.time.Instant;

/** Persists a device/session and returns a newly generated raw token once. */
@FunctionalInterface
public interface SessionIssuePort {
    IssuedSession issue(AccountCredential account, ClientDescriptor client, Instant now);
}
