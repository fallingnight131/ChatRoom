package com.fallingnight.chat.application.attachment;

import java.time.Instant;
import java.util.Optional;

/** Create-only object authorization and trusted completion metadata boundary. */
public interface AttachmentObjectStorePort {
    AttachmentUploadGrant issueCreateOnlyPut(
            AttachmentUploadTarget target, Instant expiresAt);

    Optional<StoredAttachmentObject> inspectSealedObject(AttachmentUploadTarget target);
}
