package com.fallingnight.chat.application.compatibility.v1;

import com.fallingnight.chat.application.security.SecretBytes;
import java.util.Objects;
import java.util.function.Function;

/** Owned V1 registration request; closing destroys the plaintext password. */
public final class LegacyV1RegistrationCommand implements AutoCloseable {
    private final String username;
    private final String displayName;
    private final SecretBytes passwordUtf8;

    public LegacyV1RegistrationCommand(String username, String displayName, byte[] passwordUtf8) {
        this.username = Objects.requireNonNull(username, "username");
        this.displayName = Objects.requireNonNull(displayName, "displayName");
        this.passwordUtf8 = SecretBytes.copyOf(
                Objects.requireNonNull(passwordUtf8, "passwordUtf8"));
    }
    public String username() { return username; }
    public String displayName() { return displayName; }
    <T> T withPasswordCopy(Function<byte[], T> action) { return passwordUtf8.withCopy(action); }
    public boolean isClosed() { return passwordUtf8.isClosed(); }
    @Override public void close() { passwordUtf8.close(); }
}
