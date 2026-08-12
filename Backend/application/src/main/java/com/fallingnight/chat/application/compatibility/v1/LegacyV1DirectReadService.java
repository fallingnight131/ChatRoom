package com.fallingnight.chat.application.compatibility.v1;

import java.util.Objects;

/** Monotonic server-authorized V1 private-conversation read boundary. */
public final class LegacyV1DirectReadService implements LegacyV1DirectReadUseCase {
    private final LegacyV1DirectReadPort reads;
    public LegacyV1DirectReadService(LegacyV1DirectReadPort reads) {
        this.reads = Objects.requireNonNull(reads, "reads");
    }
    @Override public LegacyV1DirectReadResult markRead(LegacyV1DirectReadCommand command) {
        Objects.requireNonNull(command, "command");
        Objects.requireNonNull(command.actorAccountId(), "actorAccountId");
        if (command.legacyFriendshipId() <= 0
                || command.legacyFriendshipId() > Integer.MAX_VALUE) {
            return LegacyV1DirectReadResult.Rejected.INVALID_FRIENDSHIP_ID;
        }
        LegacyV1DirectReadResult result = Objects.requireNonNull(
                reads.markRead(command), "direct read result");
        if (result instanceof LegacyV1DirectReadResult.Marked marked
                && marked.legacyFriendshipId() != command.legacyFriendshipId()) {
            throw new IllegalStateException("direct read identity changed");
        }
        return result;
    }
}
