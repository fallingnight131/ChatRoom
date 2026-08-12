package com.fallingnight.chat.application.compatibility.v1;

@FunctionalInterface
public interface LegacyV1DirectHistoryUseCase {
    LegacyV1DirectHistoryResult read(LegacyV1DirectHistoryQuery query);
}
