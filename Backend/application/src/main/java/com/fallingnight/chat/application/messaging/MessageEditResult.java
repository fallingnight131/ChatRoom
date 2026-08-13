package com.fallingnight.chat.application.messaging;

import java.time.Instant;
import java.util.Arrays;
import java.util.Objects;
import java.util.UUID;

/** Exact durable edit outcome or an intentionally opaque rejection. */
public sealed interface MessageEditResult {
    record Applied(
            UUID conversationId,
            UUID messageId,
            UUID actorAccountId,
            int contentRevision,
            int contentType,
            byte[] content,
            String clientOperationId,
            boolean changed,
            long conversationSequence,
            Instant occurredAt,
            boolean duplicate) implements MessageEditResult {
        public Applied {
            Objects.requireNonNull(conversationId, "conversationId");
            Objects.requireNonNull(messageId, "messageId");
            Objects.requireNonNull(actorAccountId, "actorAccountId");
            Objects.requireNonNull(content, "content");
            Objects.requireNonNull(clientOperationId, "clientOperationId");
            Objects.requireNonNull(occurredAt, "occurredAt");
            if (contentRevision < 0 || contentRevision > MessageEditCommand.MAX_REVISION) {
                throw new IllegalArgumentException("contentRevision must be 0..100");
            }
            if (contentType != MessageEditCommand.TEXT_UTF8_CONTENT_TYPE) {
                throw new IllegalArgumentException("edit result must contain UTF-8 text");
            }
            if (content.length == 0 || content.length > MessageEditCommand.MAX_CONTENT_BYTES) {
                throw new IllegalArgumentException("content byte length must be 1..65536");
            }
            if (changed != (conversationSequence > 0)) {
                throw new IllegalArgumentException(
                        "changed edits require a positive sequence and no-ops require zero");
            }
            content = Arrays.copyOf(content, content.length);
        }

        @Override
        public byte[] content() {
            return Arrays.copyOf(content, content.length);
        }
    }

    enum Rejected implements MessageEditResult {
        NOT_AUTHORIZED,
        STALE_REVISION,
        WINDOW_EXPIRED,
        REVISION_LIMIT,
        IDEMPOTENCY_CONFLICT
    }
}
