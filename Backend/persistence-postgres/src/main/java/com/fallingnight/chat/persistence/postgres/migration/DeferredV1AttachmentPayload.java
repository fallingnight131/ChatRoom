package com.fallingnight.chat.persistence.postgres.migration;

import com.fallingnight.chat.application.compatibility.v1.LegacyV1ConversationKind;

/** Attachment payload identity deferred to the verified attachment import path. */
public record DeferredV1AttachmentPayload(
        LegacyV1ConversationKind legacyKind,
        long legacyConversationId,
        long legacyMessageId,
        long legacyFileId,
        String legacyContentType,
        boolean recalled) { }
