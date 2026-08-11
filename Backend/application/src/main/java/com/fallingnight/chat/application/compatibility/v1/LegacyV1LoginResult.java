package com.fallingnight.chat.application.compatibility.v1;

import java.util.Objects;

/** Generic V1 login outcome; rejection does not reveal which check failed. */
public sealed interface LegacyV1LoginResult {
    record Established(LegacyV1AuthenticatedIdentity identity) implements LegacyV1LoginResult {
        public Established {
            Objects.requireNonNull(identity, "identity");
        }
    }

    enum Rejected implements LegacyV1LoginResult {
        INSTANCE
    }
}
