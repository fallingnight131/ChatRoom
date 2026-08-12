package com.fallingnight.chat.application.compatibility.v1;

@FunctionalInterface
public interface LegacyV1DirectMessagePort {
    LegacyV1DirectMessageResult submit(LegacyV1DirectMessageCommand command);
}
