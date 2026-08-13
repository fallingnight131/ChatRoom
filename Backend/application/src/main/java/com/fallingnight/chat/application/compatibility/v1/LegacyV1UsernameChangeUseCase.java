package com.fallingnight.chat.application.compatibility.v1;

public interface LegacyV1UsernameChangeUseCase {
    LegacyV1UsernameChangeResult change(LegacyV1UsernameChangeCommand command);
}
