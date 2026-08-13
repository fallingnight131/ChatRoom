package com.fallingnight.chat.application.compatibility.v1;

import com.fallingnight.chat.application.security.SecretBytes;
import java.util.Objects;
import java.util.UUID;
import java.util.function.BiFunction;

/** Owned V1 credential mutation; closing destroys both password buffers. */
public final class LegacyV1PasswordChangeCommand implements AutoCloseable {
    private final UUID actorAccountId;
    private final UUID currentSessionId;
    private final SecretBytes oldPasswordUtf8;
    private final SecretBytes newPasswordUtf8;

    public LegacyV1PasswordChangeCommand(UUID actorAccountId, UUID currentSessionId,
            byte[] oldPasswordUtf8, byte[] newPasswordUtf8) {
        this.actorAccountId = Objects.requireNonNull(actorAccountId, "actorAccountId");
        this.currentSessionId = Objects.requireNonNull(currentSessionId, "currentSessionId");
        this.oldPasswordUtf8 = SecretBytes.copyOf(
                Objects.requireNonNull(oldPasswordUtf8, "oldPasswordUtf8"));
        this.newPasswordUtf8 = SecretBytes.copyOf(
                Objects.requireNonNull(newPasswordUtf8, "newPasswordUtf8"));
    }

    public UUID actorAccountId() { return actorAccountId; }
    public UUID currentSessionId() { return currentSessionId; }
    <T> T withPasswordCopies(BiFunction<byte[], byte[], T> action) {
        Objects.requireNonNull(action, "action");
        return oldPasswordUtf8.withCopy(oldValue ->
                newPasswordUtf8.withCopy(newValue -> action.apply(oldValue, newValue)));
    }
    public boolean isClosed() {
        return oldPasswordUtf8.isClosed() && newPasswordUtf8.isClosed();
    }
    @Override public void close() {
        oldPasswordUtf8.close(); newPasswordUtf8.close();
    }
}
