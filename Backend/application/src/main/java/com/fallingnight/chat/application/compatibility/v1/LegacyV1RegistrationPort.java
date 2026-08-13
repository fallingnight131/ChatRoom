package com.fallingnight.chat.application.compatibility.v1;

public interface LegacyV1RegistrationPort {
    LegacyV1RegistrationPersistenceResult register(LegacyV1RegistrationIntent intent);
}
