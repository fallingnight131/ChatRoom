package com.fallingnight.chat.application.attachment;

import java.net.URI;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/** Transient create-only PUT authorization; callers must never persist or log its URI. */
public record AttachmentUploadGrant(
        URI uploadUri,
        Map<String, String> requiredHeaders,
        Instant expiresAt) {
    public AttachmentUploadGrant {
        Objects.requireNonNull(uploadUri, "uploadUri");
        Objects.requireNonNull(requiredHeaders, "requiredHeaders");
        Objects.requireNonNull(expiresAt, "expiresAt");
        if (!"https".equalsIgnoreCase(uploadUri.getScheme()) || uploadUri.getHost() == null
                || uploadUri.getUserInfo() != null || uploadUri.getFragment() != null) {
            throw new IllegalArgumentException("uploadUri must be an absolute HTTPS URI");
        }
        if (requiredHeaders.size() > 32) {
            throw new IllegalArgumentException("too many required upload headers");
        }
        LinkedHashMap<String, String> copied = new LinkedHashMap<>();
        requiredHeaders.forEach((name, value) -> {
            if (name == null || value == null || name.isBlank()
                    || name.length() > 128 || value.length() > 4096
                    || name.codePoints().anyMatch(Character::isISOControl)
                    || value.codePoints().anyMatch(Character::isISOControl)) {
                throw new IllegalArgumentException("required upload header is invalid");
            }
            copied.put(name, value);
        });
        requiredHeaders = Map.copyOf(copied);
    }
}
