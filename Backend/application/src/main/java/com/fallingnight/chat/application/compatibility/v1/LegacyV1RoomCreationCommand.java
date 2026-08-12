package com.fallingnight.chat.application.compatibility.v1;

import com.fallingnight.chat.application.security.SecretBytes;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Function;

/** Owned V1 create-room intent; closing destroys the optional password bytes. */
public final class LegacyV1RoomCreationCommand implements AutoCloseable {
    private final UUID actorAccountId;
    private final String clientRequestId;
    private final String roomName;
    private final SecretBytes passwordUtf8;
    public LegacyV1RoomCreationCommand(UUID actorAccountId, String clientRequestId,
            String roomName, byte[] passwordUtf8) {
        this.actorAccountId = Objects.requireNonNull(actorAccountId, "actorAccountId");
        this.clientRequestId = Objects.requireNonNull(clientRequestId, "clientRequestId");
        this.roomName = Objects.requireNonNull(roomName, "roomName");
        this.passwordUtf8 = passwordUtf8 == null ? null : SecretBytes.copyOf(passwordUtf8);
    }
    public UUID actorAccountId() { return actorAccountId; }
    public String clientRequestId() { return clientRequestId; }
    public String roomName() { return roomName; }
    public boolean hasPassword() { return passwordUtf8 != null; }
    <T> T withPasswordCopy(Function<byte[], T> action) {
        if (passwordUtf8 == null) throw new IllegalStateException("room password absent");
        return passwordUtf8.withCopy(action);
    }
    public boolean isClosed() { return passwordUtf8 == null || passwordUtf8.isClosed(); }
    @Override public void close() { if (passwordUtf8 != null) passwordUtf8.close(); }
}
