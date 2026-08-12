package com.fallingnight.chat.application.compatibility.v1;

@FunctionalInterface
public interface LegacyV1DirectRecallUseCase {
    LegacyV1DirectRecallResult recall(LegacyV1DirectRecallCommand command);
}
