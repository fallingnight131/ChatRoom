package com.fallingnight.chat.application.compatibility.v1;

import java.util.Arrays;
import java.util.Optional;

/** Canonical interpretation of the four existing V1 administrative delete modes. */
public enum LegacyV1RoomMessageDeletionMode {
    SELECTED("selected"),
    ALL("all"),
    BEFORE("before"),
    AFTER("after");

    private final String wireValue;

    LegacyV1RoomMessageDeletionMode(String wireValue) {
        this.wireValue = wireValue;
    }

    public String wireValue() { return wireValue; }

    public static Optional<LegacyV1RoomMessageDeletionMode> parse(String value) {
        return Arrays.stream(values()).filter(mode -> mode.wireValue.equals(value)).findFirst();
    }

    public boolean usesCutoff() { return this == BEFORE || this == AFTER; }
}
