package com.fallingnight.chat.application.compatibility.v1;

import java.util.UUID;

public record LegacyV1NicknameChangeCommand(UUID actorAccountId, String newDisplayName) {}
