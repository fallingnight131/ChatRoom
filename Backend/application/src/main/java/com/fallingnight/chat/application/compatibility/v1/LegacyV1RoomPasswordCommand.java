package com.fallingnight.chat.application.compatibility.v1;

import com.fallingnight.chat.application.security.SecretBytes;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Function;

/** Owned password-state mutation; closing destroys the requested password bytes. */
public final class LegacyV1RoomPasswordCommand implements AutoCloseable {
    private final UUID actorAccountId;
    private final long legacyRoomId;
    private final SecretBytes passwordUtf8;

    public LegacyV1RoomPasswordCommand(
            UUID actorAccountId, long legacyRoomId, byte[] passwordUtf8) {
        this.actorAccountId = Objects.requireNonNull(actorAccountId, "actorAccountId");
        this.legacyRoomId = legacyRoomId;
        this.passwordUtf8 = SecretBytes.copyOf(
                Objects.requireNonNull(passwordUtf8, "passwordUtf8"));
    }

    public UUID actorAccountId() { return actorAccountId; }
    public long legacyRoomId() { return legacyRoomId; }
    public boolean clearsPassword() {
        return passwordUtf8.withCopy(value -> value.length == 0);
    }
    <T> T withPasswordCopy(Function<byte[], T> action) {
        return passwordUtf8.withCopy(action);
    }
    public boolean isClosed() { return passwordUtf8.isClosed(); }
    @Override public void close() { passwordUtf8.close(); }
}
