package com.fallingnight.chat.application.compatibility.v1;

@FunctionalInterface
public interface LegacyV1DirectRecallPort {
    LegacyV1DirectRecallResult recall(LegacyV1DirectRecallCommand command);
}
