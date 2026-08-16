package com.fallingnight.chat.protocol.v2;

import java.util.UUID;

/** Structural validation for the authenticated account-block wire contract. */
public final class ContactPayloadPolicy {
    private ContactPayloadPolicy() { }

    public static void requireValid(SetAccountBlock command) {
        requireUuid(command.getTargetAccountId(), "targetAccountId");
        requireUuid(command.getClientOperationId(), "clientOperationId");
    }

    public static void requireValid(AccountBlockApplied response) {
        requireUuid(response.getActorAccountId(), "actorAccountId");
        requireUuid(response.getTargetAccountId(), "targetAccountId");
        requireUuid(response.getClientOperationId(), "clientOperationId");
        if (response.getActorAccountId().equals(response.getTargetAccountId())) {
            throw new IllegalArgumentException("account block response is self-directed");
        }
    }

    private static void requireUuid(String value, String name) {
        try {
            if (!UUID.fromString(value).toString().equals(value)) {
                throw new IllegalArgumentException(name + " is not canonical");
            }
        } catch (RuntimeException exception) {
            throw new IllegalArgumentException(name + " is not a UUID", exception);
        }
    }
}
