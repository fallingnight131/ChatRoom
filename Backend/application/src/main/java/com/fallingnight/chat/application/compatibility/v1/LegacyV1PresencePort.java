package com.fallingnight.chat.application.compatibility.v1;

import java.util.Set;
import java.util.UUID;

/** Rebuildable process/gateway presence projection; never durable contact truth. */
@FunctionalInterface
public interface LegacyV1PresencePort {
    Set<UUID> onlineAccounts(Set<UUID> accountIds);
}
