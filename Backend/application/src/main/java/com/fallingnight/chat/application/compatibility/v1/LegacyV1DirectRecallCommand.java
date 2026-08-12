package com.fallingnight.chat.application.compatibility.v1;

import java.util.UUID;

/** Server-bound actor plus the only V1 recall identity accepted from the wire. */
public record LegacyV1DirectRecallCommand(UUID actorAccountId, long legacyMessageId) { }
