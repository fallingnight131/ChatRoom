package com.fallingnight.chat.application.compatibility.v1;

public interface LegacyV1NicknameChangeUseCase {
    LegacyV1NicknameChangeResult change(LegacyV1NicknameChangeCommand command);
}
