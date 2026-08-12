package com.fallingnight.chat.application.compatibility.v1;

@FunctionalInterface
public interface LegacyV1DirectReadUseCase {
    LegacyV1DirectReadResult markRead(LegacyV1DirectReadCommand command);
}
