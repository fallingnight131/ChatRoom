package com.fallingnight.chat.application.attachment;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;

/** Authenticated, transport-neutral intent to reserve one attachment object. */
public record AttachmentRegistration(
        UUID conversationId,
        UUID ownerAccountId,
        UUID ownerDeviceId,
        String clientAttachmentId,
        String fileName,
        String mediaType,
        long byteSize,
        byte[] contentSha256) {
    public static final long MAX_BYTE_SIZE = 10L * 1024 * 1024 * 1024;

    public AttachmentRegistration {
        Objects.requireNonNull(conversationId, "conversationId");
        Objects.requireNonNull(ownerAccountId, "ownerAccountId");
        Objects.requireNonNull(ownerDeviceId, "ownerDeviceId");
        clientAttachmentId = requireIdentifier(clientAttachmentId, "clientAttachmentId", 128);
        fileName = requireFileName(fileName);
        mediaType = requireMediaType(mediaType);
        Objects.requireNonNull(contentSha256, "contentSha256");
        if (byteSize < 1 || byteSize > MAX_BYTE_SIZE) {
            throw new IllegalArgumentException("byteSize must be in 1..10737418240");
        }
        if (contentSha256.length != 32) {
            throw new IllegalArgumentException("contentSha256 must contain 32 bytes");
        }
        contentSha256 = Arrays.copyOf(contentSha256, contentSha256.length);
    }

    @Override
    public byte[] contentSha256() {
        return Arrays.copyOf(contentSha256, contentSha256.length);
    }

    private static String requireIdentifier(String value, String field, int maxBytes) {
        Objects.requireNonNull(value, field);
        if (value.isBlank()
                || value.getBytes(StandardCharsets.UTF_8).length > maxBytes
                || value.codePoints().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException(field + " is invalid");
        }
        return value;
    }

    private static String requireFileName(String value) {
        String checked = requireIdentifier(value, "fileName", 255);
        if (checked.equals(".") || checked.equals("..")
                || checked.indexOf('/') >= 0 || checked.indexOf('\\') >= 0) {
            throw new IllegalArgumentException("fileName must be a basename");
        }
        return checked;
    }

    private static String requireMediaType(String value) {
        Objects.requireNonNull(value, "mediaType");
        String canonical = value.trim().toLowerCase(Locale.ROOT);
        if (canonical.codePoints().anyMatch(codePoint -> codePoint > 0x7f)
                || canonical.length() > 127
                || !canonical.matches("[a-z0-9][a-z0-9!#$&^_.+-]*/[a-z0-9][a-z0-9!#$&^_.+-]*")) {
            throw new IllegalArgumentException("mediaType must be a bounded MIME type");
        }
        return canonical;
    }
}
