package com.fallingnight.chat.application.compatibility.v1;

@FunctionalInterface
public interface LegacyV1DirectHistoryPort {
    LegacyV1DirectHistoryResult read(LegacyV1DirectHistoryQuery query);
}
