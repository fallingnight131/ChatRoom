package com.fallingnight.chat.application.compatibility.v1;

import java.util.UUID;

public record LegacyV1UsernameChangeCommand(UUID actorAccountId, String newUsername) {}
