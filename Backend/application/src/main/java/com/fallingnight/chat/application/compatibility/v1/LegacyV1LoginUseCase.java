package com.fallingnight.chat.application.compatibility.v1;

import com.fallingnight.chat.application.identity.AuthenticateCommand;

/** Typed V1 login boundary invoked after strict transport decoding. */
@FunctionalInterface
public interface LegacyV1LoginUseCase {
    LegacyV1LoginResult login(AuthenticateCommand command);
}
