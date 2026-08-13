package com.fallingnight.chat.application.compatibility.v1;

public interface LegacyV1PasswordChangeUseCase {
    LegacyV1PasswordChangeResult change(LegacyV1PasswordChangeCommand command);
}
