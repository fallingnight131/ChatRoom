package com.fallingnight.chat.application.identity;

import java.util.Objects;

/** Non-enumerating identity use-case outcome. */
public sealed interface AuthenticationResult {
    record Established(IssuedSession session) implements AuthenticationResult {
        public Established {
            Objects.requireNonNull(session, "session");
        }
    }

    enum Rejected implements AuthenticationResult {
        INSTANCE
    }
}
