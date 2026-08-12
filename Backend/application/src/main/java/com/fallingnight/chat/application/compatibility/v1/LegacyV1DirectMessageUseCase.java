package com.fallingnight.chat.application.compatibility.v1;

@FunctionalInterface
public interface LegacyV1DirectMessageUseCase {
    LegacyV1DirectMessageResult submit(LegacyV1DirectMessageCommand command);
}
