package com.fallingnight.chat.protocol.v2;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

/** Fail-closed bounds for inactive V2 attachment commands and responses. */
public final class AttachmentPayloadPolicy {
    public static final long MAX_BYTE_SIZE = 10L * 1024 * 1024 * 1024;
    private static final int MAX_HEADERS = 32;

    private AttachmentPayloadPolicy() {
    }

    public static void requireValid(RegisterAttachment value) {
        requireUuid(value.getConversationId());
        requireText(value.getClientAttachmentId(), 128);
        requireText(value.getFileName(), 255);
        if (value.getFileName().equals(".") || value.getFileName().equals("..")
                || value.getFileName().contains("/") || value.getFileName().contains("\\")) {
            throw invalid();
        }
        requireText(value.getMediaType(), 127);
        if (!value.getMediaType().equals(value.getMediaType().toLowerCase(Locale.ROOT))
                || !value.getMediaType().matches(
                        "[a-z0-9][a-z0-9!#$&^_.+-]*/[a-z0-9][a-z0-9!#$&^_.+-]*")) {
            throw invalid();
        }
        if (value.getByteSize() < 1 || value.getByteSize() > MAX_BYTE_SIZE
                || value.getContentSha256().size() != 32) {
            throw invalid();
        }
    }

    public static void requireValid(AttachmentRegistered value) {
        requireUuid(value.getAttachmentId());
        requireUuid(value.getConversationId());
        requireText(value.getClientAttachmentId(), 128);
    }

    public static void requireValid(AuthorizeAttachmentUpload value) {
        requireUuid(value.getAttachmentId());
    }

    public static void requireValid(AttachmentUploadAuthorized value) {
        requireUuid(value.getAttachmentId());
        URI uri;
        try {
            uri = URI.create(value.getUploadUri());
        } catch (IllegalArgumentException exception) {
            throw invalid();
        }
        if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null
                || uri.getUserInfo() != null || uri.getFragment() != null
                || value.getExpiresAtEpochMs() <= 0
                || value.getRequiredHeadersCount() < 1
                || value.getRequiredHeadersCount() > MAX_HEADERS) {
            throw invalid();
        }
        Set<String> names = new HashSet<>();
        for (RequiredUploadHeader header : value.getRequiredHeadersList()) {
            requireText(header.getName(), 128);
            requireText(header.getValue(), 4096);
            String canonical = header.getName().toLowerCase(Locale.ROOT);
            if (!header.getName().equals(canonical) || !names.add(canonical)
                    || canonical.equals("host") || canonical.equals("content-length")) {
                throw invalid();
            }
        }
    }

    public static void requireValid(CompleteAttachmentUpload value) {
        requireUuid(value.getAttachmentId());
    }

    public static void requireValid(AttachmentReady value) {
        requireUuid(value.getAttachmentId());
        requireUuid(value.getConversationId());
        if (value.getReadyAtEpochMs() <= 0) {
            throw invalid();
        }
    }

    private static void requireUuid(String value) {
        try {
            if (!UUID.fromString(value).toString().equals(value)) {
                throw invalid();
            }
        } catch (IllegalArgumentException exception) {
            throw invalid();
        }
    }

    private static void requireText(String value, int maximumBytes) {
        if (value.isBlank() || value.getBytes(StandardCharsets.UTF_8).length > maximumBytes
                || value.codePoints().anyMatch(Character::isISOControl)) {
            throw invalid();
        }
    }

    private static IllegalArgumentException invalid() {
        return new IllegalArgumentException("invalid V2 attachment payload");
    }
}
