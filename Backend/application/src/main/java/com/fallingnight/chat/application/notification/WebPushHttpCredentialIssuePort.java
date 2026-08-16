package com.fallingnight.chat.application.notification;

import com.fallingnight.chat.application.identity.AuthenticatedDeviceActor;
import java.time.Instant;
import java.util.Optional;

/** Atomically validates the current session and replaces its short-lived HTTP credential. */
@FunctionalInterface
public interface WebPushHttpCredentialIssuePort {
    Optional<IssuedWebPushHttpCredential> issue(
            AuthenticatedDeviceActor actor, Instant observedAt);
}
