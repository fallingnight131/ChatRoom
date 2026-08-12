package com.fallingnight.chat.application.attachment;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/** Authorized attachment lookup and compare-and-set READY persistence boundary. */
public interface AttachmentLifecyclePort {
    Optional<RegisteredAttachment> findAuthorized(UUID attachmentId, AttachmentActor actor);

    AttachmentReadyTransition markReadyIfAuthorized(
            UUID attachmentId, AttachmentActor actor, Instant readyAt);
}
