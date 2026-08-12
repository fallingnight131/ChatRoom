package com.fallingnight.chat.application.compatibility.v1;

import java.util.List;

/** Complete bounded active-file list and canonical room quota projection. */
public record LegacyV1RoomFiles(
        List<LegacyV1RoomFile> files,
        long usedFileSpace,
        long maxFileSpace) {
    public static final int MAX_FILES = 1_500;

    public LegacyV1RoomFiles {
        files = List.copyOf(files);
        if (files.size() > MAX_FILES
                || usedFileSpace < 0
                || maxFileSpace < 1
                || maxFileSpace > LegacyV1RoomSettings.MAX_JSON_SAFE_INTEGER
                || usedFileSpace > maxFileSpace
                || files.stream().mapToLong(LegacyV1RoomFile::byteSize).sum()
                        != usedFileSpace) {
            throw new IllegalArgumentException("invalid V1 room files projection");
        }
    }
}
