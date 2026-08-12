package com.fallingnight.chat.application.compatibility.v1;

/** Exact durable limits exposed by the V1 room-settings read response. */
public record LegacyV1RoomSettings(
        long maxFileSize, long totalFileSpace, int maxFileCount, int maxMembers) {
    public static final long MAX_JSON_SAFE_INTEGER = 9_007_199_254_740_991L;

    public LegacyV1RoomSettings {
        if (maxFileSize < 1 || maxFileSize > MAX_JSON_SAFE_INTEGER) {
            throw new IllegalArgumentException("V1 room max file size");
        }
        if (totalFileSpace < maxFileSize || totalFileSpace > MAX_JSON_SAFE_INTEGER) {
            throw new IllegalArgumentException("V1 room total file space");
        }
        if (maxFileCount < 1) {
            throw new IllegalArgumentException("V1 room max file count");
        }
        if (maxMembers < 1 || maxMembers > 1_000_000) {
            throw new IllegalArgumentException("V1 room max members");
        }
    }
}
