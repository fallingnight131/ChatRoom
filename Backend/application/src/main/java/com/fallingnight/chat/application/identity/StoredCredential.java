package com.fallingnight.chat.application.identity;

import java.util.Objects;

/** Transport-neutral credential formats retained during the V1 migration window. */
public sealed interface StoredCredential {
    record Argon2id(String encodedHash) implements StoredCredential {
        public Argon2id {
            Objects.requireNonNull(encodedHash, "encodedHash");
        }
    }

    record LegacySha256(String hexDigest, String salt) implements StoredCredential {
        public LegacySha256 {
            Objects.requireNonNull(hexDigest, "hexDigest");
            Objects.requireNonNull(salt, "salt");
        }
    }
}
